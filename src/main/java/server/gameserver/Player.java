package server.gameserver;

import java.util.Random;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.ecs.EcsRegistry;
import server.ecs.PlayerCharacterBridge;
import server.ecs.World;
import server.gameserver.packets.client_udp.EquipHolster;
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
	/** Currently-equipped toolbelt slot, as commanded by the client's
	 *  EquipHolster request: {@code 0x00} = holster/unarmed,
	 *  {@code 0x01/0x02/0x04/0x08} = toolbelt slot bitmask. Tracks the
	 *  acting player's selected weapon/tool so peers and re-zones can
	 *  reflect it. Not yet DB-persisted (PlayerCharacter has no slot
	 *  column) — resets to holster on login. */
	private int equippedSlot = EquipHolster.SLOT_HOLSTER;
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
	// City-sector walk-cross marker. Set by SZoning1ConfirmEvent when the
	// committed destination is an INDEXED CITY zone (worldId < 2001 — the
	// exact complement of ZoneBoundaries.isWastelandOutdoor). Retail's
	// RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT pcap proves the server sends
	// NO self-position (no 0x03/0x2c StartPos, no 0x03/0x1b self-pos) for
	// a city walk-cross — the client self-positions from local .dat
	// geometry across the sector seam. Non-consuming so every self-pos
	// emitter in the reconnect burst sees it; cleared explicitly once the
	// burst that honoured it has run, so a later genuine fresh login
	// still gets its StartPos. See zone_portal_params.md §7, task #174.
	private volatile boolean pendingCityCrossSelfPosSuppress = false;
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
	// Observable client phase (login → world → cross-pending …). Phase 1
	// observe-only: emit sites + ack-handlers call into the machine,
	// nothing reads it for control flow yet. Task #239. See
	// `server-lacks-client-state-machine` memory.
	private final server.gameserver.state.ClientStateMachine stateMachine
		= new server.gameserver.state.ClientStateMachine();
	// BSPs (worldname strings) the client has loaded this session.
	// Used to suppress 0x83/0x0d LoadingBegin on cross-OUT or
	// re-cross to a cached BSP — retail empirically emits 0x83/0x0d
	// ONLY when the destination is being loaded for the first time
	// (verified 2026-05-24, task #253). Re-emitting 0x83/0x0d for
	// a cached BSP likely triggers the client's loading-screen state
	// for a BSP it already has — root cause hypothesis for #208.
	// Synchronised because cross handlers run on the event loop thread
	// but Player can also be accessed by UDP listener threads.
	private final java.util.Set<String> loadedBspPaths =
		java.util.Collections.synchronizedSet(
			new java.util.HashSet<>());

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
				// NOTE: lastping is NO LONGER updated here. Server-
				// scheduled heartbeat events (TimeSyncHeartbeat,
				// PoolStatusHeartbeat, ZoneStateHeartbeat) execute
				// continuously even on dead sessions; updating
				// lastping here meant a dead Player was never reaped
				// by the idle timeout (task #215 — exposed by the
				// nc2-bot dashboard reconnect test 2026-05-19).
				// lastping is now refreshed exclusively by
				// PlayerUdpListener / GameServerTCPConnection when a
				// REAL client packet arrives — see setLastping().
			}

			// Idle-session reaper. 30s without any client packet =>
			// teardown. Retail kicks idle sessions even faster (~15s
			// in some captures); 30s is the conservative middle ground
			// that survives a brief network hiccup. Configurable later
			// if we need per-deployment tuning.
			if (lastping + 30000 < Timer.getRealtime()) {
				server.tools.Out.writeln(server.tools.Out.Info,
					"Player.run: idle timeout (>30s no client packet)"
					+ " for "
					+ (pc != null ? pc.getName() : "?")
					+ " — closing session");
				break;
			}

			// Avoid burning a core when the event queue is empty.
			// 50ms is well below any user-visible latency floor and
			// well below the idle-check granularity, so it changes
			// no observable behaviour beyond not pegging CPU.
			try {
				Thread.sleep(50);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
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

	/** Per-session observable FSM. See {@link
	 *  server.gameserver.state.ClientStateMachine}. Never null —
	 *  initialised at Player construction. */
	public server.gameserver.state.ClientStateMachine getStateMachine() {
		return stateMachine;
	}

	/**
	 * Has the client already loaded the BSP at {@code worldPath}
	 * this session? Use to decide whether to emit 0x83/0x0d
	 * LoadingBegin before 0x83/0x0c Location: retail empirically
	 * emits 0x83/0x0d ONLY for cross-IN to a new BSP, never for
	 * cross-OUT or re-cross to a cached one (task #253).
	 *
	 * <p>{@code worldPath} matches the ASCII string emitted in
	 * {@link server.gameserver.packets.server_tcp.Location}
	 * (e.g. {@code "plaza/plaza_p1"},
	 * {@code "startmissions/reaktor"}).
	 */
	public boolean hasLoadedBsp(String worldPath) {
		if (worldPath == null) return false;
		return loadedBspPaths.contains(worldPath);
	}

	/**
	 * Mark {@code worldPath} as loaded. Called after a successful
	 * Location emission completes (i.e. after the client has had
	 * the destination delivered to it, in
	 * {@link server.gameserver.internalEvents.PortalCrossCommitEvent}),
	 * and at first-login spawn (in WorldEntryEvent) so the login
	 * zone doesn't re-trigger LoadingBegin on subsequent crosses.
	 */
	public void markBspLoaded(String worldPath) {
		if (worldPath != null && !worldPath.isEmpty()) {
			loadedBspPaths.add(worldPath);
		}
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

	/** @return the currently-equipped toolbelt slot (see
	 *  {@link #setEquippedSlot(int)}). */
	public int getEquippedSlot() {
		return equippedSlot;
	}

	/** Record the toolbelt slot the player just equipped/holstered.
	 *  {@code 0x00} = holster, {@code 0x01/0x02/0x04/0x08} = slot bitmask. */
	public void setEquippedSlot(int slot) {
		equippedSlot = slot & 0xff;
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

		// 4. Live-CHARSYS resync (task #201). PoolUpdate (the per-entity
		// 0x1f/01/00/50 signed delta sent above) does NOT move the local
		// player's HUD — Ghidra-pinned: the HUD HP widget is driven by
		// the CHARSYS section-2 bucket sum charsys+0x3f4/+0x3f8/+0x3fc
		// via FUN_0080c660, repainted only when the CHARSYS buffer is
		// re-parsed (FUN_00845820). The single-packet 0x03/0x2c v0x02
		// CharInfo (LiveCharInfoSync, #194) is the only lever that runs
		// that parse with no zone reload. Without this, combat / fall /
		// admin damage changed pc HP server-side but the client HUD
		// never reflected it (user-confirmed). Same lever .sethp uses.
		try {
			send(server.gameserver.packets.server_udp.LiveCharInfoSync.of(this));
		} catch (Exception e) { /* ignore */ }

		// 5. If dead, send death packet and schedule respawn
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

	/** Mark that the next world-state burst is a city walk-cross and
	 *  must NOT push a server self-position (the client self-positions,
	 *  as retail does). Set by SZoning1ConfirmEvent for a city dest. */
	public void setPendingCityCrossSelfPosSuppress(boolean v) {
		this.pendingCityCrossSelfPosSuppress = v;
	}

	/** Whether the current world-state burst must suppress the server
	 *  self-position (city walk-cross). Non-consuming; cleared via
	 *  {@link #clearPendingCityCrossSelfPosSuppress}. */
	public boolean isPendingCityCrossSelfPosSuppress() {
		return this.pendingCityCrossSelfPosSuppress;
	}

	/** Clear the city-cross self-position suppression flag (called at
	 *  the end of the world-state burst that honoured it). */
	public void clearPendingCityCrossSelfPosSuppress() {
		this.pendingCityCrossSelfPosSuppress = false;
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

	/**
	 * Authoritative server-side no-collision / free-flight flag, toggled
	 * by the {@code noclip} GM command (tasks #179/#182).
	 * {@code Movement.execute()} consults this: when {@code true} the
	 * server skips its position-sanity gate and accepts otherwise
	 * out-of-bounds coordinates for this session (free flight); when
	 * {@code false} an out-of-bounds position update is dropped.
	 * Default {@code false}.
	 */
	private volatile boolean noclip = false;

	public boolean isNoclip() {
		return noclip;
	}

	public void setNoclip(boolean enabled) {
		this.noclip = enabled;
	}

	/**
	 * rawObjectId of the chair this player is currently seated on, or
	 * {@code 0} when not seated. Set by
	 * {@link server.gameserver.packets.client_udp.UseItem} when a
	 * chair world-object is clicked; cleared on the next movement or
	 * an explicit exit-seat request. Transient session state (not
	 * persisted) — same lifetime model as {@link #noclip}.
	 */
	private volatile int seatedChairRawId = 0;

	/**
	 * Wall-clock millis when {@link #setSeatedChairRawId(int)} last
	 * transitioned the player into a seated state (non-zero). Used by
	 * {@link server.gameserver.packets.client_udp.Movement} to enforce
	 * a brief grace period after a chair-sit: in-flight C→S movement
	 * packets that the client emitted just before / concurrent with
	 * its chair-use click would otherwise race the seat-set and unseat
	 * the player within a frame (the bug behind #205/#232 —
	 * "no sit animation" / "equip-weapon sound on chair"). 0 means
	 * not seated.
	 */
	private volatile long seatedAtMillis = 0;

	/** rawObjectId of the chair this player is seated on, or 0. */
	public int getSeatedChairRawId() {
		return seatedChairRawId;
	}

	/** True if this player is currently seated on a chair. */
	public boolean isSeated() {
		return seatedChairRawId != 0;
	}

	/**
	 * Wall-clock millis when the player last transitioned from
	 * standing to seated. Returns 0 if not seated. Used by Movement
	 * to apply a stand-on-move grace period (see field javadoc).
	 */
	public long getSeatedAtMillis() {
		return seatedAtMillis;
	}

	/**
	 * Set the seated chair rawObjectId ({@code 0} = stand up).
	 * Records the transition time on a 0→non-zero edge so the
	 * Movement handler can enforce a grace window before treating
	 * in-flight movement as an explicit stand-up.
	 */
	public void setSeatedChairRawId(int rawObjectId) {
		if (rawObjectId != 0 && this.seatedChairRawId == 0) {
			this.seatedAtMillis = System.currentTimeMillis();
		} else if (rawObjectId == 0) {
			this.seatedAtMillis = 0;
		}
		this.seatedChairRawId = rawObjectId;
	}
}
