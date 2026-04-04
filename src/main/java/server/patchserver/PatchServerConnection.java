package server.patchserver;

import java.io.IOException;
import java.net.Socket;

import server.interfaces.PatchEvent;
import server.networktools.PacketBuilderTCP;
import server.patchserver.packets.PatchPacketReaderTCP;
import server.patchserver.packets.server_tcp.PatchHandshakeA;
import server.tools.PriorityList;

public final class PatchServerConnection extends Thread {

	private Socket socket;
	private boolean exitThread = false;
	private PriorityList eventList = new PriorityList();

	public PatchServerConnection(Socket socket) {
		this.socket = socket;
	}

	public void run() {
		try {
			send(new PatchHandshakeA());
			while (!exitThread) {
				PatchPacketReaderTCP.readPacket(socket.getInputStream(), this);
				while(!eventList.isEmpty()){
					((PatchEvent)eventList.removeFirst()).execute(this);
				}
			}
		} catch (IOException e) {
			// Client disconnected during patch session
		}
		try {
			socket.close();
		} catch (IOException e) {
			// Ignored; socket already closed
		}
	}

	public void closeTCP() {
		try {
			socket.close();
		} catch (IOException e) {
			// Ignored; socket already closed
		}
		exitThread = true;
	}

/*	public String getServerIP() {
		return Config.getServerIP(socket);
	}*/

	public void send(PacketBuilderTCP packet) {
		try {
			socket.getOutputStream().write(packet.getData(), 0, packet.size());
		} catch (IOException e) {
			// Socket write failed; connection likely closed by client
		}
	}

	public void addEvent(PatchEvent event) {
//		synchronized(eventList) { //not needed as every connection for itself is single threaded
			eventList.add(event);
//			eventList.notify(); // not needed here
//		}
	}
}

