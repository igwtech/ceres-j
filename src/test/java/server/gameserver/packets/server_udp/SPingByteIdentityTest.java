package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link SPing} — server response to
 * client {@code 0x0b} CPing keepalive (UDP S→C raw {@code 0x0b}).
 *
 * <p>Verified 2026-05-09 against retail HANNIBAL pcap: SPing is
 * a 9-byte RAW datagram, NO 0x13 outer wrapper.
 *
 * <p>4,939 retail samples across 17/17 captures, fixed 9-byte
 * body. Sample retail bytes:
 * <pre>
 *   0b cb6e6900 600ca704        server_time=0x00696ecb,
 *                                client_time=0xa7040c60
 *   0b b8692d00 d7c80205        server_time=0x002d69b8,
 *                                client_time=0x0502c8d7
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

    @Test
    public void clientTimeEchoEncodesLittleEndian() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);

        // Construct with a known client time (catalog sample
        // last 4 bytes: 0xd7 c8 02 05 = 0x0502c8d7)
        byte[] dgm = datagramBytes(new SPing(0x0502_C8D7, pl));

        assertEquals("opcode 0x0b", 0x0b, dgm[0] & 0xFF);
        // client_time at offset 5..8 LE32
        assertEquals(0xD7, dgm[5] & 0xFF);
        assertEquals(0xC8, dgm[6] & 0xFF);
        assertEquals(0x02, dgm[7] & 0xFF);
        assertEquals(0x05, dgm[8] & 0xFF);
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

        int t1 = (dgm1[1] & 0xFF) | ((dgm1[2] & 0xFF) << 8)
                | ((dgm1[3] & 0xFF) << 16)
                | ((dgm1[4] & 0xFF) << 24);
        int t2 = (dgm2[1] & 0xFF) | ((dgm2[2] & 0xFF) << 8)
                | ((dgm2[3] & 0xFF) << 16)
                | ((dgm2[4] & 0xFF) << 24);

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

        long time = ((long)(dgm[1] & 0xFF))
                | ((long)(dgm[2] & 0xFF) << 8)
                | ((long)(dgm[3] & 0xFF) << 16)
                | ((long)(dgm[4] & 0xFF) << 24);
        assertTrue("server_time within 6-day cycle range "
                + "(got " + time + ")",
                time > 0 && time < 6L * 24 * 60 * 1000 + 100);
    }

    @Test
    public void totalDatagramSizeIsNineBytesRaw() {
        // 1 (0x0b) + 4 (server_time) + 4 (client_time) = 9 bytes.
        // Verified 2026-05-09 against HANNIBAL pcap. NO 0x13
        // outer wrapper — that was the previous bug, surfaced
        // by the pcap-replay harness at step 8.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        byte[] dgm = datagramBytes(new SPing(0, pl));
        assertEquals("CPing reply is RAW 9B (no 0x13 wrapper)",
                9, dgm.length);
        assertEquals("first byte is the 0x0b opcode",
                0x0b, dgm[0] & 0xFF);
    }

    @Test
    public void zeroClientTimeEncodesAsAllZeros() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        byte[] dgm = datagramBytes(new SPing(0, pl));
        assertEquals(0, dgm[5] & 0xFF);
        assertEquals(0, dgm[6] & 0xFF);
        assertEquals(0, dgm[7] & 0xFF);
        assertEquals(0, dgm[8] & 0xFF);
    }
}
