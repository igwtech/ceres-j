package server.gameserver;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Functional tests for {@link WorldTickScheduler}.
 *
 * <p>The scheduler exists to drain the {@link WorldMessageBus} on a
 * single tick thread; tests verify that posted intents are picked up
 * within a reasonable time and that calling stop() cleanly halts the
 * tick.
 */
public class WorldTickSchedulerTest {

    static final class TickIntent implements WorldMessageBus.Intent {
        final String tag;
        TickIntent(String tag) { this.tag = tag; }
        // zoneId() defaults to -1 (global)
    }

    @Test
    public void singleTickDrainsGlobalQueue() {
        // tick() is package-private — call directly without spinning
        // the scheduler thread.
        WorldMessageBus bus = new WorldMessageBus();
        AtomicInteger count = new AtomicInteger();
        bus.registerHandler(TickIntent.class, i -> count.incrementAndGet());
        WorldTickScheduler tick = new WorldTickScheduler(bus);

        bus.post(new TickIntent("a"));
        bus.post(new TickIntent("b"));
        tick.tick();

        assertEquals(2, count.get());
        assertEquals(0, bus.pending(-1));
    }

    @Test
    public void scheduledTickDrainsWithinDeadline() throws Exception {
        WorldMessageBus bus = new WorldMessageBus();
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        bus.registerHandler(TickIntent.class,
                i -> seen.add(((TickIntent) i).tag));

        WorldTickScheduler tick = new WorldTickScheduler(bus);
        tick.start();
        try {
            bus.post(new TickIntent("x"));
            // Drain interval is 50 ms; allow generous slack.
            long deadline = System.currentTimeMillis() + 2000;
            while (seen.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, seen.size());
            assertEquals("x", seen.get(0));
        } finally {
            tick.stop();
        }
    }

    @Test
    public void stopIsIdempotent() {
        WorldMessageBus bus = new WorldMessageBus();
        WorldTickScheduler tick = new WorldTickScheduler(bus);
        tick.stop(); // never started — must not throw
        tick.start();
        tick.start(); // double-start must be a no-op
        tick.stop();
        tick.stop();
    }

    @Test
    public void stopHaltsTickPromptly() throws Exception {
        WorldMessageBus bus = new WorldMessageBus();
        AtomicInteger count = new AtomicInteger();
        bus.registerHandler(TickIntent.class, i -> count.incrementAndGet());

        WorldTickScheduler tick = new WorldTickScheduler(bus);
        tick.start();
        tick.stop();

        // After stop, posting + waiting should NOT consume the intent.
        bus.post(new TickIntent("ignored"));
        Thread.sleep(150);
        TimeUnit.MILLISECONDS.sleep(50);
        assertEquals(0, count.get());
        assertEquals(1, bus.pending(-1));
    }
}
