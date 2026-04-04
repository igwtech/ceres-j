package server.gameserver;

import java.io.IOException;
import java.net.Socket;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacterManager;
import server.gameserver.packets.GamePacketReaderTCP;
import server.gameserver.packets.server_tcp.HandshakeA;
import server.interfaces.GameServerEvent;
import server.interfaces.ServerTCPPacket;
import server.interfaces.ServerUDPPacket;
import server.tools.Config;
import server.tools.Debug;
import server.tools.PriorityList;

public class GameServerTCPConnection extends Thread {

	Socket socket;
	private boolean exitThread = false;
	private Player pl;
	private PriorityList eventList = new PriorityList();
	private Account ua;

	public GameServerTCPConnection(Socket socket) {
		this.socket = socket;
	}
	
	public void run() {
		setPriority(Thread.MIN_PRIORITY);
		try {
			pl = null;
			send(new HandshakeA());
			while (!exitThread) {
				GamePacketReaderTCP.readPacket(socket.getInputStream(), this);
				while(!eventList.isEmpty()){
					Debug.event((GameServerEvent)eventList.getFirst(), this);
					((GameServerEvent)eventList.removeFirst()).execute(this);
				}
			}
		} catch (IOException e) {
			closeTCP();
		}
	}
	
	public void send(ServerTCPPacket packet) {
		Debug.sendPacket(packet, this);
		synchronized(socket){
			try {
				socket.getOutputStream().write(packet.getData(), 0, packet.size());
			} catch (IOException e) {
				// Socket write failed; connection likely closed by client
			}
		}
	}

	public void addEvent(GameServerEvent event) {
		if (pl != null)
			pl.addEvent(event);
		else
			eventList.add(event);
	}

	public void closeTCP() {
//		Out.writeln(Out.Debug, "Closing connection(" + socket.getPort() + "/" + this.getName() + ")");
//		Thread.dumpStack();
		try {
			socket.close();
		} catch (Exception e) {
			// Ignored; socket already closing
		}
		exitThread = true;
		// help gc
		socket = null;
	}

	public void setAccount(Account ua) {
		if (this.ua == ua) return;
		if (this.ua != null) {
			this.ua.setCurrentTCPConnection(null);
		}
		if (ua.getCurrentTCPConnection() != null) {
			ua.getCurrentTCPConnection().closeTCP();
		}
		ua.setCurrentTCPConnection(this);
		this.ua = ua;
	}

	public boolean checkAccountID(int id) {
		if (ua == null) return false;
		if (ua.getId() == id) return true;
		return false;
	}

	public Account getAccount() {
		return ua;
	}

	public void activatePlayer(int spot) {
		pl = PlayerManager.findPlayer(ua);
		pl.setTcpConnection(this);
		// Clear old UDP connection so a new one can be established
		pl.closeUDP();
		pl.setCharacter(PlayerCharacterManager.getCharacter(ua.getChar(spot)));
	}

	public String getServerIP() {
		return Config.getServerIP(socket);
	}

	public void send(ServerUDPPacket packet) {}

	public int getPort() {
		if (socket == null) return -1;
		return socket.getPort();
	}
}	

