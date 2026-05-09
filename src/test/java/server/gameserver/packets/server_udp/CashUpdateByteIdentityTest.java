package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.net.DatagramPacket;

import org.junit.Before;
import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link CashUpdate} — the
 * authoritative HUD wallet update (UDP S→C reliable
 * {@code 0x03/0x1f → 0x25 0x13 → 0x04}).
 *
 * <p>Reverse-engineered 2026-04-26 against retail pcap and
 * verified byte-identical for two real mob-kill cash
 * deltas. This test pins the 11-byte body so a future
 * refactor of CashUpdate's internal txn counter or sub-tag
 * encoding can't regress the wire bytes.
 *
 * <p>Retail evidence:
 * <pre>
 *   kill 2 (cash 527,652 → 527,729):
 *     03 99 01 1f 01 00 25 13 e8 2f 04 71 0d 08 00
 *   kill 3 (cash 527,729 → 527,782):
 *     03 36 02 1f 01 00 25 13 ed 2f 04 a6 0d 08 00
 * </pre>
 */
public class CashUpdateByteIdentityTest {

    @Before
    public void resetTxnCounter() throws Exception {
        Field f = CashUpdate.class.getDeclaredField("txnCounter");
        f.setAccessible(true);
        // Set so first nextTxn() returns 0x2fe8 — matches retail
        // kill 2's `e8 2f` LE16 txn id.
        f.setInt(null, 0x2fe7);
    }

    private static byte[] datagramBytes(CashUpdate pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP1303):
     *  {@code [0x13][counter LE2][counter+sk LE2][size LE2][0x03][seq LE2][0x1f][body 11B]}
     *  Body starts at offset 11. */
    private static byte[] extractInnerBody(byte[] datagram) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x1f", 0x1f, datagram[10] & 0xFF);
        byte[] body = new byte[11];
        System.arraycopy(datagram, 11, body, 0, 11);
        return body;
    }

    @Test
    public void retailKill2BytesByteEqual() {
        // Reset puts next txn at 0x2fe8 = retail kill 2.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        byte[] body = extractInnerBody(datagramBytes(
                new CashUpdate(pl, 527_729)));

        // Expected body (after the 0x1f sub-opcode):
        //   01 00 25 13 e8 2f 04 71 0d 08 00
        byte[] expected = {
                0x01, 0x00, 0x25, 0x13,
                (byte) 0xe8, 0x2f,            // txn LE16 = 0x2fe8
                0x04,                          // TAG_CASH
                0x71, 0x0d, 0x08, 0x00         // cash LE32 = 527,729
        };
        assertArrayEquals("body must match retail kill 2 bytes",
                expected, body);
    }

    @Test
    public void retailKill3BytesByteEqual() throws Exception {
        // Bump counter to 0x2fec so next nextTxn() returns 0x2fed
        Field f = CashUpdate.class.getDeclaredField("txnCounter");
        f.setAccessible(true);
        f.setInt(null, 0x2fec);

        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        byte[] body = extractInnerBody(datagramBytes(
                new CashUpdate(pl, 527_782)));

        byte[] expected = {
                0x01, 0x00, 0x25, 0x13,
                (byte) 0xed, 0x2f,            // txn LE16 = 0x2fed
                0x04,                          // TAG_CASH
                (byte) 0xa6, 0x0d, 0x08, 0x00  // cash LE32 = 527,782
        };
        assertArrayEquals("body must match retail kill 3 bytes",
                expected, body);
    }

    @Test
    public void txnCounterIncrementsAcrossInstances() throws Exception {
        // Reset to 0; first call → txn=1, second → txn=2.
        Field f = CashUpdate.class.getDeclaredField("txnCounter");
        f.setAccessible(true);
        f.setInt(null, 0);

        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        byte[] b1 = extractInnerBody(datagramBytes(
                new CashUpdate(pl, 1000)));
        byte[] b2 = extractInnerBody(datagramBytes(
                new CashUpdate(pl, 1000)));

        // txn lo byte at offset 4
        assertEquals(1, b1[4] & 0xFF);
        assertEquals(2, b2[4] & 0xFF);
    }

    @Test
    public void cashEncodesLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        byte[] body = extractInnerBody(datagramBytes(
                new CashUpdate(pl, 0x12345678)));
        // cash LE32 at offset 7..10
        assertEquals(0x78, body[7]  & 0xFF);
        assertEquals(0x56, body[8]  & 0xFF);
        assertEquals(0x34, body[9]  & 0xFF);
        assertEquals(0x12, body[10] & 0xFF);
    }

    @Test
    public void totalDatagramSizeIsTwentyTwoBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 1 (0x1f) + 11 (body) = 22 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(22, datagramBytes(
                new CashUpdate(pl, 1000)).length);
    }

    @Test
    public void tagCashConstantIs0x04() {
        // The sub-tag at body offset 6 must be TAG_CASH (0x04)
        // — distinguishes wallet updates from other 0x25/0x13
        // family events (0x0b status, 0x0e marker, etc.).
        assertEquals(0x04, CashUpdate.TAG_CASH);
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                new CashUpdate(pl, 0)));
        assertEquals(0x04, body[6] & 0xFF);
    }
}
