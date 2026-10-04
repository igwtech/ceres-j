package server.gameserver.packets.server_tcp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

import server.database.accounts.Account;
import server.testtools.BytesIdenticalAssertion;

/**
 * Byte-for-bit retail parity pin for {@link AuthAck} (TCP S->C 0x8381).
 *
 * <p>Retail evidence: 50 hits across 17/17 captures, fixed 31-byte body.
 * See {@code docs/protocol/packets/tcp_s2c_8381.md}. The packet is the
 * server's reply to the client's 0x8480 Auth and carries the account id
 * plus the client's own 18-byte password ciphertext echoed back (retail
 * pcap nc2_ceres_DUNGEON_PORTAL_20260523: Auth ciphertext
 * {@code c29999f9…4459} → AuthAck session_data {@code c29999f9…4459}).
 * Without this exact 31-byte shape the NCE 2.5.x client never leaves
 * "Receive 0 Buffer".
 *
 * <p>The trailer byte differs between retail captures (0x00, 0x2d, 0xad)
 * with no known rule; Ceres-J emits 0x00, so byte-equality with retail is
 * pinned for offsets 0..29.
 */
public class AuthAckByteIdentityTest {

    private static final String CATALOG_KEY = "tcp_s2c_8381";

    /** Slice the bytes the TCP send-path will write to the socket. */
    private static byte[] wireBytes(AuthAck pkt) {
        byte[] data = pkt.getData();
        int n = pkt.size();
        byte[] sliced = new byte[n];
        System.arraycopy(data, 0, sliced, 0, n);
        return sliced;
    }

    /** Strip the {@code fe LL LL} envelope so the result aligns with the
     *  body-only retail samples stored in {@code packets.json}. */
    private static byte[] body(byte[] framed) {
        assertTrue("frame too short", framed.length >= 3);
        assertEquals("frame must start with 0xfe", (byte) 0xfe, framed[0]);
        int len = (framed[1] & 0xff) | ((framed[2] & 0xff) << 8);
        assertEquals("framed length matches wire size",
                framed.length, 3 + len);
        byte[] out = new byte[len];
        System.arraycopy(framed, 3, out, 0, len);
        return out;
    }

    /** Retail Auth password ciphertext (msn2wolf / 9-char password). */
    private static final byte[] RETAIL_CIPHER =
            hexBytes("c29999f9b1b959e973a94b39b7054e994459");

