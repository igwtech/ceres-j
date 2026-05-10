package server.gameserver.internalEvents;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.UDPAlive;
import server.tools.Out;
import server.tools.Timer;

/**
 * Periodic server-to-client UDPAlive ({@code 0x04}) heartbeat.
 *
 * <p>Retail emits 4 {@code UDPAlive} packets in the handshake-reply
 * burst (within ~0.1 ms at handshake completion) AND THEN continues
 * to emit additional UDPAlives at ~3 s spacing throughout the
 * session — 8 total per HANNIBAL session, 4 of which are the
 * handshake burst. Bytes are byte-identical to the
 * handshake-reply UDPAlive (per task #158 verification).
 *
 * <p>Trigger: unclear — may be pure keepalive OR response to
 * specific TCP traffic. Adopting a periodic-keepalive model since
 * the bytes are constant per session and retail's ~3 s cadence is
 * stable across captures.
 *
 * <p>Started from {@link WorldEntryEvent} once handshake is
 * complete; stops when the player logs out or the session tears
 * down. Same lifecycle pattern as
 * {@link TimeSyncHeartbeatEvent} / {@link PoolStatusHeartbeat}.
 */
public class UDPAliveHeartbeat extends DummyEvent {

    /**
     * Heartbeat interval in milliseconds. Retail's average is
     * ~3 s spacing (4 post-handshake UDPAlives over ~12 s in
     * HANNIBAL). 3000 ms matches order of magnitude.
     */
    public static final long INTERVAL_MS = 3000;

    public UDPAliveHeartbeat() {
        // First tick fires INTERVAL_MS after construction so it
        // doesn't collide with the handshake-reply burst (which
        // emits 4 UDPAlives via HandshakeUDPAnswer + Answer2).
        this.eventTime = Timer.getRealtime() + INTERVAL_MS;
    }

    public UDPAliveHeartbeat(long firstTickAt) {
        this.eventTime = firstTickAt;
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || !pl.isloggedin()
                || pl.getUdpConnection() == null) {
            // Player disconnected or session closed — let the
            // heartbeat die.
            return;
        }

        try {
            pl.send(new UDPAlive(pl));
        } catch (Exception e) {
            Out.writeln(Out.Error,
                    "UDPAliveHeartbeat: send failed: "
                    + e.getMessage());
            // Same defensive pattern as TimeSyncHeartbeatEvent —
            // a transient socket hiccup must not permanently stop
            // the heartbeat (which would silently desync the
            // client's UDP-keepalive expectation).
        }

        pl.addEvent(new UDPAliveHeartbeat(
                Timer.getRealtime() + INTERVAL_MS));
    }
}
