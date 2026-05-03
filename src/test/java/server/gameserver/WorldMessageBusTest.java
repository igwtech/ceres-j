package server.gameserver;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class WorldMessageBusTest {

    static class TestIntent implements WorldMessageBus.Intent {
        final int zone;
        final String tag;
        TestIntent(int zone, String tag) { this.zone = zone; this.tag = tag; }
        @Override public int zoneId() { return zone; }
    }

    @Test
    public void postAndDrainSameZone() {
        WorldMessageBus bus = new WorldMessageBus();
        List<String> seen = new ArrayList<>();
        bus.registerHandler(TestIntent.class, (i) -> seen.add(i.tag));

        bus.post(new TestIntent(1, "a"));
        bus.post(new TestIntent(1, "b"));
        bus.post(new TestIntent(2, "c"));

        assertEquals(2, bus.pending(1));
        assertEquals(1, bus.pending(2));

        // Drain zone 1 only.
        assertEquals(2, bus.drain(1));
        assertEquals(2, seen.size());
        assertEquals("a", seen.get(0));
        assertEquals("b", seen.get(1));
        assertEquals(0, bus.pending(1));
        assertEquals(1, bus.pending(2));
    }

    @Test
    public void globalIntentRoutesToGlobalQueue() {
        WorldMessageBus bus = new WorldMessageBus();
        List<String> seen = new ArrayList<>();
        bus.registerHandler(TestIntent.class, (i) -> seen.add(i.tag));

        bus.post(new TestIntent(-1, "global"));
        assertEquals(1, bus.pending(-1));
        bus.drain(-1);
        assertEquals("global", seen.get(0));
    }

    @Test
    public void unhandledIntentDoesNotThrow() {
        WorldMessageBus bus = new WorldMessageBus();
        bus.post(new TestIntent(0, "no-handler"));
        // Should log a warning but not throw.
        bus.drain(0);
    }

    @Test
    public void concurrentProducersSerializeOnSingleConsumer() throws Exception {
        WorldMessageBus bus = new WorldMessageBus();
        List<String> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        bus.registerHandler(TestIntent.class, (i) -> seen.add(i.tag));

        int producers = 10;
        int perProducer = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);
        for (int i = 0; i < producers; i++) {
            int idx = i;
            new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) {}
                for (int j = 0; j < perProducer; j++) {
                    bus.post(new TestIntent(0, "p" + idx + "-" + j));
                }
                done.countDown();
            }).start();
        }
        start.countDown();
        done.await(2, TimeUnit.SECONDS);

        assertEquals(producers * perProducer, bus.pending(0));
        bus.drain(0);
        assertEquals(producers * perProducer, seen.size());
    }
}
