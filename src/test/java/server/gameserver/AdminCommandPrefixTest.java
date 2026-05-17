package server.gameserver;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;

/**
 * Pins the chat-command prefix policy (task #179 follow-up): the
 * MaNGOS-style {@code .} prefix and the legacy {@code !} prefix are
 * consumed by {@link AdminCommandHandler#handle}; the {@code /}
 * prefix is deliberately NOT consumed because the Neocron client
 * swallows {@code /}-prefixed input locally (it never reaches the
 * server), and plain chat is never consumed.
 */
public class AdminCommandPrefixTest {

    private Player player;
    private WorldMessageBus bus;

    private static Player makePlayer() {
        Player pl = server.gameserver.packets.server_udp.PacketTestFixture
                .newPlayer();
        PlayerCharacter pc = pl.getCharacter();
        pc.setName("Tester");
        pc.setMisc(PlayerCharacter.MISC_ID, 0x1234);
        return pl;
    }

    @Before
    public void setUp() {
        bus = new WorldMessageBus();
        GameServer.setBusForTesting(bus);
        player = makePlayer();
    }

    @After
    public void tearDown() {
        GameServer.setBusForTesting(null);
    }

    @Test
    public void plainChatIsNotConsumed() {
        assertFalse(AdminCommandHandler.handle(player, "hello world"));
    }

    @Test
    public void slashPrefixIsNotConsumed() {
        // The client intercepts '/' locally — the server must let
        // such a line fall through as normal chat, not eat it.
        assertFalse(AdminCommandHandler.handle(player, "/help"));
        assertFalse(AdminCommandHandler.handle(player, "/pos"));
    }

    @Test
    public void dotPrefixIsConsumed() {
        // MaNGOS-style canonical prefix — a registered command.
        assertTrue(AdminCommandHandler.handle(player, ".help"));
    }

    @Test
    public void legacyBangPrefixStillConsumed() {
        assertTrue(AdminCommandHandler.handle(player, "!help"));
    }

    @Test
    public void emptyAndNullAreNotConsumed() {
        assertFalse(AdminCommandHandler.handle(player, ""));
        assertFalse(AdminCommandHandler.handle(player, null));
        assertFalse(AdminCommandHandler.handle(player, "   "));
    }
}
