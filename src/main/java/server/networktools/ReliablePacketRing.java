package server.networktools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded ring buffer of recently-emitted reliable {@code 0x03}
 * sub-packets, keyed by their LE16 sequence counter. Used by the
 * {@link server.gameserver.packets.client_udp.ReliableAckSubPacket}
 * handler to resend a specific seq on demand when the client
 * sends a {@code C→S 0x01 [seq LE2]} retransmit request.
 *
 * <p>Without this ring, the server has nothing to retransmit and
 * the client's "Synchronizing into city zone" overlay never
 * clears — the sync state-machine is waiting for ~17 specific
 * reliable seqs to arrive within a ~1s window post-handshake.
 *
 * <h3>Design notes</h3>
 *
 * <ul>
 *   <li><b>Size cap</b>: default 8192 entries. Retail captures
 *       show the client may request retransmit of seqs from up
 *       to ~50 entries back during normal play, but during
 *       cross-session reconnect (zone-handoff) the client
 *       requests retransmit of seqs spanning the entire prior
 *       session (the Player object is reused and the counter
 *       persists). With the previous cap of 128, live testing
 *       on 2026-05-14 surfaced `seq N not in ring (evicted
 *       or never emitted)` for seqs 1-40 immediately after a
 *       zone-cross-driven reconnect — the ring had wrapped past
 *       those seqs during normal play. 8192 gives ~80 KB / player
 *       at typical 10 B reliables and covers a full play session
 *       without eviction until the LE16 counter wraps at 65536.
 *       Memory cost: ~80 KB × N concurrent players.</li>
 *   <li><b>FIFO eviction</b>: when the cap is exceeded, the
 *       oldest entry is dropped. The seq counter is monotonic
 *       within a session, so eviction order matches retail's
 *       expectation that very-old retransmit requests fail.</li>
 *   <li><b>Per-session</b>: lives on {@link
 *       server.gameserver.GameServerUDPConnection}. Each player's
 *       ring is independent.</li>
 *   <li><b>Thread-safe stores</b>: {@code put} and {@code get} are
 *       both {@code synchronized}. Reliable emit happens on the
 *       game thread; ack-request handling happens on the UDP
 *       receiver thread.</li>
 * </ul>
 *
 * <p>Seq wraparound at 0xFFFF is theoretically possible (client
 * counter is LE16) but retail captures don't show it within a
 * single session; the ring handles it via plain {@code Integer}
 * keys without explicit wraparound logic.
 */
public final class ReliablePacketRing {

    /** Default capacity — see class javadoc. */
    public static final int DEFAULT_CAPACITY = 8192;

    private final int capacity;
    /** {@code LinkedHashMap} preserves insertion order so the
     *  oldest entry is always {@code firstEntry()}. */
    private final Map<Integer, byte[]> ring;

    public ReliablePacketRing() {
        this(DEFAULT_CAPACITY);
    }

    public ReliablePacketRing(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                    "capacity must be > 0, got " + capacity);
        }
        this.capacity = capacity;
        this.ring = new LinkedHashMap<>();
    }

    /**
     * Record a reliable sub-packet keyed by its LE16 sequence
     * counter. Defensive-copies {@code payload} so callers can
     * reuse their buffer.
     *
     * <p>If {@code seq} is already in the ring, its payload is
     * overwritten and its position in the eviction order is
     * refreshed (see {@link LinkedHashMap}). Practically this
     * shouldn't happen in normal operation since seqs are
     * monotonic, but defensive against double-emit bugs.
     */
    public synchronized void record(int seq, byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException(
                    "payload must not be null");
        }
        // Re-insert: remove first to refresh position in iteration
        // order (LinkedHashMap semantics).
        ring.remove(seq);
        ring.put(seq & 0xFFFF, payload.clone());
        evictIfOverCapacity();
    }

    /**
     * Look up the payload for {@code seq}. Returns a defensive
     * copy or {@code null} if not in the ring (evicted, never
     * stored, or seq out of session range).
     */
    public synchronized byte[] get(int seq) {
        byte[] stored = ring.get(seq & 0xFFFF);
        return stored == null ? null : stored.clone();
    }

    /** True iff {@code seq} is currently retained in the ring. */
    public synchronized boolean contains(int seq) {
        return ring.containsKey(seq & 0xFFFF);
    }

    /** Number of entries currently retained — never exceeds
     *  {@link #capacity()}. */
    public synchronized int size() {
        return ring.size();
    }

    /** Configured maximum size (FIFO eviction beyond this). */
    public int capacity() {
        return capacity;
    }

    /** Clear all entries — for session shutdown or a forced
     *  resync. */
    public synchronized void clear() {
        ring.clear();
    }

    private void evictIfOverCapacity() {
        // LinkedHashMap iteration starts at the OLDEST entry —
        // remove it until we're at the cap.
        while (ring.size() > capacity) {
            Integer oldest = ring.keySet().iterator().next();
            ring.remove(oldest);
        }
    }
}
