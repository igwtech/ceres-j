package server.gameserver.packets;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.gameserver.packets.client_udp.ChatBroadcast;
import server.interfaces.GameServerEvent;

/**
 * Integration test for the SubtagRouter-based chat dispatch.
 *
 * <p>Asserts that registering the chat factory at
 * {@code 0x03/0x1f/0x3b/-1} produces a {@link ChatBroadcast} when a
 * raw subPacket comes through the router. This mirrors the production
 * registration done at {@code GameServer.init()}.
 */
public class ChatRouteWiringTest {

    @Before
    public void setUp() { SubtagRouter.clearAllForTesting(); }

    @After
    public void tearDown() { SubtagRouter.clearAllForTesting(); }

    @Test
    public void chatRouteProducesChatBroadcast() {
        SubtagRouter.register(0x03, 0x1f, 0x3b, -1,
                ChatBroadcast::new);

        byte[] sub = new byte[]{
                0x03, 0x00, 0x00, 0x1f,
                0x02, 0x00, 0x3b, 0x03,
                0x00, 0x00, 0x00, 0x00,
                'h', 'i', 0x00};
        GameServerEvent e = SubtagRouter.dispatch(sub, 0x03, 0x1f, 0x3b, -1);
        assertNotNull(e);
        assertTrue("expected ChatBroadcast got " + e.getClass().getName(),
                e instanceof ChatBroadcast);
    }

    @Test
    public void shallowFallbackStillReachesChatRoute() {
        // Even if the SubtagRouter is queried with subtag=0x99, the
        // chat registration at /-1 should be found via the shallow
        // fallback.
        SubtagRouter.register(0x03, 0x1f, 0x3b, -1,
                ChatBroadcast::new);
        GameServerEvent e = SubtagRouter.dispatch(
                new byte[]{0x03, 0, 0, 0x1f, 0x02, 0x00, 0x3b, 0x03,
                           0, 0, 0, 0, 'h', 'i', 0x00},
                0x03, 0x1f, 0x3b, 0x99);
        assertTrue(e instanceof ChatBroadcast);
    }
}
