package server.tools;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Properties;

import server.exceptions.StartupException;

public final class Config {

	private static Properties properties = null;
	private static String localIp;
	private static String wanIp;
	public static boolean debugUnknownPackets = false;
	public static boolean debugSendingPackets = false;
	public static boolean debugEvents = false; 
        public static boolean debugReceivedPackets = false;

	public static void init() throws StartupException {
		if (properties != null) return;
		Out.writeln(Out.Info, "Reading Config");

		properties = new Properties();
		
		try {
			FileInputStream fis = new FileInputStream("ceres.cfg");
			properties.load(fis);
			fis.close();
			Out.writeln(Out.Info, "Configfile successfull read");
						
		} catch (IOException e) {
			throw new StartupException("Failed reading ceres.cfg");
		}

		// reading debugging orders
		String debugText = getProperty("Debug");
		String[] debugTextParts = debugText.split(",");
		for(int i = 0; i < debugTextParts.length; i++) {
			if (debugTextParts[i].trim().equalsIgnoreCase("unknownPackets")) {
				debugUnknownPackets = true;
				continue;
			}
			if (debugTextParts[i].trim().equalsIgnoreCase("sendingPackets")) {
				debugSendingPackets = true;
				continue;
			}
			if (debugTextParts[i].trim().equalsIgnoreCase("events")) {
				debugEvents = true;
				continue;
			}
                        if (debugTextParts[i].trim().equalsIgnoreCase("receivedPackets")) {
				debugReceivedPackets = true;
				continue;
			}
				
		}
		
		
		// checking local IP

		localIp = getProperty("ServerIPLocal");
		
		if (localIp.equalsIgnoreCase("auto")) {
			Out.writeln(Out.Info, "Starting local IP autodetection");

			localIp = null;
			try {
				Enumeration interfaces = NetworkInterface.getNetworkInterfaces();
				while (interfaces.hasMoreElements()) {
					NetworkInterface card = (NetworkInterface) interfaces.nextElement();
					String infoText = "Networkcard: \"" + card.getDisplayName() + "\" ";
					Enumeration addresses = card.getInetAddresses();
					if (!addresses.hasMoreElements()) {
						infoText += "has no IPs";
					}
					
					while (addresses.hasMoreElements()) {
						InetAddress address = (InetAddress) addresses.nextElement();
						byte[] addr = address.getAddress();
						if (addr[0] != 127) {
							DatagramSocket test = new DatagramSocket(12345, address);
							byte[] sendbuffer = new byte[] {1, 2, 3, 4, 5};
							byte[] recvbuffer = new byte[] {0, 0, 0, 0, 0};
							DatagramPacket sendPacket = new DatagramPacket(sendbuffer, 5, address, 12345);
							DatagramPacket recvPacket = new DatagramPacket(recvbuffer, 5);
							try {
								test.setSoTimeout(100);
								test.send(sendPacket);
								test.receive(recvPacket);
								test.close();
								if (recvPacket.getData()[4] == 5) {
									localIp = address.getHostAddress();
									infoText += "IP " + address.getHostAddress() + " is ok :) ";
								} else {
									infoText += "IP " + address.getHostAddress() + " is not ok :( ";
								}
							} catch (IOException e) {
								test.close();
								infoText += "IP " + address.getHostAddress() + " is not ok :( ";
							}
						} else {
							infoText += "IP " + address.getHostAddress() + " is loopback :( ";
						}
					}
					Out.writeln(Out.Info, infoText);
				}
			} catch (SocketException e) {
				// Failed to enumerate network interfaces
			}

			if (localIp == null) {
				Out.writeln(Out.Error, "Local IP autodetection failed.");
			} else {
				Out.writeln(Out.Info, "Local IP autodetection successful.");
			}
		} else if (localIp.equalsIgnoreCase("off")) {
			localIp = null;
		} else {
			try {
				localIp = Inet4Address.getByName(localIp).getHostAddress();
			} catch (UnknownHostException e) {
				Out.writeln(Out.Error, "Local IP is invalid.");
				localIp = null;
			}
		}
		if (localIp == null) {
			Out.writeln(Out.Info, "Local IP is not set, local access disabled.");
		} else {
			Out.writeln(Out.Info, "Local IP is " + localIp + " , local access enabled.");
		}
				
		// checking WAN IP

		wanIp = getProperty("ServerIPWAN");
		
		if (wanIp.equalsIgnoreCase("auto")) {
			Out.writeln(Out.Info, "Starting WAN IP autodetection");
			wanIp = null;
			try {
				URL myip = new URL("http://ip-address.domaintools.com/myip.xml");
		        URLConnection myipc = myip.openConnection();
		        BufferedReader in = new BufferedReader(new InputStreamReader(myipc.getInputStream()));
		        String inputLine;

		        while ((inputLine = in.readLine()) != null)  {
		        	if (inputLine.indexOf("<ip_address>") >= 0) {
		        		int start = inputLine.indexOf('>') + 1;
		        		int end = inputLine.lastIndexOf('<');
		        		wanIp = inputLine.substring(start, end);
		        		break;
		        	}
		        }
		        in.close();
			} catch (IOException e) {
				// WAN IP detection HTTP request failed
			}
			if (wanIp == null) {
				Out.writeln(Out.Error, "WAN IP autodetection failed.");
			} else {
				Out.writeln(Out.Info, "WAN IP autodetection successful.");
			}
		} else if (wanIp.equalsIgnoreCase("off")) {
			wanIp = null;
		} else {
			try {
				wanIp = Inet4Address.getByName(wanIp).getHostAddress();
			} catch (UnknownHostException e) {
				Out.writeln(Out.Error, "WAN IP is invalid.");
			}
		}
		if (wanIp == null) {
			Out.writeln(Out.Info, "WAN IP is not set, internet access disabled.");
		} else {
			Out.writeln(Out.Info, "WAN IP is " + wanIp + " , internet access enabled.");
		}
	}

	public static String getProperty(String key) {
		return properties.getProperty(key);
	}

	public static void stopServer() {
		properties = null;
	}

	public static String getServerIP(Socket socket) {
		String ip;
		byte[] clientip = socket.getInetAddress().getAddress();

		if (clientip[0] == 127 && localIp != null) { //localhost
			ip = localIp;
		} else if ( clientip[0] == 10 //lan
				|| (clientip[0] == (byte)172 && (clientip[1] & 240) == 16) //lan
				|| (clientip[0] == (byte)192 && clientip[1] == (byte)168)) { //lan
			// For Docker/container deployments, use the configured local IP
			// instead of the container's internal address
			if (localIp != null) {
				ip = localIp;
			} else {
				ip = socket.getLocalAddress().getHostAddress();
			}
		} else {
			if (wanIp != null) {
				ip = wanIp;
			} else {
				ip = socket.getLocalAddress().getHostAddress();
			}
		}
		Out.writeln(Out.Info, "getServerIP: client=" + socket.getInetAddress().getHostAddress() + " -> server=" + ip);
		return ip;
	}

}
