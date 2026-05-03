package server.gameserver;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.LinkedList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.chat.Chat3bDecoder;
import server.gameserver.chat.ChatBroadcastIntent;
import server.gameserver.packets.server_tcp.Chat8317;
import server.interfaces.ServerTCPPacket;

/**
 * Functional tests for {@link ChatManager#installBusHandlers} and
 * {@link ChatManager#dispatchChatIntent}.
 *
 * <p>Each test sets up a small "world" of players with capturing TCP
 * connections, registers the chat handler on a fresh
 * {@link WorldMessageBus}, posts a {@link ChatBroadcastIntent}, and
 * asserts which players received a {@link Chat8317} packet.
 *
 * <p>{@link PlayerManager#playerList} is populated via reflection
 * because there is no public test seam (Phase 0 deliberately keeps
 * production APIs minimal).
 */
public class ChatManagerHandlerTest {

    @SuppressWarnings("unchecked")
    private static LinkedList<Player> playerList() {
        try {
            Field f = PlayerManager.class.getDeclaredField("playerList");
            f.setAccessible(true);
            return (LinkedList<Player>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Player makePlayer(int uid, String name,
                                      CapturingTCPConnection cap) {
        Account acc = new Account(uid);
        acc.setUsername("u" + uid);
        Player pl = new Player(acc);
        PlayerCharacter pc = new PlayerCharacter();
        pc.setName(name);
        pc.setMisc(PlayerCharacter.MISC_ID, uid);
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

    private LinkedList<Player> snapshot;

    @Before
    public void setUp() {
        // Save the existing playerList contents so we don't leak state
        // into other tests in the suite.
        snapshot = new LinkedList<>(playerList());
        playerList().clear();
    }

    @After
    public void tearDown() {
        playerList().clear();
        playerList().addAll(snapshot);
    }

    @Test
    public void whisperGoesOnlyToTarget() {
        CapturingTCPConnection senderCap = new CapturingTCPConnection();
        CapturingTCPConnection targetCap = new CapturingTCPConnection();
        CapturingTCPConnection otherCap  = new CapturingTCPConnection();
        Player sender = makePlayer(0x00018678, "Norman Gates", senderCap);
        Player target = makePlayer(0x000186d2, "Dra Moni",     targetCap);
        Player other  = makePlayer(0x999, "Bystander",         otherCap);
        playerList().add(sender);
        playerList().add(target);
        playerList().add(other);

        WorldMessageBus bus = new WorldMessageBus();
        ChatManager.installBusHandlers(bus);
        bus.post(new ChatBroadcastIntent(0x00018678, "Norman Gates",
                Chat3bDecoder.CHANNEL_WHISPER, 0x000186d2, "hey"));
        bus.drain(-1);

        assertEquals("sender should not receive own whisper",
                0, senderCap.count());
        assertEquals(1, targetCap.count());
        assertEquals(0, otherCap.count());
        assertTrue(targetCap.received().get(0) instanceof Chat8317);
    }

    @Test
    public void whisperToOfflineTargetDropsCleanly() {
        // No players online at all.
        WorldMessageBus bus = new WorldMessageBus();
        ChatManager.installBusHandlers(bus);
        bus.post(new ChatBroadcastIntent(1, "Ghost",
                Chat3bDecoder.CHANNEL_WHISPER, 0xdeadbeef, "anyone?"));
        // Should not throw.
        bus.drain(-1);
    }

    @Test
    public void teamChatFansOutToAllExceptSender() {
        CapturingTCPConnection capA = new CapturingTCPConnection();
        CapturingTCPConnection capB = new CapturingTCPConnection();
        CapturingTCPConnection capC = new CapturingTCPConnection();
        Player a = makePlayer(1, "A", capA);
        Player b = makePlayer(2, "B", capB);
        Player c = makePlayer(3, "C", capC);
        playerList().add(a);
        playerList().add(b);
        playerList().add(c);

        WorldMessageBus bus = new WorldMessageBus();
        ChatManager.installBusHandlers(bus);
        bus.post(new ChatBroadcastIntent(1, "A",
                Chat3bDecoder.CHANNEL_TEAM, 0, "go go go"));
        bus.drain(-1);

        assertEquals("sender excluded", 0, capA.count());
        assertEquals(1, capB.count());
        assertEquals(1, capC.count());
    }

    @Test
    public void clanChatFansOutToAllExceptSender() {
        CapturingTCPConnection capA = new CapturingTCPConnection();
        CapturingTCPConnection capB = new CapturingTCPConnection();
        Player a = makePlayer(7, "Clanleader", capA);
        Player b = makePlayer(8, "Clanmate",   capB);
        playerList().add(a);
        playerList().add(b);

        WorldMessageBus bus = new WorldMessageBus();
        ChatManager.installBusHandlers(bus);
        bus.post(new ChatBroadcastIntent(7, "Clanleader",
                Chat3bDecoder.CHANNEL_CLAN, 0, "ops at 9"));
        bus.drain(-1);

        assertEquals(0, capA.count());
        assertEquals(1, capB.count());
    }

    @Test
    public void buddyChatFansOutToAllExceptSender() {
        CapturingTCPConnection capA = new CapturingTCPConnection();
        CapturingTCPConnection capB = new CapturingTCPConnection();
        Player a = makePlayer(20, "Friend1", capA);
        Player b = makePlayer(21, "Friend2", capB);
        playerList().add(a);
        playerList().add(b);

        WorldMessageBus bus = new WorldMessageBus();
        ChatManager.installBusHandlers(bus);
        bus.post(new ChatBroadcastIntent(20, "Friend1",
                Chat3bDecoder.CHANNEL_BUDDY, 0, "wb"));
        bus.drain(-1);

        assertEquals(0, capA.count());
        assertEquals(1, capB.count());
    }

    @Test
    public void emittedPacketIsValidChat8317() {
        CapturingTCPConnection cap = new CapturingTCPConnection();
        Player target = makePlayer(0x000186d2, "Dra Moni", cap);
        playerList().add(target);

        WorldMessageBus bus = new WorldMessageBus();
        ChatManager.installBusHandlers(bus);
        bus.post(new ChatBroadcastIntent(0x00018678, "Norman Gates",
                Chat3bDecoder.CHANNEL_WHISPER, 0x000186d2, "hey"));
        bus.drain(-1);

        assertEquals(1, cap.count());
        ServerTCPPacket p = cap.received().get(0);
        assertTrue(p instanceof Chat8317);
        byte[] data = p.getData();
        // FE-frame header(3) + opcode(2) + uid(4) + name_len(1) + chan(1)
        // + sep(1) + name + msg = 12 + len(name) + len(msg)
        assertEquals((byte) 0xfe, data[0]);
        assertEquals((byte) 0x83, data[3]);
        assertEquals((byte) 0x17, data[4]);
        // sender_uid LE4 = 0x00018678
        assertEquals((byte) 0x78, data[5]);
        assertEquals((byte) 0x86, data[6]);
        assertEquals((byte) 0x01, data[7]);
        assertEquals((byte) 0x00, data[8]);
        // name_len = strlen("Norman Gates") = 12
        assertEquals(12, data[9] & 0xff);
        assertEquals(Chat3bDecoder.CHANNEL_WHISPER, data[10] & 0xff);
    }
}
