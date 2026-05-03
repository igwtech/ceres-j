package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.Test;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.WorldMessageBus;
import server.gameserver.chat.Chat3bDecoder;
import server.gameserver.chat.ChatBroadcastIntent;

/**
 * Unit + functional tests for {@link ChatBroadcast}.
 *
 * <p>Verifies that the client_udp wrapper:
 * <ul>
 *   <li>strips the {@code 03 [seq2] 1f} prefix and feeds the body to
 *       {@link Chat3bDecoder};</li>
 *   <li>posts a {@link ChatBroadcastIntent} carrying the player's UID
 *       and name plus the decoded channel/target/message;</li>
 *   <li>drops malformed inputs without throwing.</li>
 * </ul>
 *
 * <p>The functional test uses raw bytes from a retail PARTY_B whisper
 * capture so the producer side is end-to-end byte-driven.
 */
public class ChatBroadcastTest {

    private static byte[] hex(String h) {
        h = h.replaceAll("\\s+", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    /** Build a player with a deterministic UID/name and no UDP/Zone/DB. */
    private static Player makePlayer(int uid, String name) {
        Account acc = new Account(1);
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

    @Test
    public void postsIntentForWhisper() {
        WorldMessageBus bus = new WorldMessageBus();
        java.util.List<ChatBroadcastIntent> seen = new java.util.ArrayList<>();
        bus.registerHandler(ChatBroadcastIntent.class,
                i -> seen.add((ChatBroadcastIntent) i));

        // Outer wrapper: 03 [seq2] 1f, then body 02 00 3b 04 [uid LE4] "hey\0"
        byte[] sub = hex(
                "03 aa bb 1f " +
                "02 00 3b 04 d2 86 01 00 68 65 79 00");

        ChatBroadcast pkt = new ChatBroadcast(sub, bus);
        pkt.execute(makePlayer(0x00018678, "Norman Gates"));
        bus.drain(-1);

        assertEquals(1, seen.size());
        ChatBroadcastIntent i = seen.get(0);
        assertEquals(0x00018678, i.senderUid);
        assertEquals("Norman Gates", i.senderName);
        assertEquals(Chat3bDecoder.CHANNEL_WHISPER, i.channel);
        assertEquals(0x000186d2, i.targetUid);
        assertEquals("hey", i.message);
    }

    @Test
    public void postsIntentForTeamChat() {
        WorldMessageBus bus = new WorldMessageBus();
        java.util.List<ChatBroadcastIntent> seen = new java.util.ArrayList<>();
        bus.registerHandler(ChatBroadcastIntent.class,
                i -> seen.add((ChatBroadcastIntent) i));

        byte[] sub = hex(
                "03 00 00 1f " +
                "02 00 3b 03 00 00 00 00 " +
                "48 65 6c 6c 6f 20 74 65 61 6d 00");

        new ChatBroadcast(sub, bus).execute(makePlayer(42, "Tester"));
        bus.drain(-1);

        assertEquals(1, seen.size());
        ChatBroadcastIntent i = seen.get(0);
        assertEquals(Chat3bDecoder.CHANNEL_TEAM, i.channel);
        assertEquals("Hello team", i.message);
        assertEquals(42, i.senderUid);
    }

    @Test
    public void malformedBodyDropsSilently() {
        WorldMessageBus bus = new WorldMessageBus();
        java.util.List<ChatBroadcastIntent> seen = new java.util.ArrayList<>();
        bus.registerHandler(ChatBroadcastIntent.class,
                i -> seen.add((ChatBroadcastIntent) i));

        // Wrong tag byte (0x1b instead of 0x3b) — decoder should reject.
        byte[] sub = hex("03 00 00 1f 02 00 1b 04 00 00 00 00 68 69 00");
        new ChatBroadcast(sub, bus).execute(makePlayer(1, "x"));
        bus.drain(-1);

        assertTrue(seen.isEmpty());
    }

    @Test
    public void shortPacketDoesNotThrow() {
        WorldMessageBus bus = new WorldMessageBus();
        // Shorter than INNER_OFFSET=3.
        new ChatBroadcast(new byte[]{0x03, 0x00}, bus)
                .execute(makePlayer(1, "x"));
        // No crash, no intent.
        assertEquals(0, bus.pending(-1));
    }

    @Test
    public void noBusAndNoFallbackDropsCleanly() {
        // Constructor with single arg uses GameServer.getBus() at execute
        // time. In tests GameServer.getBus() returns null because
        // GameServer.init() is not called. The packet must not throw.
        byte[] sub = hex(
                "03 00 00 1f 02 00 3b 03 00 00 00 00 68 69 00");
        new ChatBroadcast(sub).execute(makePlayer(1, "x"));
        // Nothing crashed — that is the assertion.
    }
}
