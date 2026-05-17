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

	public Movement(byte[] subPacket) {
		super(subPacket);
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
		boolean hasY = (type & 0x01) != 0;
		boolean hasZ = (type & 0x02) != 0;
		boolean hasX = (type & 0x04) != 0;
		int newY = hasY ? readShort() - 32000 : 0;
		int newZ = hasZ ? readShort() - 32000 : 0;
		int newX = hasX ? readShort() - 32000 : 0;

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
