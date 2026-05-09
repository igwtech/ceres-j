package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link PositionUpdate} — UDP S→C reliable
 * {@code 0x03/0x2c} StartPos packet.
 *
 * <p>Pins the verified retail wire layout (4 retail pcaps, 4 first-
 * encountered samples, all 72-byte minimal form):
 *
 * <pre>
 *   HANNIBAL: 2c 01 01 0f 00 00 1a 00 00 c1 44 cd cc bf c2 00 f0 2b 45 ...
 *   NORMAN:   2c 01 01 00 00 00 00 ee 3a 05 c4 66 e6 7e c3 ec 51 52 45 ...
 *   AUGUSTO:  2c 01 01 00 6c 42 13 00 e0 38 c5 d0 cc cc 3d 00 00 8c c3 ...
 *   DRSTONE3: 2c 01 01 01 84 20 84 13 60 e6 45 3a cb 6b 44 f9 16 08 c4 ...
 * </pre>
 *
 * Layout (post {@code [0x03][seq LE2]} wrapper):
 * <pre>
 *   [0]      0x2c                CONSTANT (sub-opcode)
 *   [1]      0x01                CONSTANT (4/4 retail)
 *   [2]      0x01                CONSTANT (4/4 retail)
 *   [3..6]   4 var bytes         session state (entity ID or zone token)
 *   [7..10]  Y coord float LE32
 *   [11..14] Z coord float LE32
 *   [15..18] X coord float LE32
 *   [19..28] 10 zero bytes
 *   [29..30] LE16 trailer
 *   [31..]   character model section
 * </pre>
 *
 * <p><b>Pre-fix bug (resolved 2026-05-09):</b> body[2] was the LOW
 * byte of {@code mapID LE16} — happened to be 0x01 by accident when
 * test mapID==1, but for any other mapID the byte mismatched.
 * Pcap-replay harness against DRSTONE3 surfaced the divergence at
 * wire offset 6 (= body[2]).
 */
public class PositionUpdateByteIdentityTest {

    private static byte[] datagramBytes(PositionUpdate pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /**
     * Extract bytes following {@code [0x13 outer][0x03 reliable][seq
     * LE2][body...]} — outer 7B + 0x03 1B + seq 2B = 10B prefix
     * before body[0].
     */
    private static byte[] extractBody(byte[] datagram, int len) {
        assertTrue("datagram too short, got " + datagram.length
                + " want >=" + (10 + len),
                datagram.length >= 10 + len);
        assertEquals("outer 0x13",     0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",  0x03, datagram[7] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 10, body, 0, len);
        return body;
    }

    @Test
    public void firstThreeBytesAreConstant_2c_01_01() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        byte[] body = extractBody(datagramBytes(
                new PositionUpdate(pl)), 30);
        assertEquals("body[0] = sub-opcode 0x2c", 0x2c, body[0] & 0xFF);
        assertEquals("body[1] = const 0x01 (4/4 retail)",
                0x01, body[1] & 0xFF);
        assertEquals("body[2] = const 0x01 (4/4 retail) — pre-fix "
                + "bug: was mapID-lo, broke for mapID != 1",
                0x01, body[2] & 0xFF);
    }

    @Test
    public void floatsLandAtBodyOffsets7_11_15() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        // Test fixture sets MISC_X_COORDINATE=100, Y=200, Z=300.
        byte[] body = extractBody(datagramBytes(
                new PositionUpdate(pl)), 19);
        ByteBuffer bb = ByteBuffer.wrap(body)
                .order(ByteOrder.LITTLE_ENDIAN);
        float yCoord = bb.getFloat(7);
        float zCoord = bb.getFloat(11);
        float xCoord = bb.getFloat(15);
        assertEquals("Y coord float at body[7..10]",
                200.0f, yCoord, 0.001f);
        assertEquals("Z coord float at body[11..14]",
                300.0f, zCoord, 0.001f);
        assertEquals("X coord float at body[15..18]",
                100.0f, xCoord, 0.001f);
    }

    @Test
    public void bytes19Through28AreTenZeros() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        byte[] body = extractBody(datagramBytes(
                new PositionUpdate(pl)), 29);
        for (int i = 19; i < 29; i++) {
            assertEquals("body[" + i + "] should be zero (retail "
                    + "padding); got 0x"
                    + String.format("%02x", body[i] & 0xFF),
                    0, body[i]);
        }
    }

    @Test
    public void mapIDIsEncodedAsLE32StateAtBytes3Through6() {
        // Body[3..6] is session state — emitted as mapID LE32
        // placeholder. Pin the byte order so a future regression
        // (e.g. switching to LE16 + 2 zeros, or BE) is caught.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey(
                (short) 0);
        // Default test fixture mapID = 1 → LE32 = 01 00 00 00.
        byte[] body = extractBody(datagramBytes(
                new PositionUpdate(pl)), 7);
        assertEquals("mapID LE32 byte 0", 0x01, body[3] & 0xFF);
        assertEquals("mapID LE32 byte 1", 0x00, body[4] & 0xFF);
        assertEquals("mapID LE32 byte 2", 0x00, body[5] & 0xFF);
        assertEquals("mapID LE32 byte 3", 0x00, body[6] & 0xFF);
    }
}
