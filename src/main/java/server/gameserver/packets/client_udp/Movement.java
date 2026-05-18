package server.gameserver.packets.client_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.ecs.Components;
import server.ecs.EcsRegistry;
import server.ecs.World;
import server.gameserver.Player;
import server.gameserver.ZoneBoundaries;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_tcp.Location;
import server.gameserver.packets.server_tcp.Packet830D;
import server.tools.Out;

/**
 * Client-&gt;server movement packet.
 *
 * <p>Proof-of-concept subsystem for the light ECS: position / orientation / status
 * are written into the ECS component arrays first and then synced back to the
 * legacy {@link PlayerCharacter}. The rest of the codebase (zone broadcast,
 * persistence) still reads from {@code PlayerCharacter} so the port is incremental.
 */
public class Movement extends GamePacketDecoderUDP {

	/** Raw sub-packet bytes, retained so the seated-anchor
	 *  discriminator can peek the {@code 0x20} type byte before the
	 *  streaming decoder consumes it. Layout (reliable form, as the
	 *  dispatcher hands it over):
	 *  {@code [0]=0x03 [1..2]=seq LE2 [3]=0x20 [4..5]=localId LE2
	 *  [6]=type [7..]=payload}. */
	private final byte[] raw;

	public Movement(byte[] subPacket) {
		super(subPacket);
		this.raw = subPacket;
	}

	/**
	 * Seated-anchor / locomotion discriminator for the {@code 0x20}
	 * movement sub-packet.
	 *
	 * <p>Byte-pinned from
	 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap}
	 * (server 157.90.195.74). While a player is seated in a chair the
	 * retail client does <em>not</em> stop sending {@code 0x20}
	 * packets — it sends a continuous <em>seated-anchor sync</em>:
	 *
	 * <pre>
	 *   20 &lt;localId LE2&gt; 80 &lt;seated chair rawObjectId LE4&gt; 00
	 * </pre>
	 *
	 * <p>(observed e.g. {@code 20 03 00 80 00 48 08 00 00} at
	 * t=204.619s, repeated ~once/sec for the whole time the captured
	 * player stayed seated). The type byte is {@code 0x80}; a real
	 * locomotion {@code 0x20} uses {@code 0x7f} (and other
	 * movement-bit values) and carries float coordinates instead.
	 * Retail's genuine stand-up signal is the explicit
	 * {@code 0x03/0x1f/0x22} ({@link ExitSeatRequest}) or a real
	 * locomotion {@code 0x20}, never the {@code 0x80} anchor.
	 *
	 * <p>The previous code stood the player up on <em>every</em>
	 * {@code 0x20}, so the first post-sit anchor sync (arriving within
	 * a frame of the {@code 0x21} sit broadcast) immediately cleared
	 * the seat and broadcast {@link ExitSeat} — the client popped out
	 * of the chair before the sit was ever visible.
	 *
	 * @return {@code true} if this {@code 0x20} is the seated-anchor
	 *         keepalive (type byte {@code 0x80}), which must be
	 *         ignored entirely while seated.
	 */
	private boolean isSeatedAnchorSync() {
		// raw[6] = the 0x20 type byte (see field javadoc for layout).
		return raw != null && raw.length > 6
				&& (raw[6] & 0xFF) == 0x80;
	}

	/**
	 * Conservative world-coordinate sanity bound. The 16-bit movement
	 * field has a usable range of {@code -32000..+33535} after the
	 * {@code -32000} bias; legitimate NC2 zone coordinates sit well
	 * inside {@code ±SANE_COORD}. A value outside this is either a
	 * corrupt/replayed packet or a teleport-hack and is dropped for a
	 * non-noclip session. This is NOT a retail-derived anti-cheat
	 * (retail's exact validator is unknown / uncaptured) — it is the
	 * minimal honest gate the {@code noclip} flag can meaningfully
	 * switch off, so {@link Player#isNoclip()} genuinely changes
	 * movement acceptance instead of being a no-op.
	 */
	static final int SANE_COORD = 30000;

	private static boolean outOfBounds(int v) {
		return v < -SANE_COORD || v > SANE_COORD;
	}

