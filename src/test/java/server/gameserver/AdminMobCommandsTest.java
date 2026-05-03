package server.gameserver;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.npc.MobDamageIntent;
import server.gameserver.npc.MobManager;
import server.gameserver.npc.MobState;

/**
 * Tests for the admin testing commands {@code !hurtmob},
 * {@code !mobtick}, {@code !mobstate} (Phase 3 part 7).
 *
 * <p>Uses {@link GameServer#setBusForTesting} as a test seam to
 * inject a real {@link WorldMessageBus}, plus a captured
 * {@link CapturingTCPConnection} so reply lines from
 * {@link AdminCommandHandler#reply} land in a list rather than a
 * real socket.
 */
public class AdminMobCommandsTest {

    private List<MobDamageIntent> damageIntents;
    private Player player;
    private WorldMessageBus bus;

    private static Player makePlayer(int uid, String name) {
        // Use PacketTestFixture so Player has a UDP connection —
        // AdminCommandHandler.reply() emits a LocalChatMessage which
        // is a UDP packet and needs the session counter.
        Player pl = server.gameserver.packets.server_udp.PacketTestFixture
                .newPlayer();
        PlayerCharacter pc = pl.getCharacter();
        pc.setName(name);
        pc.setMisc(PlayerCharacter.MISC_ID, uid);
        pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, 0);
        pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, 0);
        pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, 0);
        return pl;
    }

    @Before
    public void setUp() {
        MobManager.resetForTesting();
        damageIntents = new ArrayList<>();

        bus = new WorldMessageBus();
        // Capture damage intents before they hit the dispatcher.
        bus.registerHandler(MobDamageIntent.class,
                i -> damageIntents.add((MobDamageIntent) i));
        GameServer.setBusForTesting(bus);

        player = makePlayer(0x1234, "Tester");
    }

    @After
    public void tearDown() {
        GameServer.setBusForTesting(null);
        MobManager.resetForTesting();
    }

    // ─── parseIntFlex ──────────────────────────────────────────────

    @Test
    public void parseIntFlexHandlesDecimal() {
        assertEquals(42, AdminCommandHandler.parseIntFlex("42"));
        assertEquals(0, AdminCommandHandler.parseIntFlex("0"));
        assertEquals(-7, AdminCommandHandler.parseIntFlex("-7"));
    }

    @Test
    public void parseIntFlexHandlesHex() {
        assertEquals(0x42, AdminCommandHandler.parseIntFlex("0x42"));
        assertEquals(0xCAFEBABE, AdminCommandHandler.parseIntFlex("0xCAFEBABE"));
        assertEquals(0xCAFE, AdminCommandHandler.parseIntFlex("0Xcafe"));
    }

    @Test
    public void parseIntFlexTrimsWhitespace() {
        assertEquals(7, AdminCommandHandler.parseIntFlex("  7  "));
    }

    @Test
    public void parseIntFlexThrowsOnGarbage() {
        try {
            AdminCommandHandler.parseIntFlex("not-a-number");
            fail("expected NumberFormatException");
        } catch (NumberFormatException expected) { /* ok */ }
    }

    @Test
    public void parseIntFlexThrowsOnNull() {
        try {
            AdminCommandHandler.parseIntFlex(null);
            fail("expected NumberFormatException");
        } catch (NumberFormatException expected) { /* ok */ }
    }

    // ─── !hurtmob ─────────────────────────────────────────────────

    @Test
    public void hurtMobPostsDamageIntent() {
        AdminCommandHandler.cmdHurtMob(player, "0x42 25");
        bus.drain(-1);

        assertEquals(1, damageIntents.size());
        MobDamageIntent intent = damageIntents.get(0);
        assertEquals(0x42, intent.npcId);
        assertEquals(25, intent.amount);
        // Player's character UID = 0x1234
        assertEquals(0x1234, intent.attackerUid);
    }

    @Test
    public void hurtMobAcceptsDecimalArguments() {
        AdminCommandHandler.cmdHurtMob(player, "100 50");
        bus.drain(-1);
        assertEquals(1, damageIntents.size());
        assertEquals(100, damageIntents.get(0).npcId);
        assertEquals(50,  damageIntents.get(0).amount);
    }

    @Test
    public void hurtMobMissingArgumentsDoesNotPostIntent() {
        AdminCommandHandler.cmdHurtMob(player, "");
        bus.drain(-1);
        assertTrue("no damage intent for empty args",
                damageIntents.isEmpty());
    }

    @Test
    public void hurtMobOneArgumentDoesNotPostIntent() {
        AdminCommandHandler.cmdHurtMob(player, "0x42");
        bus.drain(-1);
        assertTrue(damageIntents.isEmpty());
    }

    @Test
    public void hurtMobBadArgumentDoesNotPostIntent() {
        AdminCommandHandler.cmdHurtMob(player, "not-a-num 25");
        bus.drain(-1);
        assertTrue(damageIntents.isEmpty());
    }

    @Test
    public void hurtMobWithNullBusDoesNotThrow() {
        GameServer.setBusForTesting(null);
        // The command must short-circuit gracefully — no NPE — when
        // the world bus has not been initialised.
        AdminCommandHandler.cmdHurtMob(player, "0x42 10");
        // Re-installing the bus should still post nothing for that
        // earlier call (no caching).
        GameServer.setBusForTesting(bus);
        bus.drain(-1);
        assertTrue(damageIntents.isEmpty());
    }

    @Test
    public void hurtMobNullArgsDoesNotThrow() {
        // Defensive: invocation point may pass null when the chat
        // line had nothing after the command name.
        AdminCommandHandler.cmdHurtMob(player, null);
        bus.drain(-1);
        assertTrue(damageIntents.isEmpty());
    }

    // ─── !mobstate ─────────────────────────────────────────────────

    @Test
    public void mobStateForUnknownMobDoesNotThrow() {
        AdminCommandHandler.cmdMobState(player, "0x999");
        // Side-effect-free; just verify no exception escaped.
    }

    @Test
    public void mobStateForKnownMobDoesNotThrow() {
        MobManager.setState(bus, 0x42, 0, MobState.COMBAT, 0, 100f, 0x1234);
        bus.drain(-1);
        AdminCommandHandler.cmdMobState(player, "0x42");
    }

    @Test
    public void mobStateMissingArgumentDoesNotThrow() {
        AdminCommandHandler.cmdMobState(player, "");
    }

    @Test
    public void mobStateBadArgDoesNotThrow() {
        AdminCommandHandler.cmdMobState(player, "garbage");
    }

    // ─── !mobtick ─────────────────────────────────────────────────

    @Test
    public void mobTickWithoutZoneDoesNotThrow() {
        // Player has no zone — must reply gracefully and not NPE.
        AdminCommandHandler.cmdMobTick(player);
    }

    @Test
    public void mobTickWithZoneRunsAIScheduler() {
        // Attach the player to a synthetic zone and force a tick.
        Zone z = new Zone(99, "test_zone");
        z.addNPCtoZone(0, 0, 0, 100, 0, 0); // one mob at origin
        z.registerPlayer(player);
        try {
            Field f = Player.class.getDeclaredField("currentZone");
            f.setAccessible(true);
            f.set(player, z);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        AdminCommandHandler.cmdMobTick(player);
        bus.drain(-1);
        // The mob at distance 0 from the player aggroed: a snapshot
        // exists for it after the AI tick.
        assertNotNull("mob should be tracked after tick",
                MobManager.getSnapshot(z.getAllNPCs().get(0).getMapID()));
    }

    @Test
    public void mobTickWithoutBusDoesNotThrow() {
        GameServer.setBusForTesting(null);
        AdminCommandHandler.cmdMobTick(player);
    }
}
