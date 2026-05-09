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
	public static boolean debugSubPackets = false;

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
		applyDebugTokens(getProperty("Debug"));
		
		
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
		if (properties == null) return null;
		return properties.getProperty(key);
	}

	/** Set a property at runtime — visible mostly for unit tests
	 *  that need to flip flags like {@code AutoCreateAccounts}
	 *  without touching the actual {@code ceres.cfg} file. */
	public static void setProperty(String key, String value) {
		if (properties == null) {
			properties = new java.util.Properties();
		}
		if (value == null) {
			properties.remove(key);
		} else {
			properties.setProperty(key, value);
		}
	}

	public static void stopServer() {
		properties = null;
	}

	public static String getServerIP(Socket socket) {
		// Defensive: tests sometimes drive packet builders without
		// a real socket. Return loopback so UDPServerData and
		// friends stay testable; production sockets always provide
		// a real client address.
		if (socket == null) return "127.0.0.1";
		String ip;
		byte[] clientip = socket.getInetAddress().getAddress();

		if (clientip[0] == 127) { //localhost
			// Client on loopback (e.g. wine on the same host as Ceres-J).
			// Return 127.0.0.1 so the UDP handover stays on loopback —
			// otherwise we'd hand the client a LAN IP it can't always
			// reach (firewall, docker namespace edge cases, wine network
			// stack quirks). Verified fix for "connect to nethost failed"
			// from wine client during char-select → world-enter handoff.
			ip = "127.0.0.1";
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

	/**
	 * Parse a comma-separated {@code Debug} directive and flip the
	 * corresponding {@code debug*} flags. Unknown tokens are
	 * silently ignored — that matches the original behaviour and
	 * keeps a typo from blocking startup. Package-private so the
	 * unit tests can exercise the parser without touching ceres.cfg.
	 */
	static void applyDebugTokens(String debugText) {
		if (debugText == null) return;
		String[] parts = debugText.split(",");
		for (int i = 0; i < parts.length; i++) {
			String t = parts[i].trim();
			if (t.equalsIgnoreCase("unknownPackets"))      debugUnknownPackets = true;
			else if (t.equalsIgnoreCase("sendingPackets")) debugSendingPackets = true;
			else if (t.equalsIgnoreCase("events"))         debugEvents = true;
			else if (t.equalsIgnoreCase("receivedPackets")) debugReceivedPackets = true;
			else if (t.equalsIgnoreCase("subPackets"))     debugSubPackets = true;
		}
	}

}
