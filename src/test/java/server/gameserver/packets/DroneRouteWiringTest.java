package server.gameserver.packets;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.gameserver.packets.client_udp.DroneControlPacket;
import server.interfaces.GameServerEvent;

/**
 * Integration test: the SubtagRouter route at {@code 0x03/0x2d/-1}
 * produces a {@link DroneControlPacket} for both the heartbeat and
 * control variants.
 *
 * <p>Mirrors {@link ChatRouteWiringTest} for the chat carrier — same
 * mechanism, different subtag.
 */
public class DroneRouteWiringTest {

    @Before
    public void setUp() { SubtagRouter.clearAllForTesting(); }

    @After
    public void tearDown() { SubtagRouter.clearAllForTesting(); }

    @Test
    public void droneRouteProducesDroneControlPacket() {
        SubtagRouter.register(0x03, 0x2d, -1, -1, DroneControlPacket::new);
        byte[] sub = new byte[]{0x03, 0x00, 0x00, 0x2d, 0x00, 0x00, 0x00, 0x00, 0x0b};
        GameServerEvent e = SubtagRouter.dispatch(sub, 0x03, 0x2d, -1, -1);
        assertNotNull(e);
        assertTrue("expected DroneControlPacket got "
                + e.getClass().getName(), e instanceof DroneControlPacket);
    }

    @Test
    public void droneRouteIgnoresUnrelatedSubtag() {
        SubtagRouter.register(0x03, 0x2d, -1, -1, DroneControlPacket::new);
        // Different sub byte (0x1f) must not match the 0x2d route.
        GameServerEvent e = SubtagRouter.dispatch(new byte[]{0x03, 0, 0, 0x1f},
                0x03, 0x1f, -1, -1);
        assertNull(e);
    }
}
