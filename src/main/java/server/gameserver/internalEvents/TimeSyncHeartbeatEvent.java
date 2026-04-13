package server.gameserver.internalEvents;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.GamePacketTimeSync;
import server.tools.Out;
import server.tools.Timer;

/**
 * Periodic server-to-client TimeSync heartbeat.
 *
 * <p>Reschedules itself every {@link #INTERVAL_MS} ms and emits a
 * {@link GamePacketTimeSync} on each tick. Retail sends 37-65 of these per
 * session (~1 Hz) and the modern client appears to refuse to clear the
 * "SYNCHRONIZING INTO CITY ZONE" overlay until it sees them streaming.
 *
 * <p>Started from {@link WorldEntryEvent} once the initial world-state burst
 * has been flushed; stops when the player is no longer marked as logged in
 * (logout or session teardown).
 */
public class TimeSyncHeartbeatEvent extends DummyEvent {

    /**
     * Heartbeat interval in milliseconds. Retail's average is ~1 s (one
     * heartbeat per second across a 40-60 s session). Using a slightly
     * faster cadence (~750 ms) gives a bit of head-room against timer
     * jitter while still matching the order of magnitude retail uses.
     */
    public static final long INTERVAL_MS = 750;

    public TimeSyncHeartbeatEvent() {
        // Fire the first tick a short delay after construction so the
        // initial WorldEntry burst has time to drain onto the wire first.
        this.eventTime = Timer.getRealtime() + INTERVAL_MS;
    }

    public TimeSyncHeartbeatEvent(long firstTickAt) {
        this.eventTime = firstTickAt;
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || !pl.isloggedin() || pl.getUdpConnection() == null) {
            // Player disconnected or session closed — let the heartbeat die.
            return;
        }

        try {
            pl.send(new GamePacketTimeSync(pl));
        } catch (Exception e) {
            Out.writeln(Out.Error, "TimeSyncHeartbeat: send failed: " + e.getMessage());
            // Intentional: even on send failure we keep rescheduling so a
            // transient socket hiccup doesn't permanently stop the
            // heartbeat (which would silently desync the client).
        }

        // Self-reschedule. A new event instance is cheaper and clearer
        // than trying to reuse `this` via the event queue's ordering.
        pl.addEvent(new TimeSyncHeartbeatEvent(Timer.getRealtime() + INTERVAL_MS));
    }
}
