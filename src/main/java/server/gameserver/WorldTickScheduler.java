package server.gameserver;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import server.tools.Out;

/**
 * Single-threaded tick that drains the {@link WorldMessageBus} global
 * queue. Phase 0.3 wired the bus; Phase 2 (chat) is the first
 * subsystem that posts intents, so a real consumer must run.
 *
 * <p>Per-zone ticks will replace the global drain in Phase 3 (mob AI)
 * once each {@link Zone} owns its own logic-thread. Until then, all
 * intents post to zone -1 (global) and this scheduler drains it.
 *
 * <p>The drain runs every {@value #DRAIN_INTERVAL_MS} ms which is
 * tighter than the typical TCP RTT but loose enough to keep the
 * scheduler thread mostly idle. If a tick takes longer than the
 * interval, subsequent ticks back-pressure naturally — we use
 * {@code scheduleWithFixedDelay} rather than {@code AtFixedRate} so
 * we never queue overlapping drains.
 */
public final class WorldTickScheduler {

    /** 50 ms cadence — chat fan-out tolerates this latency easily. */
    public static final long DRAIN_INTERVAL_MS = 50;

    private final WorldMessageBus bus;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> drainHandle;

    public WorldTickScheduler(WorldMessageBus bus) {
        this.bus = bus;
    }

    /** Start the tick. Idempotent. */
    public synchronized void start() {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WorldTickScheduler");
            t.setDaemon(true);
            return t;
        });
        drainHandle = executor.scheduleWithFixedDelay(this::tick,
                DRAIN_INTERVAL_MS, DRAIN_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        Out.writeln(Out.Info,
                "WorldTickScheduler: started (" + DRAIN_INTERVAL_MS + " ms)");
    }

    /** Stop the tick. Safe to call repeatedly. */
    public synchronized void stop() {
        if (executor == null) return;
        if (drainHandle != null) {
            drainHandle.cancel(false);
            drainHandle = null;
        }
        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor = null;
        Out.writeln(Out.Info, "WorldTickScheduler: stopped");
    }

    /** One tick. Package-private for tests.
     *
     *  <p>Order of operations matters: AI runs first so that intents
     *  it posts ({@link server.gameserver.npc.MobStateChangeIntent})
     *  are drained on the SAME tick rather than waiting one extra
     *  cadence. */
    void tick() {
        try {
            server.gameserver.npc.MobAIScheduler.tickOnce(bus);
        } catch (Exception e) {
            Out.writeln(Out.Error,
                    "WorldTickScheduler: AI tick threw " + e.getMessage());
        }
        try {
            bus.drain(-1); // global
        } catch (Exception e) {
            Out.writeln(Out.Error,
                    "WorldTickScheduler: drain threw " + e.getMessage());
        }
    }
}
