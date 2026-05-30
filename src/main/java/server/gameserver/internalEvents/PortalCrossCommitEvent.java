package server.gameserver.internalEvents;

import server.database.playerCharacters.PlayerCharacter;
import server.database.playerCharacters.PlayerCharacterManager;
import server.gameserver.Player;
import server.gameserver.PortalResolver;
import server.gameserver.packets.server_tcp.InteractionAck;
import server.gameserver.packets.server_tcp.Location;
import server.gameserver.packets.server_udp.ChangeLocation;
import server.gameserver.state.ClientState;
import server.tools.Out;
import server.tools.Timer;

/**
 * Deferred zone-cross commit fired ~470ms after the client clicks a
 * portal-actor (handled by {@link
 * server.gameserver.packets.client_udp.UseItem}).
 *
 * <h3>Why deferred</h3>
 *
 * <p>Retail emits the cross packet train with a wire-pinned timing
 * gap that Ceres-J must mirror or the modern NCE 2.5 client never
 * transitions:
 *
 * <pre>
 *   T+0       TCP 0x83/0x8f   (interaction-commit)
 *   T+180ms   TCP 0x83/0x0d   (loading UI begin)
 *   T+650ms   TCP 0x83/0x0c   (Location: destination BSP)
 *             UDP 0x03/0x1f/0x38 ChangeLocation
 *             TCP 0xa0/0x02 ×2 InteractionAck
 * </pre>
 *
 * <p>Source: {@code RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502}
 * pcap (BEFORE_CROSS marker at 10:40:26.970). The same 470ms gap
 * appears in {@code RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} (zone
 * t=238.352 → t=238.847).
 *
 * <p>Ceres-J task #172, 2026-05-23: a Ceres-J pcap showed the
 * server emitting 0x83/0x0d then 0x83/0x0c back-to-back (~9ms
 * gap). The client never opened a new UDP socket (the cross
 * teardown+reconnect), timed out, and dropped to the login screen
 * — every dungeon entry attempt became a forced relog. With the
 * delay restored to retail's natural cadence the client has time
 * to enter its loading state before the Location packet hands it
 * the destination BSP.
 *
 * <h3>What this event does</h3>
 *
 * <p>Fires the rest of the cross-burst as a single atomic step at
 * the wire moment the client transitions:
 *
 * <ol>
 *   <li>Commit {@code MISC_LOCATION} to the destination zone</li>
 *   <li>{@link Player#updateZone()} to re-register on the destination
 *       Zone object</li>
 *   <li>Persist via {@link PlayerCharacterManager#saveCharacter} —
 *       (task #236 — survives the client teardown+reconnect)</li>
 *   <li>Send {@link Location} (TCP 0x83/0x0c) with destination BSP</li>
 *   <li>Send {@link ChangeLocation} (UDP 0x03/0x1f/0x38)</li>
 *   <li>Send {@link InteractionAck} pair (TCP 0xa0/0x02) — releases
 *       the client's interaction lock</li>
 * </ol>
 *
 * <p>If the player has disconnected during the 470ms window, the
 * event is a graceful no-op.
 *
 * @see server.gameserver.packets.client_udp.UseItem
 * @see server.gameserver.packets.client_udp.Zoning1.SZoning1ConfirmEvent
 *      — the analogous walking-cross flow with its own 450ms delay
 */
public final class PortalCrossCommitEvent extends DummyEvent {

	/**
	 * Delay in ms from the click (after {@code 0x83/0x0d} is sent)
	 * until this commit fires. Retail wire-pinned ~470ms gap between
	 * the two TCP packets; we use 470 to match the most-common value.
	 */
	public static final long DELAY_MS = 470;

	private final PortalResolver.Portal portal;

	public PortalCrossCommitEvent(PortalResolver.Portal portal) {
		this.portal = portal;
		this.eventTime = Timer.getRealtime() + DELAY_MS;
	}

