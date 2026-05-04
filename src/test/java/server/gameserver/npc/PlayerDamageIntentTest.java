package server.gameserver.npc;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.WorldMessageBus;

/**
 * Functional tests for the PlayerDamageIntent → applyDamage path.
 *
 * <p>Uses the {@link PlayerDamageDispatcher} test seams to inject a
 * synthetic victim lookup + capturing damage-applier so we can
 * verify the dispatch logic without going through the real packet
 * pipeline (which sends UDP packets that drop without a socket).
 */
public class PlayerDamageIntentTest {

    static final class CapturedApply {
        final Player victim;
        final float  amount;
        final int    attackerId;
        final int    dmgType;
        CapturedApply(Player v, float a, int at, int dt) {
            this.victim = v; this.amount = a;
            this.attackerId = at; this.dmgType = dt;
        }
    }

    private List<CapturedApply> captured;

    private static Player makePlayer(int uid, String name) {
        Account acc = new Account(uid);
        acc.setUsername("u" + uid);
        Player pl = new Player(acc);
        PlayerCharacter pc = new PlayerCharacter();
        pc.setName(name);
        pc.setMisc(PlayerCharacter.MISC_ID, uid);
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
        PlayerDamageDispatcher.resetForTesting();
        captured = new ArrayList<>();
        PlayerDamageDispatcher.setDamageApplierForTesting(
                (v, a, at, dt) -> captured.add(
                        new CapturedApply(v, a, at, dt)));
    }

    @After
    public void tearDown() {
        PlayerDamageDispatcher.resetForTesting();
    }

    // ─── Dispatch ──────────────────────────────────────────────────

    @Test
    public void dispatchCallsApplyDamageOnVictim() {
        Player victim = makePlayer(0xCAFE, "Victim");
        PlayerDamageDispatcher.setVictimLookupForTesting(
                uid -> uid == 0xCAFE ? victim : null);

        PlayerDamageDispatcher.dispatch(new PlayerDamageIntent(
                0xCAFE, 0x42, 25.0f, 0x0a));

        assertEquals(1, captured.size());
        CapturedApply c = captured.get(0);
        assertEquals(victim, c.victim);
        assertEquals(25.0f, c.amount, 0.001f);
        assertEquals(0x42, c.attackerId);
        assertEquals(0x0a, c.dmgType);
    }

    @Test
    public void dispatchUnknownVictimSkipsCleanly() {
        PlayerDamageDispatcher.setVictimLookupForTesting(uid -> null);
        PlayerDamageDispatcher.dispatch(new PlayerDamageIntent(
                0xDEADBEEF, 0, 100f, 0));
        // No call captured, no exception.
        assertTrue(captured.isEmpty());
    }

    @Test
    public void dispatchNullIntentIsSafe() {
        PlayerDamageDispatcher.dispatch(null);
        assertTrue(captured.isEmpty());
    }

    @Test
    public void applierExceptionIsSwallowed() {
        Player victim = makePlayer(1, "V");
        PlayerDamageDispatcher.setVictimLookupForTesting(uid -> victim);
        PlayerDamageDispatcher.setDamageApplierForTesting(
                (v, a, at, dt) -> { throw new RuntimeException("boom"); });

        // Must not propagate the exception.
        PlayerDamageDispatcher.dispatch(new PlayerDamageIntent(
                1, 2, 10f, 0));
    }

    @Test
    public void busHandlerWiringDispatchesIntent() {
        Player victim = makePlayer(0x1234, "Tester");
        PlayerDamageDispatcher.setVictimLookupForTesting(
                uid -> uid == 0x1234 ? victim : null);

        WorldMessageBus bus = new WorldMessageBus();
        PlayerDamageDispatcher.installBusHandlers(bus);
        bus.post(new PlayerDamageIntent(0x1234, 0x999, 50f, 0));
        bus.drain(-1);

        assertEquals(1, captured.size());
        assertEquals(victim, captured.get(0).victim);
    }

    @Test
    public void multipleHitsAccumulate() {
        Player victim = makePlayer(1, "V");
        PlayerDamageDispatcher.setVictimLookupForTesting(uid -> victim);

        WorldMessageBus bus = new WorldMessageBus();
        PlayerDamageDispatcher.installBusHandlers(bus);
        for (int i = 0; i < 5; i++) {
            bus.post(new PlayerDamageIntent(1, 99, 10f, 0));
        }
        bus.drain(-1);

        assertEquals(5, captured.size());
    }

    // ─── Intent fields ─────────────────────────────────────────────

    @Test
    public void intentFieldsAreImmutableAndAccessible() {
        PlayerDamageIntent i = new PlayerDamageIntent(
                0x100, 0x200, 50.5f, 0x0a);
        assertEquals(0x100, i.victimUid);
        assertEquals(0x200, i.attackerId);
        assertEquals(50.5f, i.amount, 0.001f);
        assertEquals(0x0a, i.dmgType);
        // Bus scope is global until per-zone ticks land.
        assertEquals(-1, i.zoneId());
    }
}
