package server.gameserver.internalEvents;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.GameServerEvent;
import server.tools.PriorityList;

/**
 * Functional test for {@link PoolStatusHeartbeat} — the
 * ~0.9 Hz raw {@code 0x1f} pool-status broadcast that tells
 * the modern client the current HP/PSI/STA values are valid.
 *
 * <p>Self-rescheduling event: each tick sends a
 * {@code PoolStatusBroadcast} via UDP and enqueues the next
 * tick {@code INTERVAL_MS} later. Stops rescheduling when the
 * player is no longer logged in / has no UDP / has no
 * character.
 */
public class PoolStatusHeartbeatTest {

    @Test
    public void executeReschedulesItselfWhenLoggedIn() throws Exception {
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new PoolStatusHeartbeat().execute(pl);

        assertFalse("heartbeat must self-reschedule when logged in",
                queue.isEmpty());
        GameServerEvent next = (GameServerEvent) queue.getFirst();
        assertEquals("rescheduled event must be the same class",
                "PoolStatusHeartbeat",
                next.getClass().getSimpleName());
    }

    @Test
    public void notLoggedInDoesNotReschedule() throws Exception {
        Player pl = PacketTestFixture.newPlayer();
        // Explicitly NOT logged in.

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new PoolStatusHeartbeat().execute(pl);

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

        new PoolStatusHeartbeat().execute(pl);

        assertTrue("heartbeat must die when UDP closed",
                queue.isEmpty());
    }

    @Test
    public void nullCharacterDoesNotReschedule() throws Exception {
        // Defensive: character was deleted between construction
        // and execute. The handler must short-circuit (without
        // rescheduling) so a deleted-character session can't
        // leak heartbeats.
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();
        Field f = Player.class.getDeclaredField("pc");
        f.setAccessible(true);
        f.set(pl, null);

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new PoolStatusHeartbeat().execute(pl);

        assertTrue("heartbeat must die when character is null",
                queue.isEmpty());
    }

    @Test
    public void nullPlayerEarlyReturns() {
        new PoolStatusHeartbeat().execute((Player) null);
        // Pass = no NPE.
    }

    @Test
    public void firstTickFiresIntervalMsAfterConstruction() {
        long before = server.tools.Timer.getRealtime();
        PoolStatusHeartbeat e = new PoolStatusHeartbeat();

        assertTrue("eventTime must be at least INTERVAL_MS in the "
                + "future, got " + e.eventTime + " vs now=" + before,
                e.eventTime >= before + PoolStatusHeartbeat.INTERVAL_MS);
    }

    @Test
    public void intervalIsRetailDerivedNotZero() {
        // Catches a regression that flips the constant to 0
        // (would flood the client) or to a wildly off value.
        // Retail observed: ~1.1 s. Allow [500, 5000] as a
        // sanity envelope.
        assertTrue("INTERVAL_MS must be in the retail envelope",
                PoolStatusHeartbeat.INTERVAL_MS >= 500
                        && PoolStatusHeartbeat.INTERVAL_MS <= 5000);
    }
}
