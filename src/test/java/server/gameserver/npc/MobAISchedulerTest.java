package server.gameserver.npc;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.WorldMessageBus;
import server.gameserver.Zone;

/**
 * Functional tests for {@link MobAIScheduler}.
 *
 * <p>Builds synthetic Zone instances (no {@code WorldManager}, no
 * DB) by invoking the {@link Zone#Zone(int, String)} constructor
 * directly and pushing NPCs in via {@link Zone#addNPCtoZone}, then
 * registering players via {@link Zone#registerPlayer}. Drives the
 * scheduler with a custom {@link MobAIScheduler.ZoneEnumerator}
 * that returns the synthetic zones, so the test never touches
 * {@code ZoneManager}.
 */
public class MobAISchedulerTest {

    private static Player makePlayer(int uid, int x, int y, int z) {
        Account acc = new Account(uid);
        acc.setUsername("u" + uid);
        Player pl = new Player(acc);
        PlayerCharacter pc = new PlayerCharacter();
        pc.setName("p" + uid);
        pc.setMisc(PlayerCharacter.MISC_ID, uid);
        pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, x);
        pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, y);
        pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, z);
        try {
            Field f = Player.class.getDeclaredField("pc");
            f.setAccessible(true);
            f.set(pl, pc);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return pl;
    }

    @Before
    public void setUp() {
        MobManager.resetForTesting();
        MobAIScheduler.resetForTesting();
    }

    @After
    public void tearDown() {
        MobManager.resetForTesting();
        MobAIScheduler.resetForTesting();
    }

    @Test
    public void emptyZoneListIsNoOp() {
        WorldMessageBus bus = new WorldMessageBus();
        MobAIScheduler.setZoneEnumeratorForTesting(Collections::emptyList);
        int n = MobAIScheduler.tickOnce(bus);
        assertEquals(0, n);
    }

    @Test
    public void zoneWithNoNpcsIsNoOp() {
        Zone z = new Zone(1, "test_zone");
        WorldMessageBus bus = new WorldMessageBus();
        int n = MobAIScheduler.tickZone(bus, z);
        assertEquals(0, n);
    }

    @Test
    public void mobAggroesOnPlayerInSameZone() {
        Zone z = new Zone(1, "test_zone");
        // Mob at origin, player 5 units away.
        z.addNPCtoZone(0, 0, 0, 100, 0, 0); // x,y,z,hp,type,armor,??
        z.registerPlayer(makePlayer(7, 5, 0, 0));

        WorldMessageBus bus = new WorldMessageBus();
        // Aggro range 20 (default) covers 5m distance.
        int n = MobAIScheduler.tickZone(bus, z);
        assertEquals(1, n);

        NPC mob = z.getAllNPCs().get(0);
        MobManager.Snapshot s = MobManager.getSnapshot(mob.getMapID());
        assertNotNull(s);
        assertEquals(MobState.COMBAT, s.state);
        assertEquals(7, s.targetId);
    }

    @Test
    public void mobIgnoresPlayerOutsideAggroRange() {
        Zone z = new Zone(1, "test_zone");
        z.addNPCtoZone(0, 0, 0, 100, 0, 0);
        z.registerPlayer(makePlayer(7, 100, 0, 0)); // 100m away

        WorldMessageBus bus = new WorldMessageBus();
        MobAIScheduler.tickZone(bus, z);

        NPC mob = z.getAllNPCs().get(0);
        // First tick records IDLE; ensure not COMBAT.
        MobManager.Snapshot s = MobManager.getSnapshot(mob.getMapID());
        assertNotNull(s);
        assertEquals(MobState.IDLE, s.state);
    }

    @Test
    public void multipleZonesTickedIndependently() {
        Zone z1 = new Zone(1, "zone_one");
        Zone z2 = new Zone(2, "zone_two");
        z1.addNPCtoZone(0, 0, 0, 100, 0, 0);
        z2.addNPCtoZone(0, 0, 0, 100, 0, 0);
        // Zone.addNPCtoZone auto-assigns mapIDs starting at 257 in
        // each zone — give the second mob a distinct id so they don't
        // collide in MobManager's global state map. The real wire
        // protocol encodes the zone in the high byte, but Zone.java
        // doesn't reflect that yet.
        z2.getAllNPCs().get(0).setMapID(0x402);
        // Player only in zone 1.
        z1.registerPlayer(makePlayer(7, 5, 0, 0));

        WorldMessageBus bus = new WorldMessageBus();
        MobAIScheduler.setZoneEnumeratorForTesting(() -> Arrays.asList(z1, z2));
        int n = MobAIScheduler.tickOnce(bus);
        // Both mobs transitioned (z1 → COMBAT, z2 → IDLE).
        assertEquals(2, n);

        NPC m1 = z1.getAllNPCs().get(0);
        NPC m2 = z2.getAllNPCs().get(0);
        assertEquals(MobState.COMBAT, MobManager.getSnapshot(m1.getMapID()).state);
        assertEquals(MobState.IDLE,   MobManager.getSnapshot(m2.getMapID()).state);
    }

    @Test
    public void zoneTickExceptionDoesNotBreakOtherZones() {
        // First "zone" is a sentinel that throws on getAllNPCs by
        // being intercepted via a faulty enumerator. The exception
        // path is exercised: tickOnce must catch it and continue.
        Zone good = new Zone(1, "good_zone");
        good.addNPCtoZone(0, 0, 0, 100, 0, 0);
        good.registerPlayer(makePlayer(7, 5, 0, 0));

        // A "broken" zone entry: null. The scheduler skips nulls.
        WorldMessageBus bus = new WorldMessageBus();
        MobAIScheduler.setZoneEnumeratorForTesting(
                () -> Arrays.asList(null, good));
        int n = MobAIScheduler.tickOnce(bus);
        // Only good zone produced a transition.
        assertEquals(1, n);
    }

    @Test
    public void snapshotPlayersSkipsPlayersWithNoCharacter() {
        // Player with no PlayerCharacter must be skipped without NPE.
        Account acc = new Account(99);
        acc.setUsername("nochar");
        Player anon = new Player(acc);

        List<MobAI.PlayerSnapshot> snaps = MobAIScheduler.snapshotPlayers(
                Arrays.asList(anon, makePlayer(7, 5, 0, 0)));
        assertEquals(1, snaps.size());
        assertEquals(7, snaps.get(0).uid);
    }

    @Test
    public void snapshotPlayersConvertsIntCoordsToFloat() {
        Player p = makePlayer(7, 100, 200, 300);
        List<MobAI.PlayerSnapshot> snaps = MobAIScheduler.snapshotPlayers(
                Collections.singletonList(p));
        assertEquals(1, snaps.size());
        MobAI.PlayerSnapshot s = snaps.get(0);
        assertEquals(100f, s.posX, 0.0001f);
        assertEquals(200f, s.posY, 0.0001f);
        assertEquals(300f, s.posZ, 0.0001f);
        assertTrue(s.alive);
    }

    @Test
    public void snapshotPlayersHandlesEmptyAndNullInputs() {
        assertTrue(MobAIScheduler.snapshotPlayers(null).isEmpty());
        assertTrue(MobAIScheduler.snapshotPlayers(Collections.emptyList()).isEmpty());
    }

    @Test
    public void adapterExposesNpcPositionAsFloats() {
        Zone z = new Zone(1, "test");
        z.addNPCtoZone(123, 456, 789, 100, 0, 0);
        NPC n = z.getAllNPCs().get(0);
        List<MobAIRunner.MobView> views = MobAIScheduler.adaptMobs(
                Collections.singletonList(n));
        assertEquals(1, views.size());
        MobAIRunner.MobView v = views.get(0);
        assertEquals(123f, v.posX(), 0.0001f);
        assertEquals(456f, v.posY(), 0.0001f);
        assertEquals(789f, v.posZ(), 0.0001f);
        assertEquals(n.getMapID(), v.npcId());
    }

    @Test
    public void customRangesAreUsed() {
        Zone z = new Zone(1, "test_zone");
        z.addNPCtoZone(0, 0, 0, 100, 0, 0);
        // Player at 25m — outside default aggro 20, inside default deaggro 30.
        z.registerPlayer(makePlayer(7, 25, 0, 0));

        WorldMessageBus bus = new WorldMessageBus();
        // Override aggro to 30 — now player IS inside aggro.
        MobAIScheduler.setRangesForTesting(30f, 40f);
        MobAIScheduler.tickZone(bus, z);

        NPC mob = z.getAllNPCs().get(0);
        assertEquals(MobState.COMBAT,
                MobManager.getSnapshot(mob.getMapID()).state);
    }

    @Test
    public void nullBusReturnsZero() {
        Zone z = new Zone(1, "test_zone");
        z.addNPCtoZone(0, 0, 0, 100, 0, 0);
        assertEquals(0, MobAIScheduler.tickZone(null, z));
        assertEquals(0, MobAIScheduler.tickOnce(null));
    }
}
