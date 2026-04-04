package server.infoserver;

import java.io.IOException;
import java.net.Socket;

import server.database.accounts.Account;
import server.infoserver.packets.InfoPacketReaderTCP;
import server.infoserver.packets.server_tcp.HandshakeA;
import server.interfaces.InfoEvent;
import server.networktools.PacketBuilderTCP;
import server.tools.Config;
import server.tools.PriorityList;

public class InfoServerConnection extends Thread {

	private Socket socket;
	private Account ua = null;
	private PriorityList eventList = new PriorityList();
	private boolean exitThread = false;

	public InfoServerConnection(Socket socket) {
		this.socket = socket;
	}

	public void run() {
		setPriority(Thread.MIN_PRIORITY);
		try {
			send(new HandshakeA());
			while (!exitThread) {
				InfoPacketReaderTCP.readPacket(socket.getInputStream(), this);
				while(!eventList.isEmpty()){
					((InfoEvent)eventList.removeFirst()).execute(this);
				}
			}
		} catch (IOException e) {}
	}

	public void send(PacketBuilderTCP packet) {
		try {
			socket.getOutputStream().write(packet.getData(), 0, packet.size());
		} catch (IOException e) {}
	}

	public void addEvent(InfoEvent event) {
//		synchronized(eventList) { // not needed as Infoserverconnections are singlethreaded
			eventList.add(event);
//			eventList.notify();
//		}
	}

	public void setAccount(Account ua) {
		this.ua = ua;
	}

	public String getServerIP() {
		return Config.getServerIP(socket);
	}

	public void closeTCP() {
		try {
			socket.close();
		} catch (IOException e) {}
		exitThread = true;
	}

	public boolean checkAccountID(int id) {
		if (ua == null) return false;
		if (ua.getId() == id) return true;
		return false;
	}
}
