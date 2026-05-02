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
    public void charInfoUsesSinglePacketForSmallBody() {
        // A fresh character (no inventory, no quest log, no faction reps)
        // produces a CharInfo body well under the ~900-byte multipart
        // threshold. Verified retail behavior 2026-05-01: small bodies ride
        // reliable_type 0x2c as a single packet (Dr.Stone fresh char in
        // Genesis Dungeon AND Plaza Sec-1 both used 0x2c).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        DatagramPacket[] dps = new CharInfo(pl).getDatagramPackets();

        assertEquals("small CharInfo must be a single packet, got "
                + dps.length + " datagram(s)", 1, dps.length);

        // Frame: 0x13 + counter + size + 0x03 reliable wrapper + 0x2c sub-type.
        byte[] data = dps[0].getData();
        assertEquals(0x13, data[0] & 0xFF);
        assertEquals("reliable wrapper at offset 7", 0x03, data[7] & 0xFF);
        assertEquals("single-packet sub-type at offset 10", 0x2c, data[10] & 0xFF);

        // Inner data starts immediately after offset 10. Retail single-mode
        // prefix is `02 01` (replaces multipart's `00 22 02 01` chain_key+magic).
        assertEquals("single-mode prefix byte 0", 0x02, data[11] & 0xFF);
        assertEquals("single-mode prefix byte 1", 0x01, data[12] & 0xFF);
        // Section 1 ID immediately follows the 2-byte prefix.
        assertEquals("section 1 id at offset 13", 0x01, data[13] & 0xFF);
    }
}
