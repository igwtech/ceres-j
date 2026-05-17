package server.gameserver;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.Test;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.packets.server_tcp.Chat8317;
import server.interfaces.ServerTCPPacket;

/**
 * Functional test (task #181): a reply-only GM command such as
 * {@code .help} must emit the retail-pinned TCP {@code 0x83 0x17}
 * system-broadcast packet on the caller's connection — not the legacy
 * hand-rolled {@code LocalChatMessage} (which the retail client never
 * rendered, so {@code .help} silently did nothing in-game).
 */
public class AdminCommandReplyTest {

    private static byte[] body(byte[] framed) {
        int len = (framed[1] & 0xff) | ((framed[2] & 0xff) << 8);
        byte[] out = new byte[len];
        System.arraycopy(framed, 3, out, 0, len);
        return out;
    }

    private static Player makePlayer(CapturingTCPConnection cap) {
        Account acc = new Account(0x1234);
        acc.setUsername("gm");
        Player pl = new Player(acc);
        PlayerCharacter pc = new PlayerCharacter();
        pc.setName("Tester");
        pc.setMisc(PlayerCharacter.MISC_ID, 0x1234);
        try {
            Field pcField = Player.class.getDeclaredField("pc");
            pcField.setAccessible(true);
            pcField.set(pl, pc);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        pl.setTcpConnection(cap);
        return pl;
    }

    @Test
    public void helpCommandEmitsSystemChatPacket() {
        CapturingTCPConnection cap = new CapturingTCPConnection();
        Player pl = makePlayer(cap);

        boolean consumed = AdminCommandHandler.handle(pl, ".help");

        assertTrue(".help must be consumed as a command", consumed);
        List<ServerTCPPacket> got = cap.received();
        assertFalse("reply must emit at least one TCP packet",
                got.isEmpty());

        ServerTCPPacket p = got.get(0);
        assertTrue("reply must be a Chat8317 (TCP 0x83 0x17)",
                p instanceof Chat8317);

        byte[] b = body(((Chat8317) p).getData());
        // 83 17  ff ff ff ff  [name_len] ff 00  "Server" "[Server] ..."
        assertEquals((byte) 0x83, b[0]);
        assertEquals((byte) 0x17, b[1]);
        assertEquals((byte) 0xff, b[2]);
        assertEquals((byte) 0xff, b[3]);
        assertEquals((byte) 0xff, b[4]);
        assertEquals((byte) 0xff, b[5]);
        assertEquals((byte) 0x06, b[6]);           // "Server" = 6
        assertEquals((byte) 0xff, b[7]);           // system channel
        assertEquals((byte) 0x00, b[8]);           // sub-channel
        String payload = new String(b, 9, b.length - 9);
        // name (6B "Server") + message ("[Server] <text>")
        assertTrue("name+message starts with Server[Server]",
                payload.startsWith("Server[Server] "));
        assertFalse("reply must carry non-empty help text",
                payload.equals("Server[Server] "));
    }

    @Test
    public void unknownCommandStillReplies() {
        CapturingTCPConnection cap = new CapturingTCPConnection();
        Player pl = makePlayer(cap);

        boolean consumed = AdminCommandHandler.handle(pl, ".nosuchcmd");

        assertTrue(consumed);
        assertEquals("unknown command produces exactly one reply",
                1, cap.count());
        assertTrue(cap.received().get(0) instanceof Chat8317);
    }

    @Test
    public void nonCommandIsNotConsumedAndEmitsNothing() {
        CapturingTCPConnection cap = new CapturingTCPConnection();
        Player pl = makePlayer(cap);

        boolean consumed = AdminCommandHandler.handle(pl, "hello world");

        assertFalse("plain chat must not be treated as a command",
                consumed);
        assertEquals("no reply for non-command", 0, cap.count());
    }
}
