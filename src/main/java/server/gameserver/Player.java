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
	/** Destination zone_id captured from a Zoning1 (0x03/0x22/0x0d)
	 *  notification but NOT yet committed. The actual server-side
	 *  zone switch (MISC_LOCATION + updateZone()) is deferred to the
	 *  Zoning2 (0x03/0x22/0x03) handler, matching retail: Zoning1 is
	 *  a heads-up only; switching the zone server-side on Zoning1
	 *  starts streaming the destination zone's NPC/state to a client
	 *  still in the source BSP, which wedges the client's zone-cross
	 *  state machine (it never emits Zoning2). 0 = none pending. */
	private int pendingZoneId;

	public int getPendingZoneId() {
		return pendingZoneId;
	}

	public void setPendingZoneId(int zoneId) {
		this.pendingZoneId = zoneId;
	}

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
	// Zone-handoff suppression: when the client reconnects from a new
	// port after BSP load, we suppress handshake (0x01) and sync (0x03)
	// packets until the first gamedata (0x13) arrives. Retail never has
	// zone-handoff so processing these packets causes state resets.
	private volatile boolean zoneHandoffActive = false;
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
			if (currentZone != null) {
				currentZone.unregisterPlayer(this);
			}
			currentZone = ZoneManager.getZone(pc.getMisc(PlayerCharacter.MISC_LOCATION));
			if (currentZone != null) {
				currentZone.registerPlayer(this);
			}
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

	/**
	 * Apply damage through the retail combat sequence.
	 * If HP reaches 0, triggers death screen and schedules respawn.
	 */
	public void applyDamage(float damage, int attackerId) {
		if (pc == null) return;
		int dmgInt = (int) damage;
		int newHp = pc.getHealth() - dmgInt;
		pc.setHealth(Math.max(0, newHp));

		// 1. Damage tick (1f 01 00 25 23 30): retail emits this every
		// ~500 ms while a character is taking damage — appears to drive
		// the HUD HP-bar animation. Send first so the client knows
		// damage is incoming before the pool delta arrives.
		try {
			send(new server.gameserver.packets.server_udp.DamageTick(this));
		} catch (Exception e) { /* ignore */ }

		// 2. Pool delta: signed delta applied client-side. Retail's
		// 0x50 sub-opcode is "delta", not "set"; passing newHp here
		// caused the client to ADD HP instead of subtract.
		try {
			send(new server.gameserver.packets.server_udp.PoolUpdate(
				this, server.gameserver.packets.server_udp.PoolUpdate.POOL_HP,
				-dmgInt, pc.getMaxHealth()));
		} catch (Exception e) { /* ignore */ }

		// 3. Rich damage event (R:0x1f 0x25 0x06) — retail sends this
		// at the fatal blow with target/attacker/value fields.
		try {
			send(new server.gameserver.packets.server_udp.DamageEvent(
				this, damage, attackerId, 0x0a));
		} catch (Exception e) { /* ignore */ }

		// 3. If dead, send death packet and schedule respawn
		if (newHp <= 0) {
			try {
				send(new server.gameserver.packets.server_udp.PlayerDeath(this, attackerId));
			} catch (Exception e) { /* ignore */ }
			try {
				send(new server.gameserver.packets.server_udp.PoolStatusBroadcast(this));
			} catch (Exception e) { /* ignore */ }
			addEvent(new server.gameserver.internalEvents.RespawnEvent());
		}
	}

	/** Instant kill: apply lethal damage. */
	public void die() {
		if (pc == null) return;
		applyDamage(pc.getHealth() + 100, 0);
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

	public boolean isZoneHandoffActive() { return zoneHandoffActive; }
	public void setZoneHandoffActive(boolean v) { this.zoneHandoffActive = v; }

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

	/**
	 * Authoritative server-side no-collision / free-flight flag, toggled
	 * by the {@code noclip} GM command (task #179). The movement
	 * validator consults this so out-of-bounds positions are not
	 * rejected for a noclip GM. Default {@code false}.
	 */
	private volatile boolean noclip = false;

	public boolean isNoclip() {
		return noclip;
	}

	public void setNoclip(boolean enabled) {
		this.noclip = enabled;
	}
}
