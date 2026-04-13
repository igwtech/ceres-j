package server.gameserver.internalEvents;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.ObjectPositionBroadcast;
import server.tools.Out;
import server.tools.Timer;

/**
 * Periodic unreliable {@code 0x1b} object-position broadcast.
 *
 * <p>Retail servers emit 122 of these per session at an average rate of
 * 7.7 Hz (ACC1_CHAR1; 75-333 across the other 3 captures). The modern
 * NCE 2.5 client's world-alive watchdog expires after 10-25 seconds
 * without them, forcing the "SYNCHRONIZING INTO CITY ZONE" overlay
 * to re-appear and the session to time out.
 *
 * <p>Uses the unreliable {@code 0x13} wrapper (no {@code 0x03} reliable
 * sub-type) so the broadcast is treated as advisory — it feeds the
 * world-state watchdog but does NOT override the client's local
 * movement prediction (which an authoritative
 * {@code 0x03→0x1b PlayerPositionUpdate} echo would, causing rubber-
 * banding — verified in a prior attempt).
 */
public class ObjectBroadcastHeartbeat extends DummyEvent {

    /**
     * Interval in milliseconds. Retail averages 7.7 Hz (130 ms) but
     * ranges 2-10 Hz depending on zone activity. 150 ms gives us
     * ~6.6 Hz which sits comfortably in that range without burying the
     * socket in writes.
     */
    public static final long INTERVAL_MS = 150;

    public ObjectBroadcastHeartbeat() {
        this.eventTime = Timer.getRealtime() + INTERVAL_MS;
    }

    public ObjectBroadcastHeartbeat(long firstTickAt) {
        this.eventTime = firstTickAt;
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || !pl.isloggedin() || pl.getUdpConnection() == null
                || pl.getCharacter() == null) {
            return;
        }

        try {
            pl.send(new ObjectPositionBroadcast(pl));
        } catch (Exception e) {
            Out.writeln(Out.Error,
                "ObjectBroadcastHeartbeat: send failed: " + e.getMessage());
        }

        // Self-reschedule.
        pl.addEvent(new ObjectBroadcastHeartbeat(Timer.getRealtime() + INTERVAL_MS));
    }
}
