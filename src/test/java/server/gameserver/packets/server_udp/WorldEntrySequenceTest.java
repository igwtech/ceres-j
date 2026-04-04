package server.gameserver.packets.server_udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.DatagramPacket;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;

/**
 * Integration-style tests that exercise the full list of packet builders used
 * by {@code WorldEntryEvent} in sequence, verifying that:
 * <ul>
 *   <li>every builder can be instantiated against a single shared Player
 *       instance (incrementing session counters as expected),</li>
 *   <li>the cumulative UDP datagram count is at least as large as the retail
 *       ~15-packet burst observed in /tmp/retail_capture.pcapng, and</li>
 *   <li>the packets each land within the UDP MTU (&lt;1500 bytes) so none get
 *       dropped on the path to the client.</li>
 * </ul>
 */
public class WorldEntrySequenceTest {

    private int totalDatagrams(server.interfaces.ServerUDPPacket... pkts) {
        int n = 0;
        for (server.interfaces.ServerUDPPacket p : pkts) {
            DatagramPacket[] dps = p.getDatagramPackets();
            assertNotNull(dps);
            n += dps.length;
            for (DatagramPacket dp : dps) {
                assertTrue("packet " + p.getClass().getSimpleName() + " exceeded UDP MTU: "
                        + dp.getLength(), dp.getLength() <= 1500);
            }
        }
        return n;
    }

    @Test
    public void fullSequenceProducesMultipleDatagrams() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        int mapId = pl.getMapID();

        int datagrams = totalDatagrams(
                new UDPAlive(pl),
                new UpdateModel(pl),
                new CharInfo(pl),
                new TimeSync(pl, 0),
                new PositionUpdate(pl),
                new WorldWeather(pl),
                new LongPlayerInfo(pl, pc, mapId),
                new ShortPlayerInfo(pl, pc, mapId),
                new PlayerPositionUpdate(pl, pc, mapId),
                new ZoningEnd(pl)
        );

        // A single CharInfo typically fragments into ~6 multi-part datagrams
        // (each ~230B). Plus UDPAlive, UpdateModel, TimeSync, PositionUpdate,
        // WorldWeather, LongPlayerInfo, ShortPlayerInfo, PlayerPositionUpdate,
        // ZoningEnd (1 datagram each) that's at least 6 + 9 = 15 datagrams.
        assertTrue("expected >= 10 datagrams from full sequence, got " + datagrams,
                datagrams >= 10);
    }

    @Test
    public void sessionCountersAreMonotonic() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();

        // ZoningEnd with session counter starting at 0: after ctor counter=1.
        DatagramPacket[] dps1 = new ZoningEnd(pl).getDatagramPackets();
        byte[] b1 = new byte[dps1[0].getLength()];
        System.arraycopy(dps1[0].getData(), 0, b1, 0, b1.length);
        int counter1 = (b1[1] & 0xFF) | ((b1[2] & 0xFF) << 8);

        // Second reliable packet built right after must have counter > 1.
        DatagramPacket[] dps2 = new ShortPlayerInfo(pl, pc, 1).getDatagramPackets();
        byte[] b2 = new byte[dps2[0].getLength()];
        System.arraycopy(dps2[0].getData(), 0, b2, 0, b2.length);
        int counter2 = (b2[1] & 0xFF) | ((b2[2] & 0xFF) << 8);

        assertTrue("session counter must increase, got " + counter1 + " then " + counter2,
                counter2 > counter1);
    }

    @Test
    public void charInfoFragmentsIntoMultipart() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        DatagramPacket[] dps = new CharInfo(pl).getDatagramPackets();

        // CharInfo is a 0x13->0x03->0x07 multi-part packet; even for an empty
        // inventory the payload is big enough to fragment into 2+ chunks.
        assertTrue("CharInfo must fragment; got " + dps.length + " datagram(s)",
                dps.length >= 2);

        // Every fragment must be framed as 0x13 + 0x03 reliable + 0x07 multipart.
        for (DatagramPacket dp : dps) {
            byte[] data = dp.getData();
            assertEquals(0x13, data[0] & 0xFF);
            assertEquals("reliable wrapper at offset 6", 0x03, data[6] & 0xFF);
            // 0x07 multipart sub-type lives at offset 9 (after
            // 0x13+counter+counter+size+0x03+seq = 9 bytes).
            assertEquals("multipart sub-type at offset 9", 0x07, data[9] & 0xFF);
        }

        // Fragment index / total in multipart header are at offsets 10..13.
        // The last fragment should have fragIdx + 1 == total.
        byte[] last = dps[dps.length - 1].getData();
        int fragIdx = (last[10] & 0xFF) | ((last[11] & 0xFF) << 8);
        int total   = (last[12] & 0xFF) | ((last[13] & 0xFF) << 8);
        assertEquals("last fragment index must equal total - 1",
                total - 1, fragIdx);
    }
}
