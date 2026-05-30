package server.gameserver.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import server.tools.Timer;

/**
 * Per-{@code Player} state machine tracking the client's observable
 * progress through the login → in-world → cross-pending lifecycle.
 *
 * <h3>Why this exists</h3>
 *
 * <p>Ceres-J historically fires server-state-changing packets
 * fire-and-forget — emit {@code 0x83/0x0d} then {@code 0x83/0x0c}
 * back-to-back, commit the zone DB write, and assume the client
 * follows. Retail does NOT work this way: each S→C state advance is
 * gated on an observable C→S transition (a reliable ACK on
 * {@code 0x03/0x08}, an interaction acknowledgement, a TCP teardown
 * followed by a {@code ResumeAuth}, etc.). See the
 * {@code server-lacks-client-state-machine} memory for the
 * architectural analysis behind task #172.
 *
 * <p>Phase 1 (this class, landed under task #239) is purely
 * observational: instrument {@link #transition(ClientState, String)}
 * calls at the existing emit sites and {@link #observeReliableAck(int)}
 * calls at the existing ACK-reception sites, log the resulting
 * transitions, and validate them against retail pcaps. No behaviour
 * change.
 *
 * <p>Phase 2 will add {@link #onceAcked(int, Runnable)}-driven
 * gating to the {@link
 * server.gameserver.internalEvents.PortalCrossCommitEvent} and the
 * {@code Zoning1.SZoning1ConfirmEvent} so the zone commit only fires
 * when the client has acknowledged the preceding {@code 0x83/0x0c
 * Location}. That eliminates the empirical 470ms / 450ms timing
 * fudges in favour of correctness.
 *
 * <h3>Thread safety</h3>
 *
 * <p>All public methods are {@code synchronized}. The expected hot
 * paths (UDP decoder threads observing acks, the Player event-loop
 * driving transitions) are different threads, so contention is
 * unavoidable and the per-machine lock keeps the contract simple.
 * Callback invocation inside {@link #observeReliableAck(int)} is
 * done outside the lock to avoid re-entrancy with caller-held locks.
 */
public final class ClientStateMachine {

    /** Bounded transition log used for diagnostics + tests. The most
     *  recent {@code TRANSITION_LOG_CAPACITY} transitions are kept;
     *  older entries are evicted from the front of the deque. */
    public static final int TRANSITION_LOG_CAPACITY = 64;

    private ClientState state = ClientState.PRE_LOGIN;
    private long stateEnteredAt = Timer.getRealtime();
    /** Highest acked u16 seq the client has confirmed. {@code -1}
     *  means "no ack observed yet." Wraparound handled in
     *  {@link #seqGteUnsigned(int, int)}. */
    private int lastConfirmedReliableSeq = -1;
    /** Pending {@link #onceAcked(int, Runnable)} callbacks, keyed by
     *  the seq they're waiting on. Fires when an ack ≥ that seq
     *  arrives (or immediately if already past). */
    private final Map<Integer, List<Runnable>> pendingAckCallbacks =
            new HashMap<>();
    private final Deque<Transition> log = new ArrayDeque<>();

    /** Current state (last value passed to {@link #transition}). */
    public synchronized ClientState getState() {
        return state;
    }

    /** Milliseconds since the FSM last transitioned. */
    public synchronized long timeInStateMs() {
        return Timer.getRealtime() - stateEnteredAt;
    }

    /** Last reliable-ACK seq observed from this client, or
     *  {@code -1} if none. */
    public synchronized int lastConfirmedReliableSeq() {
        return lastConfirmedReliableSeq;
    }

    /**
     * Transition to {@code to} with a human-readable {@code reason}
     * (logged in the transition history). Idempotent: a transition
     * to the current state is a no-op (no log entry).
     */
    public synchronized void transition(ClientState to, String reason) {
        if (to == null) {
            throw new IllegalArgumentException("to == null");
        }
        if (to == state) return;
        log.addLast(new Transition(state, to, reason,
                Timer.getRealtime()));
        while (log.size() > TRANSITION_LOG_CAPACITY) {
            log.removeFirst();
        }
        state = to;
        stateEnteredAt = Timer.getRealtime();
    }

