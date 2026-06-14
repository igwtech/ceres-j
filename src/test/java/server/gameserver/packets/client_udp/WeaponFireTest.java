package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.WorldMessageBus;
import server.gameserver.Zone;
import server.gameserver.npc.MobDeathHandler;
import server.gameserver.npc.MobManager;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Unit + functional tests for {@link WeaponFire} — the C→S
 * {@code 0x03/0x1f/<localId>/0x01} weapon-fire request.
 *
 * <p>Wire byte pin (retail, 2026-06-13 Norman→Reggie kill):
 * {@code 03 [seq2] 1f [localId LE2] 01 [weapon LE2][target LE2]
 * [8B shot][shot_coord f32]}. The handler is invoked with the full
 * sub-packet (starting at the leading {@code 03}); {@code parseWeaponId}
 * reads at offset 7, {@code parseTargetId} at offset 9.
 * See memory {@code combat_loop_retail_kill_pinned}.
 */
public class WeaponFireTest {

    /** Build the 23-byte wire sub-packet for a fire request. */
    private static byte[] buildFire(int seq, int weapon, int target) {
        byte[] b = new byte[23];
        b[0]  = 0x03;                          // reliable outer
        b[1]  = (byte) (seq & 0xff);           // seq lo
        b[2]  = (byte) ((seq >> 8) & 0xff);    // seq hi
        b[3]  = 0x1f;                          // GamePackets sub-op
        b[4]  = 0x01;                          // localId lo
        b[5]  = 0x00;                          // localId hi
        b[6]  = 0x01;                          // act-tag = FIRE
        b[7]  = (byte) (weapon & 0xff);        // weapon lo
        b[8]  = (byte) ((weapon >> 8) & 0xff); // weapon hi
        b[9]  = (byte) (target & 0xff);        // target lo
        b[10] = (byte) ((target >> 8) & 0xff); // target hi
        // [11..22] 8B shot data + f32 shot_coord — server ignores.
        return b;
    }

    /** Install a zone onto a fixture player without booting ZoneManager
     *  (mirrors PacketTestFixture's reflective PlayerCharacter install). */
    private static void setZone(Player pl, Zone z) {
        try {
            java.lang.reflect.Field f =
                    Player.class.getDeclaredField("currentZone");
            f.setAccessible(true);
            f.set(pl, z);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @After
    public void tearDown() {
        MobManager.resetForTesting();
        MobDeathHandler.resetForTesting();
        server.gameserver.GameServer.setBusForTesting(null);
    }

    // ─── Decode ────────────────────────────────────────────────────

    @Test
    public void parseWeaponIdLittleEndian() {
        WeaponFire p = new WeaponFire(buildFire(0x0042, 0x0122, 0x012c));
        assertEquals(0x0122, p.parseWeaponId());   // Lazar Gun def id
    }

    @Test
    public void parseTargetIdLittleEndian() {
        WeaponFire p = new WeaponFire(buildFire(0x0042, 0x0122, 0x012c));
        assertEquals(0x012c, p.parseTargetId());   // Reggie entity
    }

    @Test
    public void parseIsRepeatableAndSeqInsensitive() {
        WeaponFire a = new WeaponFire(buildFire(0x0001, 0x0122, 0x012c));
        WeaponFire b = new WeaponFire(buildFire(0xffff, 0x0122, 0x012c));
        assertEquals(a.parseWeaponId(), b.parseWeaponId());
        assertEquals(a.parseTargetId(), b.parseTargetId());
        // parse resets the underlying stream → repeatable.
        assertEquals(0x012c, a.parseTargetId());
        assertEquals(0x012c, a.parseTargetId());
    }

    // ─── Execute ───────────────────────────────────────────────────

    @Test
    public void executeWithNoZoneIsSafe() {
        // Fixture player has no currentZone — must no-op, not throw.
        Player pl = PacketTestFixture.newPlayer();
        new WeaponFire(buildFire(0x0042, 0x0122, 0x012c)).execute(pl);
    }

    @Test
    public void fireAppliesServerDamageToTargetMob() {
        Zone z = new Zone(7, "test_zone");
        z.addNPCtoZone(0, 0, 0, 170, 0, 0);   // Aggressor-ish maxHP
        NPC mob = z.getAllNPCs().get(0);
        int target = mob.getMapID();

        Player pl = PacketTestFixture.newPlayer();
        setZone(pl, z);

        WorldMessageBus bus = new WorldMessageBus();
        server.gameserver.GameServer.setBusForTesting(bus);
        MobManager.resetForTesting();
        MobDeathHandler.resetForTesting();
        MobManager.setNpcLookupForTesting(id -> id == target ? mob : null);
        MobManager.installBusHandlers(bus);
        MobDeathHandler.installBusHandlers(bus);

        new WeaponFire(buildFire(0x0042, 0x0122, target)).execute(pl);
        bus.drain(-1);

        assertEquals("one shot deducts PER_SHOT_DAMAGE",
                170 - WeaponFire.PER_SHOT_DAMAGE, mob.getHP());
        assertFalse(mob.isDead());
    }

    @Test
    public void fireAtTerrainIdDealsNoDamage() {
        // target 0x03fe is a world hit-point (the floor) — not an NPC
        // in the zone, so it resolves to null and nothing is damaged.
        Zone z = new Zone(7, "test_zone");
        z.addNPCtoZone(0, 0, 0, 100, 0, 0);
        NPC mob = z.getAllNPCs().get(0);

        Player pl = PacketTestFixture.newPlayer();
        setZone(pl, z);
        WorldMessageBus bus = new WorldMessageBus();
        server.gameserver.GameServer.setBusForTesting(bus);
        MobManager.setNpcLookupForTesting(id -> mob);

        new WeaponFire(buildFire(0x0042, 0x0122, 0x03fe)).execute(pl);
        bus.drain(-1);

        assertEquals("terrain shot is harmless", 100, mob.getHP());
    }

    @Test
    public void fireAtDeadMobIsNoOp() {
        Zone z = new Zone(7, "test_zone");
        z.addNPCtoZone(0, 0, 0, 100, 0, 0);
        NPC mob = z.getAllNPCs().get(0);
        int target = mob.getMapID();
        mob.takeDamage(100);                  // already dead
        assertTrue(mob.isDead());

        Player pl = PacketTestFixture.newPlayer();
        setZone(pl, z);
        WorldMessageBus bus = new WorldMessageBus();
        server.gameserver.GameServer.setBusForTesting(bus);
        MobManager.setNpcLookupForTesting(id -> mob);
        MobManager.installBusHandlers(bus);

        new WeaponFire(buildFire(0x0042, 0x0122, target)).execute(pl);
        bus.drain(-1);

        // Handler bailed on isDead() before posting any damage intent.
        assertEquals(0, mob.getHP());
        assertTrue(mob.isDead());
    }
}
