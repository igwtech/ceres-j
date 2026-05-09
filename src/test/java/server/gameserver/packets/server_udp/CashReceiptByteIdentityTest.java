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
 * {@code FIELD_*_OFFSET_*} constants targets one of four
 * candidate LE32 cash fields (used historically for HUD-cash
 * bisection). The constants are now consistent (all
 * trailer-relative, all pointing at the field their comment
 * describes); tests below verify each slot lands at the right
 * 4-byte position and that surrounding bytes stay intact.
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

    /**
     * Trailer offset → wire offset = trailer + 4 (the 4-byte
     * {@code 01 00 25 13} prefix at the start of the inner body).
     */
    private static int wireOffset(int trailerOffset) {
        return 4 + trailerOffset;
    }

    /** Verify a substituted LE32 lands at the expected wire offset
     *  AND the bytes immediately around it stay equal to the
     *  retail verbatim baseline. */
    private void assertSubstitution(int trailerOffset,
                                     int substitutedValue,
                                     byte[] expectedRetailVerbatim) {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                new CashReceipt(pl, trailerOffset, substitutedValue)),
                41);

        int wireOff = wireOffset(trailerOffset);
        assertEquals("substituted byte 0 (LE lo)",
                substitutedValue & 0xFF,
                body[wireOff + 0] & 0xFF);
        assertEquals("substituted byte 1",
                (substitutedValue >> 8) & 0xFF,
                body[wireOff + 1] & 0xFF);
        assertEquals("substituted byte 2",
                (substitutedValue >> 16) & 0xFF,
                body[wireOff + 2] & 0xFF);
        assertEquals("substituted byte 3 (LE hi)",
                (substitutedValue >> 24) & 0xFF,
                body[wireOff + 3] & 0xFF);

        // Every byte outside the 4-byte slot must match retail.
        for (int i = 0; i < 41; i++) {
            if (i >= wireOff && i < wireOff + 4) continue;
            assertEquals("byte " + i + " must remain at retail "
                    + "verbatim value",
                    expectedRetailVerbatim[i] & 0xFF,
                    body[i] & 0xFF);
        }
    }

    @Test
    public void fieldASubstitutionTargetsCorrectSlot() {
        // FIELD_A = trailer offset 3 → wire offset 7 → e3 15 08 00
        // (= 530,915 in retail).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] verbatim = extractInnerBody(
                datagramBytes(new CashReceipt(pl)), 41);
        assertSubstitution(CashReceipt.FIELD_A_OFFSET_530K,
                0xCAFEBABE, verbatim);
    }

    @Test
    public void fieldBSubstitutionTargetsCorrectSlot() {
        // FIELD_B = trailer offset 7 → wire offset 11 → d3 2f 18 03
        // (= 51,847,635 in retail).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] verbatim = extractInnerBody(
                datagramBytes(new CashReceipt(pl)), 41);
        assertSubstitution(CashReceipt.FIELD_B_OFFSET_51M,
                0xDEADBEEF, verbatim);
    }

    @Test
    public void fieldCSubstitutionTargetsCorrectSlot() {
        // FIELD_C = trailer offset 17 → wire offset 21 → 21 c8 0e 00
        // (= 968,225 in retail).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] verbatim = extractInnerBody(
                datagramBytes(new CashReceipt(pl)), 41);
        assertSubstitution(CashReceipt.FIELD_C_OFFSET_968K,
                0x12345678, verbatim);
    }

    @Test
    public void fieldDSubstitutionTargetsCorrectSlot() {
        // FIELD_D = trailer offset 33 → wire offset 37 → 51 c9 1e 00
        // (= 2,017,617 in retail).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] verbatim = extractInnerBody(
                datagramBytes(new CashReceipt(pl)), 41);
        assertSubstitution(CashReceipt.FIELD_D_OFFSET_2M,
                0x87654321, verbatim);
    }
}
