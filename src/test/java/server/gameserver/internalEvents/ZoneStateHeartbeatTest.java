package server.gameserver.internalEvents;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.tools.PriorityList;

/**
 * Functional test for {@link ZoneStateHeartbeat} — pins the
 * "no-NPCs fallback" that fixes the 45-second
 * "Connection to worldserver failed" disconnect on zones that
 * have no NPCs registered.
 *
 * <p>Background: the modern client's WORLDCLIENT watchdog
 * needs periodic raw {@code 0x1b} position broadcasts (~7.7
 * Hz observed in retail). The original heartbeat round-robined
 * through {@code zone.getAllNPCs()}; on a zone with 0 NPCs
 * (the current state of every zone since {@code NpcSpawnManager:
 * loaded 0 NPCs} for all 17), the heartbeat emitted nothing
 * and the watchdog timed out at 45s.
 *
 * <p>The fix broadcasts the player's own position with a
 * phantom object id (mapId | 0x80) when no NPCs are present.
 */
public class ZoneStateHeartbeatTest {

    @Test
    public void heartbeatReschedulesItselfRegardlessOfNpcs()
            throws Exception {
        // Even with no zone, the heartbeat must reschedule (the
        // session might be mid-zoning). Verify by checking the
        // event queue grows.
        // Wait — the current implementation early-returns when
        // pl.isloggedin() is false OR pl.getUdpConnection() is
        // null OR zone is null. The fixture player has UDP but
        // is not logged in by default. Set isloggedin first.
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();

        Zone z = new Zone(7, "test_zone");
        Field zf = Player.class.getDeclaredField("currentZone");
        zf.setAccessible(true);
        zf.set(pl, z);

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new ZoneStateHeartbeat().execute(pl);

        // After execute, a follow-up ZoneStateHeartbeat event
        // should be on the queue.
        assertFalse("heartbeat must reschedule itself",
                queue.isEmpty());
    }

    @Test
    public void noNpcsFallbackDoesNotThrow() throws Exception {
        // Zone with 0 NPCs — heartbeat must take the phantom-
        // broadcast path without throwing. We can't easily
        // observe UDP datagrams from this test fixture, but
        // the absence of an exception confirms the fallback
        // wires through ObjectPositionBroadcast cleanly.
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();
        Zone z = new Zone(1, "plaza_p1");
        Field zf = Player.class.getDeclaredField("currentZone");
        zf.setAccessible(true);
        zf.set(pl, z);

        assertTrue("zone must have 0 NPCs for this test",
                z.getAllNPCs().isEmpty());

        // Pass = no exception escaped through the fallback
        // ObjectPositionBroadcast construction.
        new ZoneStateHeartbeat().execute(pl);
    }

    @Test
    public void notLoggedInEarlyReturns() throws Exception {
        // The early-return must NOT reschedule (otherwise a
        // logged-out session leaks events forever).
        Player pl = PacketTestFixture.newPlayer();
        // Explicitly NOT logged in.
        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new ZoneStateHeartbeat().execute(pl);

        assertTrue("not-logged-in heartbeat must NOT reschedule "
                + "(prevents leaks on logged-out sessions)",
                queue.isEmpty());
    }

    @Test
    public void noUdpConnectionEarlyReturns() throws Exception {
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();
        pl.closeUDP();
        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new ZoneStateHeartbeat().execute(pl);

        assertTrue("no-UDP heartbeat must NOT reschedule",
                queue.isEmpty());
    }

    @Test
    public void nullZoneEarlyReturns() throws Exception {
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();
        // Fixture has no zone by default → null currentZone.
        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new ZoneStateHeartbeat().execute(pl);

        assertTrue("null-zone heartbeat must NOT reschedule",
                queue.isEmpty());
    }
}