    /**
     * Record that the client has acknowledged reliable seq
     * {@code seq} (the LE16 reconstructed from a
     * {@code 0x03/0x08 [seq-1 LE2]} client packet, post-correction
     * — i.e. callers pass the actual acked seq, not the
     * wire-encoded {@code seq-1}).
     *
     * <p>Fires any pending {@link #onceAcked} callbacks whose
     * registered seq is ≤ the new ack point (u16 wrap-aware).
     */
    public void observeReliableAck(int seq) {
        List<Runnable> toFire = new ArrayList<>();
        synchronized (this) {
            // If this is the first ack OR the new seq is newer
            // (u16 monotonic), advance. Otherwise ignore — out-of-
            // order acks from a flaky packet path shouldn't roll
            // the confirmed pointer backwards.
            if (lastConfirmedReliableSeq < 0
                    || seqGteUnsigned(
                            seq & 0xFFFF,
                            lastConfirmedReliableSeq)) {
                lastConfirmedReliableSeq = seq & 0xFFFF;
            }
            Iterator<Map.Entry<Integer, List<Runnable>>> it =
                    pendingAckCallbacks.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, List<Runnable>> e = it.next();
                if (seqGteUnsigned(lastConfirmedReliableSeq,
                        e.getKey())) {
                    toFire.addAll(e.getValue());
                    it.remove();
                }
            }
        }
        // Fire callbacks OUTSIDE the lock so they can re-enter the
        // machine (e.g. trigger a follow-up transition) without
        // deadlock.
        for (Runnable r : toFire) {
            r.run();
        }
    }

    /**
     * Register {@code callback} to fire when the client has
     * acknowledged reliable seq {@code seq} (or any later seq). If
     * an ack ≥ {@code seq} has already been observed, the callback
     * fires synchronously on the calling thread.
     *
     * <p>The callback runs OUTSIDE the per-machine lock so it can
     * safely call back into the machine (e.g. {@link #transition})
     * without re-entering itself.
     */
    public void onceAcked(int seq, Runnable callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback == null");
        }
        boolean fireImmediately;
        synchronized (this) {
            fireImmediately = lastConfirmedReliableSeq >= 0
                    && seqGteUnsigned(lastConfirmedReliableSeq,
                            seq & 0xFFFF);
            if (!fireImmediately) {
                pendingAckCallbacks
                        .computeIfAbsent(seq & 0xFFFF,
                                k -> new ArrayList<>())
                        .add(callback);
            }
        }
        if (fireImmediately) {
            callback.run();
        }
    }

    /** Number of pending {@link #onceAcked} callbacks (for tests +
     *  diagnostics). */
    public synchronized int pendingCallbackCount() {
        int total = 0;
        for (List<Runnable> rs : pendingAckCallbacks.values()) {
            total += rs.size();
        }
        return total;
    }

    /** Snapshot of recent transitions, oldest-first. The returned
     *  list is a defensive copy. */
    public synchronized List<Transition> recentTransitions() {
        return new ArrayList<>(log);
    }

    /**
     * Returns {@code true} iff {@code a >= b} interpreted as an
     * unsigned 16-bit forward-only sequence number with standard
     * RFC 1982 wraparound semantics: {@code a} is "ahead of or
     * equal to" {@code b} if the unsigned distance from {@code b}
     * to {@code a} is in the first half of the seq space.
     *
     * <p>This means a fresh session jumping from {@code 0xfffe → 0x0003}
     * correctly treats {@code 0x0003 >= 0xfffe}.
     */
    static boolean seqGteUnsigned(int a, int b) {
        return ((a - b) & 0xFFFF) < 0x8000;
    }

    /** A single state transition record. Immutable. */
    public static final class Transition {
        public final ClientState from;
        public final ClientState to;
        public final String reason;
        public final long atRealtimeMs;

        Transition(ClientState from, ClientState to, String reason,
                   long atRealtimeMs) {
            this.from = from;
            this.to = to;
            this.reason = reason;
            this.atRealtimeMs = atRealtimeMs;
        }

        @Override
        public String toString() {
            return from + " → " + to
                    + " (" + reason + ") @" + atRealtimeMs + "ms";
        }
    }
}
