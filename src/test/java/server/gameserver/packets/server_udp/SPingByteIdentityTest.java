package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link SPing} — server response to
 * client {@code 0x0b} CPing keepalive.
 *
 * <p>Retail ground truth (Frida harness, client-side decrypted
 * {@code udp_recv}): every S→C {@code 0x0b} is an unreliable
 * sub-packet inside a {@code 0x13} frame — 884 samples, none at
 * transport level. Sample (retail_draw_v3, t=2.724, answering
 * C→S {@code 0b abaa9502}):
 * <pre>
 *   13 4700 3181 0900 | 0b 6d4a6a00 abaa9502
 *   ^hdr: counter=71, counter+key  ^sub_len=9
 *                       server_time=0x006a4a6d, client echo=0x0295aaab
 * </pre>
 *
 * <p>Sub-packet layout: {@code [0x0b][server_time LE32][client_time LE32]}.
 * The server_time field is consistent with
 * {@code Timer.getIngametime()}'s 6-day-cycle modulo encoding.
 */
public class SPingByteIdentityTest {

    /** Offset of the {@code 0x0b} opcode: after
     *  {@code [0x13][counter LE2][counter+key LE2][sub_len LE2]}. */
    private static final int SUB = 7;

    private static byte[] datagramBytes(SPing pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    @Test
    public void wrappedIn0x13FrameWithUnconsumedCounter() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0x1234);
        int counterBefore = pl.getUdpConnection().getSessionCounter();

        byte[] dgm = datagramBytes(new SPing(0x0295_AAAB, pl));

        assertEquals("16 bytes: 7B 0x13 header + 9B ping sub-packet",
                16, dgm.length);
        assertEquals("outer frame is 0x13", 0x13, dgm[0] & 0xFF);
        int counter = (dgm[1] & 0xFF) | ((dgm[2] & 0xFF) << 8);
        int keyed = (dgm[3] & 0xFF) | ((dgm[4] & 0xFF) << 8);
        assertEquals("counter is the current reliable counter",
                counterBefore, counter);
        assertEquals("second field is counter + session key",
                (counterBefore + 0x1234) & 0xFFFF, keyed);
        assertEquals("sub_len LE2 = 9", 9, dgm[5] & 0xFF);
        assertEquals(0, dgm[6] & 0xFF);
        assertEquals("unreliable reply must not consume a reliable seq",
                counterBefore,
                pl.getUdpConnection().getSessionCounter());
    }

    @Test
    public void clientTimeEchoEncodesLittleEndian() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);

        // Retail sample echo: ab aa 95 02 = 0x0295aaab
        byte[] dgm = datagramBytes(new SPing(0x0295_AAAB, pl));

        assertEquals("opcode 0x0b", 0x0b, dgm[SUB] & 0xFF);
        assertEquals(0xAB, dgm[SUB + 5] & 0xFF);
        assertEquals(0xAA, dgm[SUB + 6] & 0xFF);
        assertEquals(0x95, dgm[SUB + 7] & 0xFF);
        assertEquals(0x02, dgm[SUB + 8] & 0xFF);
    }

    @Test
    public void serverTimeFieldIsPositiveAndChanges() {
        // Server time is Timer.getIngametime() + 10 — varies
        // each call. Two consecutive constructions should
        // produce server_time values that are non-decreasing.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);

        byte[] dgm1 = datagramBytes(new SPing(0, pl));
        byte[] dgm2 = datagramBytes(new SPing(0, pl));

        int t1 = readLE32(dgm1, SUB + 1);
        int t2 = readLE32(dgm2, SUB + 1);

        assertTrue("server_time > 0 (got " + t1 + ")", t1 > 0);
        assertTrue("server_time monotonic non-decreasing",
                t2 >= t1);
    }

    @Test
    public void serverTimeFitsInIngametimeRange() {
        // Timer.getIngametime() returns realtime % (6*24*60*1000)
        // = max ~518,400,000. SPing adds 10 to it. The resulting
        // LE32 value must fit in this range.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        byte[] dgm = datagramBytes(new SPing(0, pl));

        long time = readLE32(dgm, SUB + 1) & 0xFFFFFFFFL;
        assertTrue("server_time within 6-day cycle range "
                + "(got " + time + ")",
                time > 0 && time < 6L * 24 * 60 * 1000 + 100);
    }

    @Test
    public void zeroClientTimeEncodesAsAllZeros() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        byte[] dgm = datagramBytes(new SPing(0, pl));
        assertEquals(0, dgm[SUB + 5] & 0xFF);
        assertEquals(0, dgm[SUB + 6] & 0xFF);
        assertEquals(0, dgm[SUB + 7] & 0xFF);
        assertEquals(0, dgm[SUB + 8] & 0xFF);
    }

    private static int readLE32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }
}
