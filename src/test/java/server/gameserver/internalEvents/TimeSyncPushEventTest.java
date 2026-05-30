package server.gameserver.internalEvents;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.GameServerEvent;
import server.tools.PriorityList;

/**
 * Functional test for {@link TimeSyncPushEvent} — the periodic
 * ~30s S→C TimeSync ({@code 0x03/0x0d}) push that advances the
 * in-game HUD clock for the modern NCE 2.5 client (which never
 * polls TimeSync — task #231).
 *
 * <p>Self-rescheduling event: each tick sends a {@code TimeSync}
 * via UDP and enqueues the next tick {@code INTERVAL_MS} later.
 * Stops rescheduling when the player is no longer logged in or
 * the UDP connection has been torn down.
 */
public class TimeSyncPushEventTest {

    @Test
    public void executeReschedulesItselfWhenLoggedIn() throws Exception {
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new TimeSyncPushEvent().execute(pl);

        assertFalse("push must self-reschedule when logged in",
                queue.isEmpty());
        GameServerEvent next = (GameServerEvent) queue.getFirst();
        assertEquals("rescheduled event must be the same class",
                "TimeSyncPushEvent",
                next.getClass().getSimpleName());
    }

    @Test
    public void notLoggedInDoesNotReschedule() throws Exception {
        // Player logged out → push dies (doesn't leak events).
        Player pl = PacketTestFixture.newPlayer();
        // NOT calling setloggedin().

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new TimeSyncPushEvent().execute(pl);

        assertTrue("push must die when player not logged in",
                queue.isEmpty());
    }

    @Test
    public void noUdpConnectionDoesNotReschedule() throws Exception {
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();
        pl.closeUDP();

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new TimeSyncPushEvent().execute(pl);

        assertTrue("push must die when UDP closed",
                queue.isEmpty());
    }

    @Test
    public void nullPlayerEarlyReturns() {
        new TimeSyncPushEvent().execute((Player) null);
        // Pass = no NPE.
    }

    @Test
    public void firstTickFiresIntervalMsAfterConstruction() {
        long before = server.tools.Timer.getRealtime();
        TimeSyncPushEvent e = new TimeSyncPushEvent();

        assertTrue("eventTime must be at least INTERVAL_MS in the "
                + "future, got " + e.eventTime + " vs now=" + before,
                e.eventTime >= before + TimeSyncPushEvent.INTERVAL_MS);
    }

    @Test
    public void explicitFirstTickConstructorRespectsInput() {
        long target = 1234567890L;
        TimeSyncPushEvent e = new TimeSyncPushEvent(target);
        assertEquals("explicit firstTickAt must be honored",
                target, e.eventTime);
    }

    @Test
    public void intervalIsApproximatelyRetailCadence() {
        // Retail capture average ~32s; we pin to 30s.
        // Belt-and-suspenders against accidental refactor (e.g.
        // someone copying TimeSyncHeartbeatEvent's 750ms cadence).
        assertEquals("interval must be 30s (retail-faithful)",
                30_000L, TimeSyncPushEvent.INTERVAL_MS);
    }
}
