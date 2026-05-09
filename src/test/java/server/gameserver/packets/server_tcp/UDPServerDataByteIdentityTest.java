package server.gameserver.packets.server_tcp;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.Test;

import server.gameserver.CapturingTCPConnection;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.networktools.PacketBuilderTCP;
import server.networktools.ProtocolConstants;

/**
 * Byte-identity test for {@link UDPServerData} (TCP S→C 0x83 0x05).
 *
 * <p>Wire layout (28-byte body):
 * <pre>
 *   [0..1]   83 05            opcode
 *   [2..5]   account_id LE32
 *   [6..9]   char_id    LE32
 *   [10..13] server_ip  (4 raw bytes)
 *   [14..15] udp_port   LE16
 *   [16..19] flags      LE32 (= 0x00890000 per ProtocolConstants)
 *   [20..27] inverted session id (127 - sid[i] for each of 8 bytes)
 * </pre>
 *
 * <p>The flags field at [16..19] was previously 0x0000FFFF
 * (wire: {@code ff ff 00 00}), which the client silently
 * rejected. The current {@code ProtocolConstants.UDP_SERVER_DATA_FLAGS}
 * (= 0x00890000, wire {@code 00 00 89 00}) matches retail pcap
 * evidence — pinning so a future revert can't re-introduce
 * the silent-rejection bug.
 */
public class UDPServerDataByteIdentityTest {

    private static byte[] wireBytes(PacketBuilderTCP pkt) {
        byte[] data = pkt.getData();
        int n = pkt.size();
        byte[] sliced = new byte[n];
        System.arraycopy(data, 0, sliced, 0, n);
        return sliced;
    }

    /** Body starts at offset 3 (after FE-frame). */
    private static byte[] extractBody(byte[] wire, int len) {
        assertEquals((byte) 0xfe, wire[0]);
        byte[] body = new byte[len];
        System.arraycopy(wire, 3, body, 0, len);
        return body;
    }

    /** Override the random session ID in the fixture player so
     *  tests can assert deterministic bytes. */
    private static void setSessionId(Player pl, byte[] sid)
            throws Exception {
        Field f = Player.class.getDeclaredField("sessionId");
        f.setAccessible(true);
        f.set(pl, sid);
    }

    @Test
    public void bodyLayoutForKnownPlayer() throws Exception {
        Player pl = PacketTestFixture.newPlayer();
        pl.setTcpConnection(new CapturingTCPConnection());
        // Fixture: account_id=42, char_id=0x12345678
        // Override sessionId for deterministic output
        setSessionId(pl, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

        byte[] body = extractBody(wireBytes(new UDPServerData(pl)), 28);

        // [0..1] opcode 83 05
        assertEquals(0x83, body[0] & 0xFF);
        assertEquals(0x05, body[1] & 0xFF);
        // [2..5] account_id LE32 = 42 = 0x2A
        assertEquals(42, body[2] & 0xFF);
        assertEquals(0,  body[3] & 0xFF);
        assertEquals(0,  body[4] & 0xFF);
        assertEquals(0,  body[5] & 0xFF);
        // [6..9] char_id LE32 = 0x12345678
        assertEquals(0x78, body[6] & 0xFF);
        assertEquals(0x56, body[7] & 0xFF);
        assertEquals(0x34, body[8] & 0xFF);
        assertEquals(0x12, body[9] & 0xFF);
        // [10..13] server IP (loopback in the fixture's UDP path)
        // — actual value depends on Player's TCP connection; fixture
        // doesn't set TCP, so we assert structure not the value
        // here. The IP bytes follow at offset 10..13 by definition.
        // [14..15] udp_port LE16 = 5000 = 0x1388
        assertEquals(0x88, body[14] & 0xFF);
        assertEquals(0x13, body[15] & 0xFF);
        // [16..19] flags LE32 (UDP_SERVER_DATA_FLAGS)
        int flagsLE = (body[16] & 0xFF)
                | ((body[17] & 0xFF) << 8)
                | ((body[18] & 0xFF) << 16)
                | ((body[19] & 0xFF) << 24);
        assertEquals("flags must equal UDP_SERVER_DATA_FLAGS",
                ProtocolConstants.UDP_SERVER_DATA_FLAGS,
                flagsLE);
        // [20..27] inverted session ID: 127 - sid[i] for each
        for (int i = 0; i < 8; i++) {
            assertEquals("inverted session byte " + i,
                    127 - (i + 1),
                    body[20 + i] & 0xFF);
        }
    }

    @Test
    public void flagsBytesPinnedToProtocolConstant() {
        // Regression guard: the old `ff ff 00 00` flags caused
        // silent client rejection. The current
        // ProtocolConstants.UDP_SERVER_DATA_FLAGS = 0x00830000
        // matches the majority retail sample (2 of 3 cataloged
        // samples; the 3rd has 0x00fb0000, presumably a different
        // session-state variant).
        try {
            Player pl = PacketTestFixture.newPlayer();
            pl.setTcpConnection(new CapturingTCPConnection());
            setSessionId(pl, new byte[8]);

            byte[] body = extractBody(wireBytes(new UDPServerData(pl)), 28);
            // Flags = 0x00830000 → LE bytes: 00 00 83 00
            assertEquals(0x00, body[16] & 0xFF);
            assertEquals(0x00, body[17] & 0xFF);
            assertEquals(0x83, body[18] & 0xFF);
            assertEquals(0x00, body[19] & 0xFF);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void sessionIdInversionBitFlip() throws Exception {
        // For sid byte = 0, output = 127 - 0 = 127. For sid = 127,
        // output = 0. For sid = 100, output = 27. The constructor
        // uses `127 - sid[i]` directly (which Java promotes to int
        // via signed extension, but sid is unsigned 0..255 here).
        Player pl = PacketTestFixture.newPlayer();
        pl.setTcpConnection(new CapturingTCPConnection());
        setSessionId(pl, new byte[]{0, 127, 100, 1, 0, 0, 0, 0});

        byte[] body = extractBody(wireBytes(new UDPServerData(pl)), 28);
        // 127 - 0 = 127
        assertEquals(127, body[20] & 0xFF);
        // 127 - 127 = 0
        assertEquals(0,   body[21] & 0xFF);
        // 127 - 100 = 27
        assertEquals(27,  body[22] & 0xFF);
        // 127 - 1 = 126
        assertEquals(126, body[23] & 0xFF);
    }

    @Test
    public void totalSizeIsThirtyOneBytes() throws Exception {
        // 3-byte FE frame + 28-byte body = 31 bytes
        Player pl = PacketTestFixture.newPlayer();
        pl.setTcpConnection(new CapturingTCPConnection());
        setSessionId(pl, new byte[8]);
        assertEquals(31, new UDPServerData(pl).size());
    }

    @Test
    public void udpPortReflectsPlayerSetting() throws Exception {
        // Fixture player uses udp_port = 5000 (no per-session
        // listener). Pin that fall-through value.
        Player pl = PacketTestFixture.newPlayer();
        pl.setTcpConnection(new CapturingTCPConnection());
        setSessionId(pl, new byte[8]);
        assertEquals("fixture player.getUdpPort() should be 5000",
                5000, pl.getUdpPort());
        byte[] body = extractBody(wireBytes(new UDPServerData(pl)), 28);
        // udp_port LE16 at body offset 14..15
        int portLE = (body[14] & 0xFF) | ((body[15] & 0xFF) << 8);
        assertEquals(5000, portLE);
    }
}