	public void execute(Player pl) {
		PlayerCharacter pc = pl.getCharacter();

		// ── Seated-anchor sync: ignore entirely ─────────────────────
		// While seated the retail client keeps sending 0x20 packets,
		// but as a seated-anchor keepalive (type byte 0x80, carrying
		// the chair rawObjectId) — NOT locomotion. Treating it as a
		// move stood the player up within a frame of the 0x21 sit
		// broadcast, so the chair-sit was never visible. Drop it: do
		// not stand up, do not commit a position. The genuine
		// stand-up is the explicit 0x03/0x1f/0x22 (ExitSeatRequest)
		// or a real locomotion 0x20 (handled below). Byte-pinned from
		// strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap — see
		// isSeatedAnchorSync().
		if (pl.isSeated() && isSeatedAnchorSync()) {
			return;
		}

		// ── Stand up if seated ──────────────────────────────────────
		// A seated player (UseItem on a chair set seatedChairRawId)
		// stands the moment they send a real locomotion movement
		// packet (anything that is not the seated-anchor sync handled
		// above). Clear the transient sit-state and broadcast the
		// exit-seat posture (0x03/0x1f/<localId>/0x22) to the zone so
		// peers stop rendering them seated. The position itself is
		// committed by the normal movement path below (which also
		// drives the peer SMovement broadcast). See SitOnChair /
		// ExitSeat.
		if (pl.isSeated()) {
			pl.setSeatedChairRawId(0);
			server.gameserver.Zone sz = pl.getZone();
			if (sz != null) {
				sz.sendPlayerExitSeat(pl);
			}
		}

		skip(3);

		int type = read();

		long handle = pl.getEcsEntity();
		World world = EcsRegistry.world();
		Components c = EcsRegistry.components();
		boolean useEcs = world.isAlive(handle);
		int e = useEcs ? World.index(handle) : -1;

		// Decode candidate axes first so the position-sanity gate can
		// veto the whole update atomically (committing X but rejecting
		// Y would leave the player half-moved).
		//
		// Coordinates are float32 LE in the NCE 2.5 ("Evolution")
		// client — verified against retail pcaps RETAIL_NORMAN,
		// RETAIL_DRSTONE and RETAIL_LONG_PARTY_A (C→S movement and
		// S→C peer broadcasts), consistent with the StartPos 0x03/0x2c
		// float frame. The previous `readShort() - 32000` decode
		// misread 2 of each float's 4 bytes as a uint16 and persisted
		// garbage MISC coords, so the next login's (correctly
		// float-encoded) StartPos re-emitted nonsense — task #174
		// "spawn at map centre". MISC store is integral; round to the
		// nearest world unit.
		boolean hasY = (type & 0x01) != 0;
		boolean hasZ = (type & 0x02) != 0;
		boolean hasX = (type & 0x04) != 0;
		int newY = hasY ? Math.round(readFloat()) : 0;
		int newZ = hasZ ? Math.round(readFloat()) : 0;
		int newX = hasX ? Math.round(readFloat()) : 0;

		// Noclip (GM free-flight) bypasses the gate entirely — that is
		// the authoritative effect of Player.setNoclip(true): the
		// server stops rejecting otherwise-anomalous positions for
		// this session. A normal session with an out-of-bounds axis
		// has its position update dropped (coords not committed, no
		// movement broadcast) but the rest of the packet still
		// processes so orientation/status stay in sync.
		boolean acceptPosition = pl.isNoclip()
				|| !((hasY && outOfBounds(newY))
				   || (hasZ && outOfBounds(newZ))
				   || (hasX && outOfBounds(newX)));

		if (!acceptPosition) {
			Out.writeln(Out.Warning,
				"Movement: rejected out-of-bounds position for "
				+ (pc != null ? pc.getName() : "?")
				+ " (X=" + (hasX ? newX : "-")
				+ " Y=" + (hasY ? newY : "-")
				+ " Z=" + (hasZ ? newZ : "-")
				+ "); enable !noclip to fly there");
		}

		if (acceptPosition && hasY) {
			if (useEcs) c.posY.set(e, newY);
			pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, newY);
		}
		if (acceptPosition && hasZ) {
			if (useEcs) c.posZ.set(e, newZ);
			pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, newZ);
		}
		if (acceptPosition && hasX) {
			if (useEcs) c.posX.set(e, newX);
			pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, newX);
		}
		if ((type & 0x08) != 0) {
			int v = read();//info_byte("up-mid-down(d6-80-2a)");
			if (useEcs) c.tilt.set(e, v);
			pc.setMisc(PlayerCharacter.MISC_TILT, v);
		}
		if ((type & 0x10) != 0) {
			int v = read();//info_byte("s-e-n-w-s(0-45-90-135-180)");
			if (useEcs) c.orientation.set(e, v);
			pc.setMisc(PlayerCharacter.MISC_ORIENTATION, v);
		}
		if ((type & 0x20) != 0) {
			int v = read();//info_byte("0x02-kneeing 0x08-leftstep 0x10-rightstep 0x20-walking 0x40-forward 0x80-backward");
			if (useEcs) c.status.set(e, v);
			pc.setMisc(PlayerCharacter.MISC_STATUS, v);
		}
		if ((type & 0x40) != 0) {
			// ?? :(
		}
		// Only broadcast the movement to the zone if the position was
		// accepted; broadcasting a rejected (stale) position would
		// rubber-band peers' view of this player.
		if (acceptPosition) {
			pl.getZone().sendPlayerMovement(pl);
		}

		// REMOVED 2026-05-16: legacy server-side zone-boundary
		// detector. It called ZoneBoundaries.resolveTransition()
		// (hardcoded pepper p1/p2/p3 Y thresholds) on EVERY movement
		// packet and pushed TCP Packet830D + Location whenever the
		// player crossed one. Wire evidence (/tmp/ceres_p1p3.pcap
		// TCP:12000) showed this firing dozens of times/sec as the
		// player walked in pepper — a Location flood alternating
		// pepper_p3/p2/p1 that made the client's WorldClient log
		// "Connecting to worldserver failed" ×3 → AbortSession →
		// permanent "Synchronizing" stuck.
		//
		// The authoritative model (decoded this session) is
		// CLIENT-DRIVEN: the client detects the boundary from its
		// own BSP and sends Zoning1 (0x03/0x22/0x0d); the server
		// reacts ONLY to Zoning1 (SZoning1ConfirmEvent), never by
		// re-deriving transitions from raw movement against a
		// hardcoded 3-zone table. This stale dual path actively
		// broke the cross — removed. ZoneBoundaries.java is kept
		// (its mirror math is still referenced/tested) but is no
		// longer invoked from the movement hot path.

		// Previously attempted: echo a reliable 0x03->0x1b PlayerPositionUpdate
		// back to the moving player every 500 ms. That REGRESSED movement
		// (rubberbanding): the reliable position update overrides the
		// client's local prediction, so the client got snapped back each
		// tick and the gameplay loop starved (0x1f events 448 -> 9).
		// Retail's 20-25 reliable 0x03->0x1b per session turn out to be
		// a one-shot burst during zone population, not periodic echoes.
		// Leaving the logic off here; the real fix for the 10-15 s
		// "re-sync" is more likely a different packet type (raw 0x1b
		// unreliable broadcast, or a session-alive marker), not a
		// reliable self-echo.
	}
}
