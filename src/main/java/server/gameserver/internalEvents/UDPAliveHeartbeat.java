package server.gameserver.internalEvents;

import server.gameserver.GameServerUDPConnection;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.UDPAlive;
import server.tools.Out;
import server.tools.Timer;

/**
 * Server-to-client UDPAlive ({@code 0x04}) retry while the client has
 * not yet answered the handshake.
 *
 * <p><b>Retail rule:</b> the server re-sends the session's UDPAlive at
 * ~3.2 s spacing only until the client answers it with a transport
 * {@code 0x03} ({@link server.gameserver.packets.client_udp.SyncUDP}).
 * <ul>
 *   <li>RETAIL_HANNIBAL pcap: the client did not answer the handshake
 *       burst (t=0.25 s) until t=6.56 s, and the server re-sent the
 *       identical {@code 0x04} at t=3.42 s and t=6.67 s, then stopped.
 *       This is where the old "4 periodic UDPAlives at ~3 s" reading
 *       came from.</li>
 *   <li>All 25 retail pcaps (251 S→C {@code 0x04}): a same-key
 *       {@code 0x04} only ever appears as a retry 3.0–3.6 s after an
 *       unanswered one, or racing the client's answer by ≤0.12 s —
 *       never later. All 39 retail harness traces that captured the
 *       handshake agree (client answers within ~1 ms, no further
 *       same-key {@code 0x04}).</li>
 *   <li>Every later {@code 0x04} (e.g. LONG_PARTY_A t=306 s,
 *       CREATION_LEVELING t=277/444/2469 s, fire_multikill_v1
 *       t=30.6 s) carries a NEW session key — a deliberate new UDP
 *       session — and the client restarts its reliable sequence at 1
 *       straight after it.</li>
 * </ul>
 *
 * <p><b>Why the gate matters:</b> the client treats every {@code 0x04}
 * as "new UDP session → reset the reliable state". The previous
 * unconditional 4-tick heartbeat made the 64-bit client reset four
 * times after every login (harness runs wgate64_v3, fire_ceres_*):
 * each time it restarted its own reliable seq at 1 and re-requested the
 * server's whole reliable backlog (wgate64_v3: 428 C→S {@code 0x01}
 * and 428 S→C {@code 0x02} retransmits in 43 s; retail runs show
 * 0–14 per minute).
 *
 * <p>Started from {@link WorldEntryEvent}; dies when the client has
 * answered, the player logs out, or {@link #MAX_TICKS} retries ran.
 */
public class UDPAliveHeartbeat extends DummyEvent {

    /**
     * Retry interval in milliseconds. RETAIL_HANNIBAL re-sent the
     * unanswered UDPAlive after 3.17 s and again 3.25 s later.
     */
    public static final long INTERVAL_MS = 3000;

    /**
     * Max unanswered retries before giving up. Retail's own limit is
     * not in the corpus (HANNIBAL needed two); this only bounds a
     * client that never answers.
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
        GameServerUDPConnection con = pl.getUdpConnection();
        if (con.isAliveAcked()) {
            // The client already answered this session's UDPAlive.
            // Another 0x04 would reset its reliable state.
            return;
        }

        try {
            pl.send(new UDPAlive(pl));
        } catch (Exception e) {
            Out.writeln(Out.Error,
                    "UDPAliveHeartbeat: send failed: "
                    + e.getMessage());
            // A transient socket hiccup must not stop the retry —
            // keep rescheduling below.
        }

        if (tick < MAX_TICKS) {
            pl.addEvent(new UDPAliveHeartbeat(
                    Timer.getRealtime() + INTERVAL_MS, tick + 1));
        }
    }
}