	/** Test-only constructor that fires immediately (Timer-based
	 *  delay would slow tests by 470ms each). Package-private. */
	PortalCrossCommitEvent(PortalResolver.Portal portal,
	                        long firstTickAt) {
		this.portal = portal;
		this.eventTime = firstTickAt;
	}

	/** Accessor for tests — the resolved portal carried by this event. */
	public PortalResolver.Portal getPortal() {
		return portal;
	}

	@Override
	public void execute(Player pl) {
		if (pl == null || pl.getUdpConnection() == null) {
			// Player disconnected during the cross window — drop
			// the event silently. The DB write below is skipped on
			// purpose: a half-applied state where MISC_LOCATION is
			// committed but the cross never completes is worse than
			// just leaving the player in the source zone.
			return;
		}

		PlayerCharacter pc = pl.getCharacter();
		if (pc != null) {
			pc.setMisc(PlayerCharacter.MISC_LOCATION,
				portal.exitWorldId);
			pl.updateZone();
			// Persist the location change to the DB IMMEDIATELY —
			// task #236. Without this, the post-cross relog landed
			// the user back in the SOURCE zone because the DB had
			// the stale source location.
			PlayerCharacterManager.saveCharacter(pc);
		}

		// Task #239 — phase-1 observation: the deferred branch of
		// the cross has reached its commit moment. Record this on
		// the FSM. (Phase 2 will gate the rest of this method on
		// the client having acknowledged 0x83/0x0d's reliable; for
		// now we just observe the transition.)
		pl.getStateMachine().transition(
			ClientState.CROSS_PENDING_LOCATION,
			"PortalCrossCommit fired (T+" + DELAY_MS + "ms)");

		// TCP 0x83/0x0c Location — the worldserver address + BSP
		// name the client needs to load the destination world.
		// Pass portal.exitWorldEntity as spawnIdx (task #252):
		// appplaces row pointing at the destination's entry-point
		// coords. Retail emits 1 for reaktor dungeon, 16 for plaza
		// (verified 2026-05-24 byte-for-byte). Pre-#252 we always
		// emitted 0, which produced a wire mismatch.
		if (pl.getTcpConnection() != null) {
			pl.send(new Location(pl, portal.exitWorldEntity));
			// Task #253 — mark this BSP as loaded so future
			// cross-OUT or re-cross to it suppresses 0x83/0x0d
			// (retail-faithful: only first-load triggers
			// LoadingBegin). pl.updateZone() above has already
			// re-pointed currentZone to the destination, so the
			// Zone's worldname is the right fallback for resolvers
			// that haven't seeded WorldManager.
			server.gameserver.Zone destZone = pl.getZone();
			String fallback = (destZone == null) ? null
					: destZone.getWorldname();
			String destPath = server.gameserver.PortalResolver
					.worldIdToObjectPath(
						portal.exitWorldId, fallback);
			if (destPath != null) {
				pl.markBspLoaded(destPath);
			}
		} else {
			Out.writeln(Out.Warning,
				"PortalCrossCommit: " + (pc == null ? "?" : pc.getName())
				+ " has no TCP connection at commit time — "
				+ "0x83/0x0c Location dropped");
		}

		// UDP 0x03/0x1f/<localId>/0x38 ChangeLocation — the actual
		// "go to this zone now" trigger the client uses to tear
		// down its session + reconnect to the destination
		// worldserver. Must arrive AFTER the TCP pair.
		pl.send(new ChangeLocation(pl,
			portal.exitWorldId,
			portal.exitWorldEntity,
			portal.entityTypeByte));

		// Retail emits the transaction-ack PAIR (a0 02) after the
		// state-change packets. Without it the client's interaction
		// lock-out never releases.
		// Task #254 — retail 13B variant. RETRY3 pcap 2026-05-24
		// confirmed both InteractionAcks in the cross sequence are
		// 13B (fe 0a 00 a0 02 15 00 00 00 00 00 80 3f), not the
		// catalog-dominant 2B form.
		pl.send(new InteractionAck(true));
		pl.send(new InteractionAck(true));
	}
}