    private static byte[] hexBytes(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    private static AuthAck ack(Account ua) {
        return new AuthAck(ua, RETAIL_CIPHER);
    }

    private static Account accountWithId(int id) {
        Account a = new Account(id);
        a.setUsername("test");
        a.setPassword("test");
        return a;
    }

    @Test
    public void wireSizeMatchesRetailFixed31() {
        byte[] wire = wireBytes(ack(accountWithId(0x17ebd)));
        // 3-byte fe-frame + 31-byte body = 34 bytes total on socket.
        assertEquals("expected 34 framed wire bytes", 34, wire.length);
        byte[] payload = body(wire);
        assertEquals("retail body is fixed 31 bytes",
                31, payload.length);
    }

    @Test
    public void opcodeBytesAreAt8381() {
        byte[] payload = body(wireBytes(ack(accountWithId(1))));
        // Catalog stores body-only samples, so opcode is at body offset 0-1.
        // The task description's "offset 3" refers to the framed wire view
        // (after fe + LE16 length) — verify both views.
        assertEquals("body[0] = opcode hi",
                (byte) 0x83, payload[0]);
        assertEquals("body[1] = opcode lo",
                (byte) 0x81, payload[1]);

        byte[] framed = wireBytes(ack(accountWithId(1)));
        assertEquals("framed[3] = opcode hi",
                (byte) 0x83, framed[3]);
        assertEquals("framed[4] = opcode lo",
                (byte) 0x81, framed[4]);
    }

    @Test
    public void accountIdIsLittleEndianAtOffset2() {
        // 0x17ebd is one of the retail account ids (sample #1, #2).
        byte[] body = body(wireBytes(ack(accountWithId(0x17ebd))));
        assertEquals("acct[0] LE32", (byte) 0xbd, body[2]);
        assertEquals("acct[1] LE32", (byte) 0x7e, body[3]);
        assertEquals("acct[2] LE32", (byte) 0x01, body[4]);
        assertEquals("acct[3] LE32", (byte) 0x00, body[5]);

        // 0xa2b0 (retail sample #3): only the low two bytes are non-zero.
        byte[] body2 = body(wireBytes(ack(accountWithId(0xa2b0))));
        assertEquals((byte) 0xb0, body2[2]);
        assertEquals((byte) 0xa2, body2[3]);
        assertEquals((byte) 0x00, body2[4]);
        assertEquals((byte) 0x00, body2[5]);
    }

    @Test
    public void reservedFieldIsFourZeroBytesAt6Through9() {
        byte[] body = body(wireBytes(ack(accountWithId(0x12345678))));
        for (int i = 6; i <= 9; i++) {
            assertEquals("reserved[" + i + "] must be zero",
                    0, body[i]);
        }
    }

    @Test
    public void sessionDataLengthIs18LE16AtOffset10() {
        byte[] body = body(wireBytes(ack(accountWithId(1))));
        // LE16: low byte at offset 10, high byte at offset 11.
        assertEquals("len lo (=18)", (byte) 0x12, body[10]);
        assertEquals("len hi", (byte) 0x00, body[11]);
        int len = (body[10] & 0xff) | ((body[11] & 0xff) << 8);
        assertEquals("session_data length = 18", 18, len);
    }

    @Test
    public void sessionDataEchoesThePasswordCipherAtOffsets12Through29() {
        byte[] body = body(wireBytes(ack(accountWithId(1))));
        for (int i = 12; i <= 29; i++) {
            assertEquals("session_data[" + (i - 12) + "] @ off " + i,
                    RETAIL_CIPHER[i - 12], body[i]);
        }
    }

    @Test
    public void sessionDataLengthFollowsTheCipherLength() {
        byte[] body = body(wireBytes(new AuthAck(accountWithId(1),
                new byte[16])));
        assertEquals("len = 16 for an 8-char password", 16,
                (body[10] & 0xff) | ((body[11] & 0xff) << 8));
        assertEquals(29, body.length);
    }

    @Test
    public void trailerByteSitsAtOffset30() {
        byte[] body = body(wireBytes(ack(accountWithId(1))));
        assertEquals("body has a 31st byte", 31, body.length);
        assertEquals("trailer @ off 30 (Ceres-J emits 0)",
                0, body[30]);
    }

    @Test
    public void multipleConstructionsWithSameAccountAreIdentical() {
        // The constructor is deterministic given the Account id —
        // catches accidental reliance on shared mutable state.
        Account ua = accountWithId(0x17ebd);
        byte[] a = wireBytes(ack(ua));
        byte[] b = wireBytes(ack(ua));
        assertArrayEquals(
                "two AuthAck instances for the same account must "
                        + "serialise to identical bytes",
                a, b);
    }

    @Test
    public void retailSamplesAllConformToTheStructuralLayout() {
        // For every sample in the catalog, verify the structural offsets
        // we rely on. session_data and trailer vary across captures, so
        // they're not pinned to specific values — only the shape is.
        List<byte[]> samples =
                BytesIdenticalAssertion.loadRetailSamples(CATALOG_KEY);
        assertTrue("expected retail samples for " + CATALOG_KEY,
                !samples.isEmpty());
        for (int i = 0; i < samples.size(); i++) {
            byte[] s = samples.get(i);
            String tag = "sample #" + i + ": " + toHex(s);
            assertEquals(tag + " size", 31, s.length);
            assertEquals(tag + " opcode hi", (byte) 0x83, s[0]);
            assertEquals(tag + " opcode lo", (byte) 0x81, s[1]);
            for (int j = 6; j <= 9; j++) {
                assertEquals(tag + " reserved[" + j + "]",
                        0, s[j]);
            }
            int len = (s[10] & 0xff) | ((s[11] & 0xff) << 8);
            assertEquals(tag + " session_data length", 18, len);
        }
    }

    @Test
    public void byteEqualToRetailPcapPairExceptTrailer() {
        // Retail AuthAck for account 0xa2b0 answering the Auth that
        // carried RETAIL_CIPHER (trailer 0xad there; Ceres-J emits 0x00).
        byte[] retail = hexBytes("8381b0a20000000000001200"
                + "c29999f9b1b959e973a94b39b7054e994459ad");
        byte[] actual = body(wireBytes(ack(accountWithId(0xa2b0))));
        assertEquals(retail.length, actual.length);
        for (int i = 0; i < 30; i++) {
            assertEquals("byte " + i + " expected=" + toHex(retail)
                    + " actual=" + toHex(actual), retail[i], actual[i]);
        }
        assertEquals("Ceres-J trailer", 0, actual[30]);
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", b[i] & 0xff));
        }
        return sb.toString();
    }
}
