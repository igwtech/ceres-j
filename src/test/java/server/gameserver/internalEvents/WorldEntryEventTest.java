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

    /**
     * Task #174 functional test. A city↔city walk-cross commits a
     * destination worldId &lt; 2001 and (via SZoning1Confirm) sets the
     * city self-position suppression flag. The world-entry burst must
     * then send NO self {@code PlayerPositionUpdate} (0x03/0x1b) — this
     * is exactly what retail does: in
     * RETAIL_PLAZA_TO_PEPPER_CROSS_DISTRICT the verbose self-position
     * (0x1b 01000000 03 [XYZ]) appears only in the initial plaza_p1
     * login session, and ZERO times in the plaza_p3 or pepper_p1 cross
     * sessions. Pushing the stale source-zone self-position is the
     * "spawn reset to map centre" bug. After the burst the single-shot
     * flag must be consumed (cleared) so a later non-cross re-entry
     * still gets its authoritative self-position.
     */
    @Test
    public void cityCrossBurstSuppressesSelfPositionAndClearsFlag() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        server.testtools.CapturingUDPConnection udp =
                server.testtools.CapturingUDPConnection.replaceOn(pl);

        // Simulate the SZoning1Confirm commit for a city destination
        // (plaza_p3 = 101, retail-proven city sector).
        assertTrue(server.gameserver.ZoneBoundaries
                .isIndexedCitySector(101));
        pl.setPendingCityCrossSelfPosSuppress(true);

        new WorldEntryEvent(0).execute(pl);

        long selfPos = udp.received().stream()
                .filter(p -> p instanceof
                    server.gameserver.packets.server_udp
                        .PlayerPositionUpdate)
                .count();
        assertTrue("city walk-cross burst must send NO self"
                + " PlayerPositionUpdate (retail sends none); got "
                + selfPos, selfPos == 0);

        assertTrue("single-shot suppression flag must be consumed"
                + " (cleared) after the burst",
                !pl.isPendingCityCrossSelfPosSuppress());
    }

    /**
     * Counterpart to the city case: a normal (non-city-cross) world
     * entry — fresh login or wasteland/outdoor cross, flag never set —
     * MUST still send the authoritative self {@code PlayerPositionUpdate}.
     * This guards against the city fix regressing the known-good
     * always-send behaviour for everything else.
     */
    @Test
    public void normalEntryStillSendsSelfPosition() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        server.testtools.CapturingUDPConnection udp =
                server.testtools.CapturingUDPConnection.replaceOn(pl);

        // Flag deliberately NOT set (fresh login / outdoor cross).
        assertTrue(!pl.isPendingCityCrossSelfPosSuppress());

        new WorldEntryEvent(0).execute(pl);

        long selfPos = udp.received().stream()
                .filter(p -> p instanceof
                    server.gameserver.packets.server_udp
                        .PlayerPositionUpdate)
                .count();
        assertTrue("normal world entry must send the self"
                + " PlayerPositionUpdate (known-good fallback); got "
                + selfPos, selfPos >= 1);
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
