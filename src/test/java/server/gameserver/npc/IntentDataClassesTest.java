package server.gameserver.npc;

import static org.junit.Assert.*;

import org.junit.Test;

import server.gameserver.WorldMessageBus;

/**
 * Pin tests for the three cross-player intent data classes:
 * {@link MobStateChangeIntent}, {@link MobDeathIntent}, and
 * {@link DroneControlIntent}.
 *
 * <p>These intents are simple immutable carriers that flow
 * through {@link WorldMessageBus}. Pinning their field
 * surfaces and the {@code zoneId() == -1} contract so any
 * future "per-zone tick" refactor that flips to non-global
 * routing surfaces explicitly here.
 */
public class IntentDataClassesTest {

    @Test
    public void mobStateChangeIntentExposesAllFields() {
        MobStateChangeIntent i = new MobStateChangeIntent(
                0xAB, 7, MobState.COMBAT,
                0x42, 12.5f, 0xCAFE);

        assertEquals(0xAB,  i.npcId);
        assertEquals(7,     i.zoneId);
        assertEquals(MobState.COMBAT, i.state);
        assertEquals(0x42,  i.flagsByte);
        assertEquals(12.5f, i.altitude, 0.0f);
        assertEquals(0xCAFE, i.targetId);
    }

    @Test
    public void mobStateChangeIntentRoutesToGlobalQueue() {
        // Phase-3 contract: zoneId() returns -1 (global) until
        // per-zone ticks land. The constructor zoneId argument
        // is informational only.
        MobStateChangeIntent i = new MobStateChangeIntent(
                1, /*zoneId=*/42, MobState.IDLE, 0, 0, 0);
        assertEquals("intent must route to global queue (-1)",
                -1, i.zoneId());
        assertEquals("but the zoneId field carries the real zone",
                42, i.zoneId);
    }

    @Test
    public void mobDeathIntentExposesAllFields() {
        MobDeathIntent i = new MobDeathIntent(0x123, 7, 0xCAFE);
        assertEquals(0x123, i.npcId);
        assertEquals(7,     i.zoneIdField);
        assertEquals(0xCAFE, i.killerUid);
    }

    @Test
    public void mobDeathIntentEnvironmentalKillUsesZeroKillerUid() {
        // killerUid = 0 sentinel for environmental deaths (fall
        // damage, scripted). Pin the convention.
        MobDeathIntent i = new MobDeathIntent(1, 7, 0);
        assertEquals(0, i.killerUid);
    }

    @Test
    public void mobDeathIntentRoutesToGlobalQueue() {
        MobDeathIntent i = new MobDeathIntent(0, 0, 0);
        assertEquals(-1, i.zoneId());
    }

    @Test
    public void droneControlIntentExposesAllFields() {
        byte[] tail = new byte[20];
        for (int j = 0; j < 20; j++) tail[j] = (byte) j;

        DroneControlIntent i = new DroneControlIntent(
                0xCAFE, 0xBABE,
                1.5f, 2.5f, 3.5f, tail);

        assertEquals(0xCAFE, i.pilotUid);
        assertEquals(0xBABE, i.droneId);
        assertEquals(1.5f, i.posX, 0.0f);
        assertEquals(2.5f, i.posY, 0.0f);
        assertEquals(3.5f, i.posZ, 0.0f);
        assertSame("tail must be passed through by reference "
                + "(no defensive copy on the hot path)",
                tail, i.tail);
    }

    @Test
    public void droneControlIntentRoutesToGlobalQueue() {
        DroneControlIntent i = new DroneControlIntent(
                0, 0, 0, 0, 0, new byte[20]);
        assertEquals(-1, i.zoneId());
    }

    @Test
    public void allIntentsImplementBusIntent() {
        // Compile-time guard: the bus expects WorldMessageBus.Intent.
        WorldMessageBus.Intent a = new MobStateChangeIntent(
                0, 0, MobState.IDLE, 0, 0, 0);
        WorldMessageBus.Intent b = new MobDeathIntent(0, 0, 0);
        WorldMessageBus.Intent c = new DroneControlIntent(
                0, 0, 0, 0, 0, new byte[0]);
        assertNotNull(a);
        assertNotNull(b);
        assertNotNull(c);
    }
}
