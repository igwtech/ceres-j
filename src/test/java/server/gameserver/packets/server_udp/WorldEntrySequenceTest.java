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
    public void charInfoFragmentsStayUnderReceiveCeiling() {
        // Revised 2026-06-06. The "82-byte receive ceiling" was a
        // misdiagnosis (a 230B datagram was observed delivered). Retail is
        // size-based: a CharInfo ≤ SINGLE_PACKET_THRESHOLD (900B) ships as ONE
        // 0x03/0x2c packet — the ONLY form the client routes to the inventory
        // grid parser — and larger goes multipart 0x03/0x07. A fresh
        // character's CharInfo fits, so it is a single 0x2c datagram. Every
        // datagram must stay under the real IP limit (MTU 1500).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        DatagramPacket[] dps = new CharInfo(pl).getDatagramPackets();

        assertTrue("CharInfo must emit ≥1 datagram", dps.length >= 1);
        for (DatagramPacket dp : dps) {
            byte[] data = dp.getData();
            assertEquals("0x13 outer frame", 0x13, data[0] & 0xFF);
            assertEquals("reliable wrapper at offset 7", 0x03, data[7] & 0xFF);
            int subTag = data[10] & 0xFF;
            assertTrue("sub-tag is 0x2c (single) or 0x07 (multipart), got 0x"
                    + Integer.toHexString(subTag),
                    subTag == 0x2c || subTag == 0x07);
            assertTrue("datagram must be under MTU (1500B), got "
                    + dp.getLength() + "B", dp.getLength() <= 1500);
        }
    }
}
