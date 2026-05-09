package server.gameserver.npc;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.gameserver.Player;
import server.gameserver.WorldMessageBus;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Functional tests for {@link PlayerDamageDispatcher}: the
 * bus consumer that resolves a {@link PlayerDamageIntent} to a
 * live {@link Player} and forwards to {@link Player#applyDamage}.
 *
 * <p>Same test-seam pattern as {@link MobManager} and
 * {@link DroneManager}: inject a {@code VictimLookup} and a
 * {@code DamageApplier} so the dispatcher can be exercised
 * without booting {@link server.gameserver.PlayerManager}.
 */
public class PlayerDamageDispatcherTest {

    static final class CapturedApply {
        final Player victim;
        final float amount;
        final int attackerId, dmgType;
        CapturedApply(Player v, float a, int att, int dt) {
            victim = v; amount = a; attackerId = att; dmgType = dt;
        }
    }

    private List<CapturedApply> applies;
    private Map<Integer, Player> uidLookup;

    @Before
    public void setUp() {
        PlayerDamageDispatcher.resetForTesting();
        applies = new ArrayList<>();
        uidLookup = new HashMap<>();

        PlayerDamageDispatcher.setVictimLookupForTesting(
                uid -> uidLookup.get(uid));
        PlayerDamageDispatcher.setDamageApplierForTesting(
                (v, amt, att, dt) ->
                        applies.add(new CapturedApply(v, amt, att, dt)));
    }

    @After
    public void tearDown() {
        PlayerDamageDispatcher.resetForTesting();
    }

    @Test
    public void dispatchToOnlineVictimForwardsToApplier() {
        Player victim = PacketTestFixture.newPlayer();
        uidLookup.put(0xCAFE, victim);

        PlayerDamageDispatcher.dispatch(
                new PlayerDamageIntent(0xCAFE, 0xBEEF, 25.0f, 0x0a));

        assertEquals(1, applies.size());
        CapturedApply c = applies.get(0);
        assertSame(victim, c.victim);
        assertEquals(25.0f, c.amount, 0.0f);
        assertEquals(0xBEEF, c.attackerId);
        assertEquals(0x0a, c.dmgType);
    }

    @Test
    public void unknownVictimUidLogsAndDrops() {
        // Empty uidLookup map → victim resolution returns null →
        // dispatcher logs + drops, no apply.
        PlayerDamageDispatcher.dispatch(
                new PlayerDamageIntent(0x1234, 0, 10.0f, 0));
        assertTrue(applies.isEmpty());
    }

    @Test
    public void nullIntentIsSafe() {
        PlayerDamageDispatcher.dispatch(null);
        assertTrue(applies.isEmpty());
    }

    @Test
    public void applierExceptionDoesNotPropagate() {
        Player victim = PacketTestFixture.newPlayer();
        uidLookup.put(0x42, victim);

        PlayerDamageDispatcher.setDamageApplierForTesting(
                (v, amt, att, dt) -> {
                    throw new RuntimeException("simulated");
                });

        // Must not throw.
        PlayerDamageDispatcher.dispatch(
                new PlayerDamageIntent(0x42, 0, 1.0f, 0));
    }

    @Test
    public void multipleDispatchesOnSameVictimAccumulate() {
        Player victim = PacketTestFixture.newPlayer();
        uidLookup.put(0x99, victim);

        for (int i = 0; i < 3; i++) {
            PlayerDamageDispatcher.dispatch(
                    new PlayerDamageIntent(0x99, 0, 5.0f * (i + 1), 0));
        }
        assertEquals(3, applies.size());
        assertEquals(5.0f, applies.get(0).amount, 0.0f);
        assertEquals(10.0f, applies.get(1).amount, 0.0f);
        assertEquals(15.0f, applies.get(2).amount, 0.0f);
    }

    @Test
    public void installBusHandlersRegistersConsumer() {
        // Plug the dispatcher into a fresh bus and post one
        // intent — verify the apply happens via bus drain.
        WorldMessageBus bus = new WorldMessageBus();
        PlayerDamageDispatcher.installBusHandlers(bus);

        Player victim = PacketTestFixture.newPlayer();
        uidLookup.put(0x77, victim);

        bus.post(new PlayerDamageIntent(0x77, 0, 9.0f, 0));
        bus.drain(-1);

        assertEquals(1, applies.size());
        assertEquals(9.0f, applies.get(0).amount, 0.0f);
    }

    @Test
    public void resetForTestingClearsBothSeams() {
        // resetForTesting must restore both lookup and applier
        // to defaults. After reset, dispatch with the test seam
        // map empty falls through to the production VictimLookup
        // (PlayerManager scan), which in tests returns null.
        PlayerDamageDispatcher.resetForTesting();
        // After reset, our @Before-installed seams are gone, so
        // dispatch should hit the production no-victim path
        // without throwing.
        PlayerDamageDispatcher.dispatch(
                new PlayerDamageIntent(0x9999, 0, 1.0f, 0));
        // Pass = the production path tolerated an offline victim.
    }
}
