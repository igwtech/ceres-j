package server.gameserver;

import java.util.Random;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.interfaces.GameServerEvent;
import server.interfaces.ServerTCPPacket;
import server.interfaces.ServerUDPPacket;
import server.tools.Debug;
import server.tools.PriorityList;
import server.tools.Timer;

public class Player extends Thread {
	
	private boolean exitThread = false;
	private PriorityList eventList = new PriorityList();
	private GameServerTCPConnection tcpConnection;
	private Account ua;
	private PlayerCharacter pc;
	private byte[] sessionId = new byte[8];
	private GameServerUDPConnection udpConnection;
	private Zone currentZone;
	private long lastping;
	private int channels;	// the channels the player is currentyl listening to
	private int MapID;
	private boolean isloggedin;
	private short Transactionid;

	public Player(Account ua) {
		this.ua = ua;
		channels = 0;
		MapID = 0;
		new Random().nextBytes(sessionId); //TODO: this should be unique
		currentZone = null;
		isloggedin = false;
		Transactionid = 10170;
	}

	public void run () {
		setPriority(Thread.MIN_PRIORITY);
		
		
		while (!exitThread) {
			synchronized (eventList) {
				try {
					eventList.wait(100);
				} catch (InterruptedException e) {
				}
			}

			while (true) {
				GameServerEvent e;
				synchronized (eventList) {
					e = ((GameServerEvent)eventList.getFirst());
					if (e == null) break;
					if (e.getEventTime() > Timer.getRealtime()) break;
					eventList.removeFirst();
				}
				Debug.event(e, this);
				e.execute(this);
				lastping = Timer.getRealtime(); //TODO somthing more usefull
			}
			
			if (lastping + 60000 < Timer.getRealtime()) { // 60sec timeout
				break;
			}
		}
		currentZone.unregisterPlayer(this);
		PlayerManager.remove(this);
		closeTCP();
		closeUDP();
		// TODO unregister at zone, chat, ...
	}

	public void addEvent(GameServerEvent event) {
		if (event == null) return;
		synchronized(eventList) {
			eventList.add(event);
			eventList.notify();
		}
	}

	public void closeTCP() {
		if (tcpConnection != null) {
			tcpConnection.closeTCP();
		}
		tcpConnection = null;
	}

	public void setAccount(Account ua) {
		this.ua = ua;
	}

	public Account getAccount() {
		return ua;
	}

	public void send(ServerTCPPacket packet) {
		if (tcpConnection != null) {
			tcpConnection.send(packet);
		}
	}

	public void send(ServerUDPPacket packet) {
		if (udpConnection != null) {
			udpConnection.send(packet);
		}
	}

	public void setTcpConnection(GameServerTCPConnection connection) {
		tcpConnection = connection;
	}

	public PlayerCharacter getCharacter() {
		return pc;
	}

	public void setCharacter(PlayerCharacter character) {
		pc = character;
		if(currentZone != null)
			currentZone.unregisterPlayer(this);
		currentZone = ZoneManager.getZone(pc.getMisc(PlayerCharacter.MISC_LOCATION));
		currentZone.registerPlayer(this);
	}

	public String getServerIP() {
		return tcpConnection.getServerIP();
	}

	public byte[] getSessionID() {
		return sessionId;
	}

	public void setUdpConnection(GameServerUDPConnection con) {
		udpConnection = con;
	}

	public GameServerUDPConnection getUdpConnection() {
		return udpConnection;
	}

	public Zone getZone() {
			return currentZone;
	}

	public void setLastping() {
		lastping = Timer.getRealtime();
	}

	public void closeUDP() {
		if (udpConnection != null) {
			udpConnection.close();
		}
		udpConnection = null;
	}

	public void updateZone() {
		synchronized(this){
			currentZone.unregisterPlayer(this);
			currentZone = ZoneManager.getZone(pc.getMisc(PlayerCharacter.MISC_LOCATION));
			currentZone.registerPlayer(this);
		}
	}

	public GameServerTCPConnection getTcpConnection() {
		return tcpConnection;
	}
	
	public int getChannels(){
		return channels;
	}
	
	public int getMapID(){
		return MapID;
	}
	
	public boolean isloggedin(){
		return isloggedin;
	}
	
	public void setChannels(int chans){
		channels = chans;
	}
	
	public void setMapID(int ID){
		MapID = ID;
	}
	
	public void setloggedin(){
		isloggedin = true;
	}
	
	public void incrementTransactionID(){
		Transactionid++;
	}
	
	public short getTransactionID(){
		return Transactionid;
	}
}
