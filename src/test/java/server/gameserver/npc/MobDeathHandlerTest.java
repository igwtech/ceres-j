package server.gameserver.npc;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.WorldMessageBus;
import server.gameserver.Zone;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Functional tests for {@link MobDeathHandler}: the consumer that
 * cleans up dead mob bodies from {@link Zone}s.
 */
public class MobDeathHandlerTest {

    static final class CapturedKill {
        final MobDeathIntent intent;
        final NPC removed;
        CapturedKill(MobDeathIntent i, NPC n) {
            this.intent = i; this.removed = n;
        }
    }

    static final class CapturedDespawn {
        final Player recipient;
        final int entityId;
        CapturedDespawn(Player r, int id) { recipient = r; entityId = id; }
    }

    private List<CapturedKill> killed;
    private List<CapturedDespawn> despawns;
    private Map<Integer, Zone> zoneMap;

    @Before
    public void setUp() {
        MobManager.resetForTesting();
        MobDeathHandler.resetForTesting();
        killed = new ArrayList<>();
        despawns = new ArrayList<>();
        zoneMap = new HashMap<>();
        MobDeathHandler.setZoneLookupForTesting(
                id -> zoneMap.get(id));
        MobDeathHandler.setKillLoggerForTesting(
                (i, n) -> killed.add(new CapturedKill(i, n)));
        MobDeathHandler.setDespawnSinkForTesting(
                (r, id) -> despawns.add(new CapturedDespawn(r, id)));
    }

    @After
    public void tearDown() {
        MobManager.resetForTesting();
        MobDeathHandler.resetForTesting();
        server.gameserver.GameServer.setBusForTesting(null);
    }

    private Zone newZone(int id) {
        Zone z = new Zone(id, "test_zone_" + id);
        zoneMap.put(id, z);
        return z;
    }

    // ─── Direct dispatch ───────────────────────────────────────────

    @Test
    public void deathRemovesNpcFromZone() {
        Zone z = newZone(7);
        z.addNPCtoZone(0, 0, 0, 100, 0, 0);
        NPC mob = z.getAllNPCs().get(0);
        int npcId = mob.getMapID();

        MobDeathHandler.dispatch(new MobDeathIntent(npcId, 7, 0x999));

        assertTrue("npc gone from zone",
                z.getAllNPCs().isEmpty());
        assertEquals(1, killed.size());
        assertSame(mob, killed.get(0).removed);
    }

    @Test
    public void unknownZoneDoesNotThrow() {
        // Zone lookup returns null for an unknown zoneId — handler
        // logs + drops without an NPE.
        MobDeathHandler.dispatch(new MobDeathIntent(0x42, 999, 0x1));
        assertTrue(killed.isEmpty());
    }

    @Test
    public void unknownNpcDoesNotThrow() {
        // Zone exists but the npcId isn't there — handler logs + drops.
        newZone(7);
        MobDeathHandler.dispatch(new MobDeathIntent(0x999, 7, 0x1));
        assertTrue(killed.isEmpty());
    }

    @Test
    public void nullIntentIsSafe() {
        MobDeathHandler.dispatch(null);
        assertTrue(killed.isEmpty());
    }

    @Test
    public void killLoggerExceptionDoesNotPropagate() {
        Zone z = newZone(7);
        z.addNPCtoZone(0, 0, 0, 100, 0, 0);
        NPC mob = z.getAllNPCs().get(0);

        MobDeathHandler.setKillLoggerForTesting((i, n) -> {
            throw new RuntimeException("boom");
        });

        // Must not throw. NPC removal already happened before the
        // logger ran.
        MobDeathHandler.dispatch(new MobDeathIntent(mob.getMapID(), 7, 0));
        assertTrue("npc still removed despite logger exception",
                z.getAllNPCs().isEmpty());
    }

    // ─── End-to-end via MobManager.dispatchDamage ──────────────────

    @Test
    public void lethalDamagePostsDeathIntentAndRemovesNpc() {
        Zone z = newZone(7);
        z.addNPCtoZone(0, 0, 0, 10, 0, 0); // low HP
        NPC mob = z.getAllNPCs().get(0);
        int npcId = mob.getMapID();

        WorldMessageBus bus = new WorldMessageBus();
        // MobManager.dispatchDamage looks up GameServer.getBus() to
        // post the secondary state-change + death intents. Wire it
        // up so the death intent reaches MobDeathHandler on the
        // same bus.
        server.gameserver.GameServer.setBusForTesting(bus);
        MobManager.setNpcLookupForTesting(id ->
                id == npcId ? mob : null);
        MobManager.installBusHandlers(bus);
        MobDeathHandler.installBusHandlers(bus);

        bus.post(new MobDamageIntent(npcId, 7, 0xCAFE, 100));
        bus.drain(-1);

        assertTrue(mob.isDead());
        // NPC removed from zone via the death handler.
        assertTrue(z.getAllNPCs().isEmpty());
        assertEquals(1, killed.size());
        assertEquals(npcId, killed.get(0).intent.npcId);
        assertEquals(0xCAFE, killed.get(0).intent.killerUid);
        assertEquals(7, killed.get(0).intent.zoneIdField);
    }

