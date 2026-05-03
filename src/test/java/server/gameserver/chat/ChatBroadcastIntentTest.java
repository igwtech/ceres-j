package server.gameserver.chat;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import server.gameserver.WorldMessageBus;

/**
 * Functional test: end-to-end producer→bus→consumer for
 * {@link ChatBroadcastIntent}. Verifies that the WorldMessageBus
 * choke-point design from Phase 0.3 actually serializes chat
 * intents on a single consumer thread, in arrival order.
 */
public class ChatBroadcastIntentTest {

    @Test
    public void busDeliversIntentToHandler() {
        WorldMessageBus bus = new WorldMessageBus();
        List<ChatBroadcastIntent> seen = new ArrayList<>();
        bus.registerHandler(ChatBroadcastIntent.class, seen::add);

        bus.post(new ChatBroadcastIntent(
                0x00018678, "Norman Gates",
                Chat3bDecoder.CHANNEL_WHISPER,
                0x000186d2, "hey"));
        bus.drain(-1); // global queue

        assertEquals(1, seen.size());
        ChatBroadcastIntent i = seen.get(0);
        assertEquals(0x00018678, i.senderUid);
        assertEquals("Norman Gates", i.senderName);
        assertEquals(Chat3bDecoder.CHANNEL_WHISPER, i.channel);
        assertEquals(0x000186d2, i.targetUid);
        assertEquals("hey", i.message);
    }

    @Test
    public void chatIsAlwaysGlobalScope() {
        // Chat fan-out needs the global player directory regardless
        // of zone. The intent must report zone -1 unconditionally.
        ChatBroadcastIntent i = new ChatBroadcastIntent(
                1, "X", Chat3bDecoder.CHANNEL_TEAM, 0, "msg");
        assertEquals(-1, i.zoneId());
    }

    @Test
    public void multipleChatsArriveInPostOrder() {
        WorldMessageBus bus = new WorldMessageBus();
        List<String> seen = new ArrayList<>();
        bus.registerHandler(ChatBroadcastIntent.class,
                i -> seen.add(((ChatBroadcastIntent) i).message));

        for (int i = 0; i < 5; i++) {
            bus.post(new ChatBroadcastIntent(0, "A",
                    Chat3bDecoder.CHANNEL_TEAM, 0, "msg-" + i));
        }
        bus.drain(-1);

        assertEquals(5, seen.size());
        for (int i = 0; i < 5; i++) {
            assertEquals("msg-" + i, seen.get(i));
        }
    }

    @Test
    public void decodedChatRoundTripsIntoIntent() {
        // Producer side: decode a captured whisper body → intent.
        byte[] hex = new byte[]{
            0x02, 0x00, 0x3b, 0x04,
            (byte)0xd2, (byte)0x86, 0x01, 0x00,
            'h', 'e', 'y', 0x00
        };
        Chat3bDecoder.DecodedChat decoded = Chat3bDecoder.decode(hex);
        assertNotNull(decoded);

        ChatBroadcastIntent intent = new ChatBroadcastIntent(
                0x00018678, "Norman Gates",
                decoded.channel, decoded.targetUid, decoded.message);

        // Consumer side: handler receives intent, builds Chat8317.
        WorldMessageBus bus = new WorldMessageBus();
        List<server.gameserver.packets.server_tcp.Chat8317> reflected = new ArrayList<>();
        bus.registerHandler(ChatBroadcastIntent.class, i -> {
            ChatBroadcastIntent c = (ChatBroadcastIntent) i;
            reflected.add(new server.gameserver.packets.server_tcp.Chat8317(
                    c.senderUid, c.senderName, c.channel, c.message));
        });
        bus.post(intent);
        bus.drain(-1);

        assertEquals(1, reflected.size());
        // Spot-check the reflected packet body matches retail.
        byte[] body = reflected.get(0).getData();
        // FE-frame header (3) + Chat8317 body
        assertEquals((byte)0xfe, body[0]);
        // body[3..4] = 83 17 opcode
        assertEquals((byte)0x83, body[3]);
        assertEquals((byte)0x17, body[4]);
    }
}
