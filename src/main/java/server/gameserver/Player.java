package server.gameserver;

import java.util.Random;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.ecs.EcsRegistry;
import server.ecs.PlayerCharacterBridge;
import server.ecs.World;
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
	private long ecsEntity = World.NULL;
	private byte[] sessionId = new byte[8];
	private GameServerUDPConnection udpConnection;
	private Zone currentZone;
	private long lastping;
	private int channels;	// the channels the player is currentyl listening to
	private int MapID;
	private boolean isloggedin;
	private short Transactionid;
	// Zone-handoff state: after the server finishes streaming the world-entry
	// burst, the NC2 client closes its login UDP socket and opens a fresh
	// socket from a new ephemeral port to the zone server. Source IP stays
	// the same, port changes. Marking the player as "handoff pending" at the
	// end of WorldEntryEvent lets the UDP listener disambiguate multi-boxed
	// clients on the same source IP by picking the earliest handoff timestamp.
	private boolean handoffPending = false;
	private long handoffPendingAt = 0L;
	// Debounce cooldown for HandshakeUDPAnswer2: the 3+ HandshakeUDPAnswer
	// events can interleave in the event queue and schedule two Answer2
	// events milliseconds apart. A 500 ms cooldown on this timestamp lets
	// the second Answer2 skip re-scheduling WorldEntryEvent while still
	// allowing a zone-handoff reconnect (seconds later) to re-run it.
	private long lastWorldEntryAt = 0L;
	// Last time we echoed an authoritative PlayerPositionUpdate back to this
	// player in response to a client Movement packet. The modern NCE 2.5
	// client runs local dead-reckoning and expects periodic server-side
	// position confirmation; without it the prediction window expires after
	// ~10-15 s and the "SYNCHRONIZING INTO CITY ZONE" overlay re-appears.
	// Throttled to one echo per 500 ms to approximately match retail's
	// ~20-25 reliable 0x03->0x1b packets per session (seen across all 4
	// captures) rather than echoing every 60 ms movement packet.
	private long lastPositionEchoAt = 0L;
	// Per-session UDP listener: each login reserves its own server port,
	// matching NC2 retail's session-per-port design. Null if the player
	// falls back to the shared ListenerUDP (pool exhausted, bind failure).
	private PlayerUdpListener udpListener;

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
					Thread.currentThread().interrupt();
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
		if (udpListener != null) {
			int releasedPort = udpListener.getPort();
			udpListener.shutdown();
			UdpPortPool.release(releasedPort);
			udpListener = null;
		}
		if (ecsEntity != World.NULL) {
			EcsRegistry.world().destroyEntity(ecsEntity);
			ecsEntity = World.NULL;
		}
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

		// Materialize the character into the ECS so systems that have been ported
		// (currently: Movement) can read/write it via component arrays instead of
		// going through PlayerCharacter directly.
		if (ecsEntity == World.NULL) {
			ecsEntity = EcsRegistry.world().createEntity();
		}
		PlayerCharacterBridge.materialize(EcsRegistry.components(), World.index(ecsEntity), pc);
	}

	/**
	 * Returns the ECS entity handle associated with this player, or
	 * {@link World#NULL} if no character has been attached yet.
	 */
	public long getEcsEntity() {
		return ecsEntity;
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

	public boolean isHandoffPending() {
		return handoffPending;
	}

	public long getHandoffPendingAt() {
		return handoffPendingAt;
	}

	public void markHandoffPending() {
		handoffPending = true;
		handoffPendingAt = Timer.getRealtime();
	}

	public void clearHandoffPending() {
		handoffPending = false;
		handoffPendingAt = 0L;
	}

	public long getLastWorldEntryAt() {
		return lastWorldEntryAt;
	}

	public void setLastWorldEntryAt(long at) {
		this.lastWorldEntryAt = at;
	}

	public long getLastPositionEchoAt() {
		return lastPositionEchoAt;
	}

	public void setLastPositionEchoAt(long at) {
		this.lastPositionEchoAt = at;
	}

	public PlayerUdpListener getUdpListener() {
		return udpListener;
	}

	public void setUdpListener(PlayerUdpListener l) {
		this.udpListener = l;
	}

	/**
	 * Per-session UDP port allocated for this player, or the shared
	 * legacy port 5000 if no per-session listener was created. Used by
	 * {@code UDPServerData} to tell the client where to send UDP traffic.
	 */
	public int getUdpPort() {
		return udpListener != null ? udpListener.getPort() : 5000;
	}
}
