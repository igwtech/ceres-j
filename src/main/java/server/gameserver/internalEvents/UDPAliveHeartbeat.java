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

    /**
     * Max post-handshake UDPAlives to emit, then STOP rescheduling.
     *
     * <p>The modern client treats every {@code 0x04} UDPAlive as
     * "new UDP session → reset reliable-receive window to seq 1"
     * (decoded 2026-05-16, memory {@code zone_cross_2phase_handshake}
     * UPDATE #5). A perpetual 3 s heartbeat therefore makes the
     * client re-request its <em>entire</em> reliable backlog from
     * seq 1 every 3 s; the server replays it (no rate limit) while
     * heartbeats keep growing the backlog → an unbounded retransmit
     * storm (pcap 2026-05-16: 45 k C→S retransmit-requests / 263
     * pkt·s⁻¹ → client FPS collapse, "can't move", slow logoff).
     *
     * <p>Retail sends ZERO periodic S→C UDPAlive during steady-state
     * play (verified: 0 in the 205 s PLAZA_TO_PEPPER and 414 s
     * PLAZA_CROSSZONE captures) — only the handshake-reply burst
     * plus a handful early. Bounding the heartbeat to a few ticks
     * then stopping matches retail and kills the storm. A
     * cross-reconnect starts a fresh WorldEntryEvent → a fresh
     * bounded heartbeat, so the early-session keepalive the client
     * expects after each (re)connect is preserved.
     */
    public static final int MAX_TICKS = 4;

    private final int tick;

    public UDPAliveHeartbeat() {
        // First tick fires INTERVAL_MS after construction so it
        // doesn't collide with the handshake-reply burst (which
        // emits 4 UDPAlives via HandshakeUDPAnswer + Answer2).
        this.eventTime = Timer.getRealtime() + INTERVAL_MS;
        this.tick = 1;
    }

    public UDPAliveHeartbeat(long firstTickAt) {
        this.eventTime = firstTickAt;
        this.tick = 1;
    }

    private UDPAliveHeartbeat(long firstTickAt, int tick) {
        this.eventTime = firstTickAt;
        this.tick = tick;
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || !pl.isloggedin()
                || pl.getUdpConnection() == null) {
            // Player disconnected or session closed — let the
            // heartbeat die.
            return;
        }

        // NOTE: do NOT suppress UDPAlive during a pending
        // zone-cross. The localhost-vs-retail byte-diff
        // (2026-05-16) shows retail emits a UDPAlive ~0.65 s after
        // Zoning1 (clustered with the post-Zoning2 completion); the
        // client expects it. An earlier commit suppressed it on the
        // wrong theory and that made the cross worse. The real
        // mid-cross divergence is the NPC zone-state flood
        // (ZoneStateHeartbeat), which retail silences during a
        // cross — handled there, not here. UDPAlive keeps firing.
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

        // Bounded: emit only the first MAX_TICKS post-handshake
        // UDPAlives (retail sends ~0 periodic ones in steady state),
        // then let the heartbeat die. A perpetual heartbeat makes
        // the client reset its reliable-receive window every tick →
        // unbounded retransmit storm. See MAX_TICKS javadoc.
        if (tick < MAX_TICKS) {
            pl.addEvent(new UDPAliveHeartbeat(
                    Timer.getRealtime() + INTERVAL_MS, tick + 1));
        }
    }
}
