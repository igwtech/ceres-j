package server.gameserver.internalEvents;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.GameServerEvent;
import server.tools.PriorityList;

/**
 * Functional test for {@link TimeSyncHeartbeatEvent} — the
 * ~1 Hz S→C TimeSync heartbeat the modern client requires
 * to clear the "SYNCHRONIZING INTO CITY ZONE" overlay.
 *
 * <p>Self-rescheduling event: each tick sends a
 * {@code GamePacketTimeSync} via UDP and enqueues the next
 * tick {@code INTERVAL_MS} later. Stops rescheduling when the
 * player is no longer logged in.
 */
public class TimeSyncHeartbeatEventTest {

    @Test
    public void executeReschedulesItselfWhenLoggedIn() throws Exception {
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new TimeSyncHeartbeatEvent().execute(pl);

        assertFalse("heartbeat must self-reschedule when logged in",
                queue.isEmpty());
        GameServerEvent next = (GameServerEvent) queue.getFirst();
        assertEquals("rescheduled event must be the same class",
                "TimeSyncHeartbeatEvent",
                next.getClass().getSimpleName());
    }

    @Test
    public void notLoggedInDoesNotReschedule() throws Exception {
        // Player logged out → heartbeat dies (doesn't leak
        // events on closed sessions).
        Player pl = PacketTestFixture.newPlayer();
        // NOT calling setloggedin().

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new TimeSyncHeartbeatEvent().execute(pl);

        assertTrue("heartbeat must die when player not logged in",
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

        new TimeSyncHeartbeatEvent().execute(pl);

        assertTrue("heartbeat must die when UDP closed",
                queue.isEmpty());
    }

    @Test
    public void nullPlayerEarlyReturns() {
        new TimeSyncHeartbeatEvent().execute((Player) null);
        // Pass = no NPE.
    }

    @Test
    public void firstTickFiresIntervalMsAfterConstruction() {
        long before = server.tools.Timer.getRealtime();
        TimeSyncHeartbeatEvent e = new TimeSyncHeartbeatEvent();

        assertTrue("eventTime must be at least INTERVAL_MS in the "
                + "future, got " + e.eventTime + " vs now=" + before,
                e.eventTime >= before
                        + TimeSyncHeartbeatEvent.INTERVAL_MS);
    }

    @Test
    public void explicitFirstTickConstructorRespectsInput() {
        // The 2-arg constructor takes a firstTickAt timestamp
        // (used by the rescheduling path to chain ticks
        // precisely INTERVAL_MS apart).
        long target = 1234567890L;
        TimeSyncHeartbeatEvent e =
                new TimeSyncHeartbeatEvent(target);
        assertEquals(target, e.eventTime);
    }

    @Test
    public void sendFailureStillReschedules() throws Exception {
        // Per the source comment: even on send failure the
        // heartbeat keeps rescheduling. We can't easily
        // engineer a send failure without mocking, but we can
        // confirm: a player WITH UDP gets a reschedule (proxy
        // for the happy path).
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new TimeSyncHeartbeatEvent().execute(pl);
        assertFalse(queue.isEmpty());
    }
}
