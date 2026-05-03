package server.gameserver;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import server.tools.Out;

/**
 * Single-writer-per-zone intent queue for cross-player mutations.
 *
 * <p>Concurrency model: {@code Player extends Thread}, so a chat
 * message from player A to player B touches two threads. To avoid
 * scattering locks, all cross-player intents (chat broadcast, trade
 * request, group invite, mob aggro, drone command, etc.) are
 * <strong>posted</strong> here by the originating player thread and
 * <strong>consumed</strong> on a single per-zone tick thread.
 *
 * <p>This is the single mandatory choke-point for cross-player
 * mutation. Anything that bypasses it is a bug.
 *
 * <p>Phase 0 wires the bus only — no producers/consumers yet. Each
 * subsequent phase (2 chat, 4 group, 5 trade, 7 mission) adds intent
 * types and their handlers.
 *
 * <p>An intent is just a marker interface; concrete intents live next
 * to the manager that produces them (e.g. {@code ChatBroadcastIntent}
 * lives in {@code server.gameserver.chat}).
 */
public final class WorldMessageBus {

    /** Marker interface for any cross-player mutation request. */
    public interface Intent {
        /** Zone the intent applies to. -1 = global (chat broadcast,
         *  buddy lookup, server-wide event). */
        default int zoneId() { return -1; }
    }

    /** Per-zone queues + a global queue (zone -1). The map itself
     *  must be concurrent because producers from many threads may
     *  call {@code computeIfAbsent} for a fresh zone simultaneously. */
    private final Map<Integer, Queue<Intent>> queues = new ConcurrentHashMap<>();
    private final Queue<Intent> globalQueue = new ConcurrentLinkedQueue<>();

    /** Per-intent-class handler. Handlers run on the bus's drain
     *  thread (typically the per-zone tick), never on the producer's
     *  thread. Concurrent so registrations can happen at startup
     *  from multiple threads safely. */
    private final Map<Class<? extends Intent>, Consumer<Intent>> handlers = new ConcurrentHashMap<>();

    /** Register a handler for an intent class. Last registration wins
     *  (allows in-place upgrade during dev). */
    @SuppressWarnings("unchecked")
    public <T extends Intent> void registerHandler(Class<T> cls, Consumer<T> h) {
        handlers.put(cls, (Consumer<Intent>) h);
    }

    /** Post an intent. Producer side. Thread-safe. */
    public void post(Intent intent) {
        if (intent == null) return;
        int zone = intent.zoneId();
        Queue<Intent> q = (zone < 0)
                ? globalQueue
                : queues.computeIfAbsent(zone,
                        k -> new ConcurrentLinkedQueue<>());
        q.add(intent);
    }

    /** Drain the queue for one zone (or the global queue when
     *  {@code zoneId == -1}) and dispatch each intent to its
     *  registered handler. Consumer side. NOT thread-safe — call
     *  from a single tick thread per zone. */
    public int drain(int zoneId) {
        Queue<Intent> q = (zoneId < 0) ? globalQueue : queues.get(zoneId);
        if (q == null || q.isEmpty()) return 0;
        int n = 0;
        Intent intent;
        while ((intent = q.poll()) != null) {
            Consumer<Intent> h = handlers.get(intent.getClass());
            if (h == null) {
                Out.writeln(Out.Warning,
                        "WorldMessageBus: no handler for "
                        + intent.getClass().getSimpleName());
                continue;
            }
            try {
                h.accept(intent);
            } catch (Exception e) {
                Out.writeln(Out.Error, "WorldMessageBus: handler for "
                        + intent.getClass().getSimpleName()
                        + " threw: " + e.getMessage());
            }
            n++;
        }
        return n;
    }

    /** Number of pending intents in a zone (or global). */
    public int pending(int zoneId) {
        Queue<Intent> q = (zoneId < 0) ? globalQueue : queues.get(zoneId);
        return q == null ? 0 : q.size();
    }

    /** For tests / admin: clear all queues + handlers. */
    public synchronized void clearAllForTesting() {
        queues.clear();
        globalQueue.clear();
        handlers.clear();
    }
}
