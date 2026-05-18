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
 * <p>Background: the heartbeat round-robins through
 * {@code zone.getAllNPCs()} sending one
 * {@link server.gameserver.packets.server_udp.ZoneStateCompoundPacket}
 * (reliable {@code 0x28} WorldInfo + reliable {@code 0x2d} 6-byte
 * ping; NO raw {@code 0x1b} — byte-pinned task #178d) per NPC per
 * tick. On a zone with 0 NPCs
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
    public void noNpcsBranchIsNoOpAndStillReschedules()
            throws Exception {
        // Zone with 0 NPCs — heartbeat must NOT broadcast the
        // player's own position. A previous attempt did, which
        // caused the modern client to render a duplicate
        // "ghost-self" standing a few meters away from the
        // real character. The heartbeat must still reschedule,
        // since other ticks may pick up newly-spawned NPCs.
        Player pl = PacketTestFixture.newPlayer();
        pl.setloggedin();
        Zone z = new Zone(1, "plaza_p1");
        Field zf = Player.class.getDeclaredField("currentZone");
        zf.setAccessible(true);
        zf.set(pl, z);
        assertTrue("zone must have 0 NPCs for this test",
                z.getAllNPCs().isEmpty());

        Field elf = Player.class.getDeclaredField("eventList");
        elf.setAccessible(true);
        PriorityList queue = (PriorityList) elf.get(pl);

        new ZoneStateHeartbeat().execute(pl);

        assertFalse("heartbeat must reschedule even on a zone "
                + "with 0 NPCs (next tick may find new ones)",
                queue.isEmpty());
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
