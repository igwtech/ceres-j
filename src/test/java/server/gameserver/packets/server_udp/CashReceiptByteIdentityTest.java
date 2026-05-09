package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link CashReceipt} — the 41-byte
 * vendor-buy receipt observed once during a 2026-04-26 retail
 * pcap (UDP S→C reliable {@code 0x03/0x1f → 01 00 25 13 …}).
 *
 * <p>The default {@code CashReceipt(Player)} constructor ships
 * the captured retail bytes verbatim. Pinning that path protects
 * the historical baseline used by the admin-side bisection
 * tooling.
 *
 * <p>The four-argument substitution constructor with its
 * {@code FIELD_*_OFFSET_*} constants is admin-debug code with
 * known inconsistent offsets (some are trailer-relative, some
 * inner-body-relative). Tests for those slot semantics are
 * intentionally absent — they would lock in the inconsistency.
 * Fixing those constants is a separate task once we're sure the
 * cash bisection is no longer in active use.
 */
public class CashReceiptByteIdentityTest {

    private static byte[] datagramBytes(CashReceipt pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x1f", 0x1f, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void retailVerbatimBytesAreByteEqual() {
        // Inner body = `01 00 25 13` (4-byte 0x25/0x13 transactional
        // prefix) + 37-byte retail-captured trailer = 41 bytes.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(
                datagramBytes(new CashReceipt(pl)), 41);
        byte[] expected = {
                // 0x25/0x13 transactional prefix
                0x01, 0x00, 0x25, 0x13,
                // local entity id 3B
                (byte) 0xd2, (byte) 0x2f, 0x04,
                // field A LE32 = 530,915
                (byte) 0xe3, 0x15, 0x08, 0x00,
                // field B LE32 = 51,847,635
                (byte) 0xd3, 0x2f, 0x18, 0x03,
                // sentinel ff ff + LE16(20)
                (byte) 0xff, (byte) 0xff, 0x14, 0x00,
                // LE16(1499)
                (byte) 0xdb, 0x05,
                // field C LE32 = 968,225
                0x21, (byte) 0xc8, 0x0e, 0x00,
                // GUID parts
                0x0e, 0x0e, 0x4d, 0x3d,
                (byte) 0xf7, 0x3e, 0x57, 0x57,
                (byte) 0xd3, 0x44, (byte) 0xa3, (byte) 0x97,
                // field D LE32 = 2,017,617
                0x51, (byte) 0xc9, 0x1e, 0x00
        };
        assertArrayEquals("body must match the 2026-04-26 retail "
                + "vendor-buy bytes verbatim", expected, body);
    }

    @Test
    public void totalDatagramSizeIsFiftyTwoBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 1 (0x1f) + 41 (body) = 52 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(52, datagramBytes(new CashReceipt(pl)).length);
    }

    @Test
    public void prefixIsTransactionalCashSubTagFamily() {
        // The 0x25/0x13 prefix marks this as a transactional-event
        // packet (same family as CashUpdate). Pin the prefix so a
        // refactor that moves CashReceipt to a different sub-tag
        // family fails this test loudly.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(
                datagramBytes(new CashReceipt(pl)), 41);
        assertEquals(0x01, body[0] & 0xFF);
        assertEquals(0x00, body[1] & 0xFF);
        assertEquals(0x25, body[2] & 0xFF);
        assertEquals(0x13, body[3] & 0xFF);
    }
}
