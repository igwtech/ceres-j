package server.gameserver.internalEvents;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.TimeSync;
import server.tools.Out;
import server.tools.Timer;

/**
 * Periodic server-to-client TimeSync push ({@code 0x03/0x0d}).
 *
 * <p>Distinct from {@link TimeSyncHeartbeatEvent} (which emits the
 * fast-cadence ~1 Hz GamePackets state-ack {@code 0x03/0x1f/01/00/25/23}
 * — required for the SYNCHRONIZING overlay). This event emits the
 * <strong>actual clock-advance packet</strong> the client uses to
 * update its in-game HUD time/compass widget.
 *
 * <h3>Retail cadence</h3>
 *
 * <p>Pcap analysis 2026-05-23 of
 * {@code RETAIL_LONG_PARTY_A_20260503_130137} (an 11-minute capture):
 * the retail server pushes 22 unsolicited TimeSync packets at an
 * average inter-arrival of ~32 s (min 0, max 139s — clustered bursts
 * after long quiet intervals). We use a fixed 30 s cadence — within
 * the natural retail spread and avoids burst-clustering.
 *
 * <h3>Why this is needed</h3>
 *
 * <p>Without periodic push: the modern NCE 2.5 client does NOT
 * request TimeSync periodically (60 min of session logs show zero
 * C→S 0x0c GetTimeSync or 0x03/0x0d requests). The HUD clock stays
 * frozen at whatever value was emitted at world-entry. Task #231:
 * "in-game clock stuck at 00:xx".
 *
 * <p>Started from {@link WorldEntryEvent} once world-entry burst is
 * flushed; stops when the player is no longer marked logged in.
 *
 * @see TimeSync server_time semantics (monotonic ms since server boot)
 * @see TimeSyncHeartbeatEvent state-ack heartbeat (separate channel)
 */
public class TimeSyncPushEvent extends DummyEvent {

	/**
	 * Push interval in milliseconds. Retail average is ~32 s; 30 s
	 * stays inside the natural spread.
	 */
	public static final long INTERVAL_MS = 30_000;

	public TimeSyncPushEvent() {
		// First tick after a full INTERVAL — gives the WorldEntry
		// burst (which includes a one-shot TimeSync at +0) room to
		// drain before we start the periodic push.
		this.eventTime = Timer.getRealtime() + INTERVAL_MS;
	}

	public TimeSyncPushEvent(long firstTickAt) {
		this.eventTime = firstTickAt;
	}

	@Override
	public void execute(Player pl) {
		if (pl == null || !pl.isloggedin()
				|| pl.getUdpConnection() == null) {
			// Player disconnected — let the schedule die.
			return;
		}

		try {
			// clienttime=0: this is an UNSOLICITED push, so there's
			// no client-side timestamp to echo. Retail observed
			// echoing the last-received client_time on pushes; we
			// don't track that yet, so 0 is the safe default.
			pl.send(new TimeSync(pl, 0));
		} catch (Exception e) {
			Out.writeln(Out.Error,
				"TimeSyncPush: send failed: " + e.getMessage());
			// Keep rescheduling — a transient socket hiccup
			// shouldn't permanently freeze the client clock.
		}

		// Self-reschedule. Cheaper + clearer than reusing `this`.
		pl.addEvent(new TimeSyncPushEvent(
			Timer.getRealtime() + INTERVAL_MS));
	}
}
