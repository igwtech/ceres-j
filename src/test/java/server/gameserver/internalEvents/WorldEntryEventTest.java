package server.gameserver.internalEvents;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.tools.PriorityList;
import server.tools.Timer;

/**
 * Smoke test for {@link WorldEntryEvent}.
 *
 * The event delegates to {@link Player#send} which, in production, writes to
 * a real {@code DatagramSocket}. In the fixture that socket is not bound, so
 * {@code send} will silently swallow {@link java.io.IOException}. We just
 * verify that the event can be executed end-to-end without throwing, and that
 * it reports a sensible {@code eventTime}.
 */
public class WorldEntryEventTest {

    @Test
    public void eventIsScheduledAfterCreation() {
        // Timer.getRealtime() is a static field updated by the Timer background
        // thread. In unit tests the thread isn't running, so getRealtime()
        // may return 0. We just verify that the schedule-offset is applied
        // on top of whatever base time is in effect.
        long base = Timer.getRealtime();
        WorldEntryEvent evt = new WorldEntryEvent();
        assertTrue("event scheduled before base time",
                evt.getEventTime() >= base + WorldEntryEvent.START_DELAY_MS);
        assertTrue("event scheduled too far in future",
                evt.getEventTime() <= base + WorldEntryEvent.START_DELAY_MS + 5000);
    }

    @Test
    public void executeDoesNotThrowOnValidPlayer() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        // Zone is null in the fixture, so the zone-broadcast sub-steps are
        // skipped. Still, this exercises all the packet builders.
        WorldEntryEvent evt = new WorldEntryEvent(0);
        assertNotNull(evt);
        try {
            evt.execute(pl);
        } catch (Throwable t) {
            throw new AssertionError("WorldEntryEvent.execute threw: " + t, t);
        }
    }

    @Test
    public void executeStartsAllThreeHeartbeatsOnFirstLogin()
            throws Exception {
        // Regression test for the SYNCHRONIZING-overlay hang fixed
        // 2026-05-09. WorldEntryEvent must schedule the three S→C
        // heartbeats (TimeSync, PoolStatus, ZoneState) immediately
        // after the burst, NOT defer them to zone-handoff. Sessions
        // without a zone-handoff (the user's reported case) would
        // otherwise never see TimeSync streaming and the client's
        // state-machine would never advance past SYNCHRONIZING.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        new WorldEntryEvent(0).execute(pl);

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        // PriorityList isn't Iterable — drain it via removeFirst()
        // and capture each event's class name.
        Set<String> scheduled = new HashSet<>();
        while (!queue.isEmpty()) {
            scheduled.add(
                queue.removeFirst().getClass().getSimpleName());
        }

        assertTrue("TimeSyncHeartbeatEvent must be scheduled, "
                + "got: " + scheduled,
                scheduled.contains("TimeSyncHeartbeatEvent"));
        assertTrue("PoolStatusHeartbeat must be scheduled, "
                + "got: " + scheduled,
                scheduled.contains("PoolStatusHeartbeat"));
        assertTrue("ZoneStateHeartbeat must be scheduled, "
                + "got: " + scheduled,
                scheduled.contains("ZoneStateHeartbeat"));
        assertTrue("TcpKeepaliveEvent must remain scheduled, "
                + "got: " + scheduled,
                scheduled.contains("TcpKeepaliveEvent"));
    }

    @Test
    public void executeToleratesMissingCharacter() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        // Blank out the character so execute() must take the early-return path.
        try {
            java.lang.reflect.Field f = Player.class.getDeclaredField("pc");
            f.setAccessible(true);
            f.set(pl, null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }

        WorldEntryEvent evt = new WorldEntryEvent(0);
        evt.execute(pl); // must not throw
    }
}
