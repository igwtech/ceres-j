package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link SPing} — server response to
 * client {@code 0x0b} CPing keepalive (UDP S→C raw {@code 0x0b}).
 *
 * <p>4,939 retail samples across 17/17 captures, fixed 9-byte
 * body. Sample bodies (showing server-time at varying ticks):
 *
 * <pre>
 *   #1  0b b8 69 2d 00  d7 c8 02 05    server_time=0x002d69b8
 *   #2  0b 59 77 2d 00  6f d6 02 05    server_time=0x002d7759
 *   #3  0b 10 83 2d 00  2e e2 02 05    server_time=0x002d8310
 * </pre>
 *
 * <p>Layout: {@code [0x0b][server_time LE32][client_time LE32]}.
 * The server_time field is in the 0x0..0x1f000000 range across
 * retail samples — consistent with {@code Timer.getIngametime()}'s
 * 6-day-cycle modulo encoding.
 */
public class SPingByteIdentityTest {

    private static byte[] datagramBytes(SPing pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP13):
     *  {@code [0x13][counter LE2][counter+sk LE2][size LE2][body 9B]}
     *  Body starts at offset 7. The 9-byte body matches the catalog
     *  sample directly (catalog includes the 0x0b opcode byte). */
    private static byte[] extractInnerBody(byte[] datagram) {
        assertEquals("outer 0x13", 0x13, datagram[0] & 0xFF);
        byte[] body = new byte[9];
        System.arraycopy(datagram, 7, body, 0, 9);
        return body;
    }

    @Test
    public void clientTimeEchoEncodesLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        // Construct with a known client time (matches retail
        // sample #1's last 4 bytes: 0xd7 c8 02 05 = 0x0502c8d7)
        byte[] body = extractInnerBody(datagramBytes(
                new SPing(0x0502_C8D7, pl)));

        assertEquals("opcode 0x0b", 0x0b, body[0] & 0xFF);
        // client_time at offset 5..8 LE32
        assertEquals(0xD7, body[5] & 0xFF);
        assertEquals(0xC8, body[6] & 0xFF);
        assertEquals(0x02, body[7] & 0xFF);
        assertEquals(0x05, body[8] & 0xFF);
    }

    @Test
    public void serverTimeFieldIsPositiveAndChanges() {
        // Server time is Timer.getIngametime() + 10 — varies
        // each call. Two consecutive constructions should
        // produce server_time values that are non-decreasing
        // (the timer advances monotonically).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        byte[] body1 = extractInnerBody(
                datagramBytes(new SPing(0, pl)));
        byte[] body2 = extractInnerBody(
                datagramBytes(new SPing(0, pl)));

        int time1 = (body1[1] & 0xFF) | ((body1[2] & 0xFF) << 8)
                | ((body1[3] & 0xFF) << 16) | ((body1[4] & 0xFF) << 24);
        int time2 = (body2[1] & 0xFF) | ((body2[2] & 0xFF) << 8)
                | ((body2[3] & 0xFF) << 16) | ((body2[4] & 0xFF) << 24);

        // The values must both be positive (mod 6-day cycle so
        // < ~518M) and the second must be ≥ the first (timer
        // is monotonic; they may be equal if both ran in the
        // same millisecond).
        assertTrue("server_time > 0 (got " + time1 + ")",
                time1 > 0);
        assertTrue("server_time monotonic non-decreasing",
                time2 >= time1);
    }

    @Test
    public void serverTimeFitsInIngametimeRange() {
        // Timer.getIngametime() returns realtime % (6*24*60*1000)
        // = max ~518,400,000. SPing adds 10 to it. The resulting
        // LE32 value must fit in this range.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(
                datagramBytes(new SPing(0, pl)));

        long time = ((long)(body[1] & 0xFF))
                | ((long)(body[2] & 0xFF) << 8)
                | ((long)(body[3] & 0xFF) << 16)
                | ((long)(body[4] & 0xFF) << 24);
        assertTrue("server_time within 6-day cycle range "
                + "(got " + time + ")",
                time > 0 && time < 6L * 24 * 60 * 1000 + 100);
    }

    @Test
    public void totalDatagramSizeIsSixteenBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   9 (body) = 16 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(16, datagramBytes(new SPing(0, pl)).length);
    }

    @Test
    public void zeroClientTimeEncodesAsAllZeros() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(
                datagramBytes(new SPing(0, pl)));
        assertEquals(0, body[5] & 0xFF);
        assertEquals(0, body[6] & 0xFF);
        assertEquals(0, body[7] & 0xFF);
        assertEquals(0, body[8] & 0xFF);
    }
}