    @Test
    public void survivableDamageDoesNotPostDeathIntent() {
        Zone z = newZone(7);
        z.addNPCtoZone(0, 0, 0, 100, 0, 0); // full HP
        NPC mob = z.getAllNPCs().get(0);

        WorldMessageBus bus = new WorldMessageBus();
        server.gameserver.GameServer.setBusForTesting(bus);
        MobManager.setNpcLookupForTesting(id -> mob);
        MobManager.installBusHandlers(bus);
        MobDeathHandler.installBusHandlers(bus);

        bus.post(new MobDamageIntent(mob.getMapID(), 7, 0xCAFE, 25));
        bus.drain(-1);

        assertFalse(mob.isDead());
        // NPC still in zone; no kill logged.
        assertEquals(1, z.getAllNPCs().size());
        assertTrue(killed.isEmpty());
    }

    // ─── Intent fields ─────────────────────────────────────────────

    @Test
    public void deathIntentFieldsAreImmutable() {
        MobDeathIntent i = new MobDeathIntent(0x42, 7, 0x100);
        assertEquals(0x42, i.npcId);
        assertEquals(7, i.zoneIdField);
        assertEquals(0x100, i.killerUid);
        // Bus scope is global until per-zone ticks.
        assertEquals(-1, i.zoneId());
    }

    // ─── 0x03/0x26 RemoveWorldItem fan-out ─────────────────────────

    @Test
    public void deathFansOutDespawnToEveryZonePlayer() {
        // Three players in zone — every one of them must get a
        // RemoveWorldItem broadcast for the dead NPC.
        Zone z = newZone(7);
        z.addNPCtoZone(0, 0, 0, 100, 0, 0);
        NPC mob = z.getAllNPCs().get(0);
        int npcId = mob.getMapID();

        Player p1 = PacketTestFixture.newPlayer();
        Player p2 = PacketTestFixture.newPlayer();
        Player p3 = PacketTestFixture.newPlayer();
        z.registerPlayer(p1);
        z.registerPlayer(p2);
        z.registerPlayer(p3);

        MobDeathHandler.dispatch(new MobDeathIntent(npcId, 7, 0x999));

        assertEquals("expected 3 despawn fan-outs (one per player)",
                3, despawns.size());
        for (CapturedDespawn d : despawns) {
            assertEquals("entity_id must match dead NPC's map id",
                    npcId, d.entityId);
        }
        // All three recipients distinct.
        assertEquals(3, despawns.stream().map(d -> d.recipient)
                .distinct().count());
    }

    @Test
    public void emptyZoneSendsNoDespawn() {
        Zone z = newZone(7);
        z.addNPCtoZone(0, 0, 0, 100, 0, 0);
        NPC mob = z.getAllNPCs().get(0);

        MobDeathHandler.dispatch(new MobDeathIntent(mob.getMapID(), 7, 0));

        assertTrue("no players → no despawn", despawns.isEmpty());
        // NPC still removed.
        assertTrue(z.getAllNPCs().isEmpty());
        assertEquals(1, killed.size());
    }

    @Test
    public void despawnSinkExceptionDoesNotInterruptOtherRecipients() {
        Zone z = newZone(7);
        z.addNPCtoZone(0, 0, 0, 100, 0, 0);
        NPC mob = z.getAllNPCs().get(0);
        int npcId = mob.getMapID();

        Player good1 = PacketTestFixture.newPlayer();
        Player bad   = PacketTestFixture.newPlayer();
        Player good2 = PacketTestFixture.newPlayer();
        z.registerPlayer(good1);
        z.registerPlayer(bad);
        z.registerPlayer(good2);

        MobDeathHandler.setDespawnSinkForTesting((r, id) -> {
            if (r == bad) throw new RuntimeException("simulated");
            despawns.add(new CapturedDespawn(r, id));
        });

        MobDeathHandler.dispatch(new MobDeathIntent(npcId, 7, 0));

        // The bad recipient threw but the others still got their
        // despawn — fan-out continues past per-recipient errors.
        assertEquals(2, despawns.size());
    }

    @Test
    public void despawnHappensBeforeKillLogger() {
        // Order matters: clients should see the model disappear
        // before any side effects of the kill (XP credit, loot
        // drop) — those follow in later commits.
        Zone z = newZone(7);
        z.addNPCtoZone(0, 0, 0, 100, 0, 0);
        NPC mob = z.getAllNPCs().get(0);
        int npcId = mob.getMapID();

        Player p = PacketTestFixture.newPlayer();
        z.registerPlayer(p);

        // Capture relative ordering by appending to a shared list.
        List<String> events = new ArrayList<>();
        MobDeathHandler.setDespawnSinkForTesting(
                (r, id) -> events.add("despawn"));
        MobDeathHandler.setKillLoggerForTesting(
                (i, n) -> events.add("logger"));

        MobDeathHandler.dispatch(new MobDeathIntent(npcId, 7, 0));

        assertEquals(2, events.size());
        assertEquals("despawn", events.get(0));
        assertEquals("logger",  events.get(1));
    }
}
