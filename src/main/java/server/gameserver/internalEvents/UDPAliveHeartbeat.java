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

        // Suppress the periodic UDPAlive while a zone-cross is in
        // flight (Zoning1 seen, Zoning2 not yet). Retail emits
        // UDPAlive during a cross ONLY as the post-Zoning2
        // handshake-completion beacon, immediately followed by the
        // server's reliable-counter reset. A stray periodic
        // UDPAlive arriving mid-cross makes the client treat it as
        // that completion beacon: it resets its reliable-receive
        // expectation to seq 1 while the server (no Zoning2 yet)
        // keeps streaming high seqs, so the client believes it
        // missed the whole burst, floods 0x01 retransmit-requests,
        // and never advances to Zoning2 — the plaza_p1 → plaza_p3
        // dead-lock (localhost-vs-retail byte-diff 2026-05-16:
        // stray 0x04 at +0.704 s → client requests seq 1 at
        // +1.066 s). Keep rescheduling so the keepalive resumes
        // once Zoning2 commits the cross and clears pendingZoneId.
        if (pl.getPendingZoneId() == 0) {
            try {
                pl.send(new UDPAlive(pl));
            } catch (Exception e) {
                Out.writeln(Out.Error,
                        "UDPAliveHeartbeat: send failed: "
                        + e.getMessage());
                // Same defensive pattern as TimeSyncHeartbeatEvent
                // — a transient socket hiccup must not permanently
                // stop the heartbeat (which would silently desync
                // the client's UDP-keepalive expectation).
            }
        }

        pl.addEvent(new UDPAliveHeartbeat(
                Timer.getRealtime() + INTERVAL_MS));
    }
}
