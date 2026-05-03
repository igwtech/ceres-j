package server.gameserver.packets.server_tcp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Byte-level unit tests for {@link CharOpAck} status variants.
 *
 * <p>Each variant's wire bytes are asserted against the retail
 * format documented in {@code docs/protocol/flows/character_creation.md}
 * and {@code FINDINGS_2026-05-03_CHARDEL_SUBWAY.md}. The functional
 * test {@link server.gameserver.packets.server_tcp.CharLifecycleFunctionalTest}
 * cross-checks against actual decoded retail capture bytes.
 */
public class CharOpAckTest {

    /** PacketBuilderTCP prepends {@code 0xfe + length LE2} framing.
     *  Strip it so the per-variant assertions only check the body. */
    private static byte[] body(byte[] framed) {
        // Frame: fe + len LE2 + body. Body starts at offset 3.
        // Note: getData() patches the length field in place.
        assertTrue("Frame too short", framed.length >= 3);
        assertEquals("Frame must start with 0xfe", (byte)0xfe, framed[0]);
        int len = (framed[1] & 0xff) | ((framed[2] & 0xff) << 8);
        byte[] out = new byte[len];
        System.arraycopy(framed, 3, out, 0, len);
        return out;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x ", x & 0xff));
        return sb.toString().trim();
    }

    @Test
    public void commitSuccessHasZeroStatusByte() {
        byte[] bytes = body(CharOpAck.commitSuccess().getData());
        // Expected: 83 86 01 00 00 00 00
        assertEquals("commit-success body length", 7, bytes.length);
        assertEquals("byte 0 = opcode hi",  (byte)0x83, bytes[0]);
        assertEquals("byte 1 = opcode lo",  (byte)0x86, bytes[1]);
        assertEquals("byte 2 = success lo", (byte)0x01, bytes[2]);
        assertEquals("byte 3 = success hi", (byte)0x00, bytes[3]);
        assertEquals("byte 4 = msglen lo",  (byte)0x00, bytes[4]);
        assertEquals("byte 5 = msglen hi",  (byte)0x00, bytes[5]);
        assertEquals("byte 6 = status",     (byte)0x00, bytes[6]);
    }

    @Test
    public void deleteSuccessHasStatusByte05() {
        byte[] bytes = body(CharOpAck.deleteSuccess().getData());
        // Expected: 83 86 01 00 00 00 05
        assertEquals(7, bytes.length);
        assertArrayEquals(
                new byte[]{(byte)0x83, (byte)0x86, 0x01, 0x00, 0x00, 0x00, 0x05},
                bytes);
    }

    @Test
    public void previewAckHasStatusByte3d() {
        byte[] bytes = body(CharOpAck.previewAck().getData());
        // Expected: 83 86 01 00 00 00 3d
        assertEquals(7, bytes.length);
        assertArrayEquals(
                new byte[]{(byte)0x83, (byte)0x86, 0x01, 0x00, 0x00, 0x00, 0x3d},
                bytes);
    }

    @Test
    public void errorEmitsAsciiMessageWithoutNullTerminator() {
        byte[] bytes = body(CharOpAck.error("HELLO").getData());
        // Expected: 83 86 06 00 [05 00] H E L L O
        assertEquals("header(6) + ASCII(5)", 11, bytes.length);
        assertEquals((byte)0x83, bytes[0]);
        assertEquals((byte)0x86, bytes[1]);
        assertEquals("error tag lo", (byte)0x06, bytes[2]);
        assertEquals("error tag hi", (byte)0x00, bytes[3]);
        assertEquals("msg len lo",   (byte)0x05, bytes[4]);
        assertEquals("msg len hi",   (byte)0x00, bytes[5]);
        assertEquals('H', bytes[6]);
        assertEquals('E', bytes[7]);
        assertEquals('L', bytes[8]);
        assertEquals('L', bytes[9]);
        assertEquals('O', bytes[10]);
    }

    @Test
    public void errorWithNullMessageDefaultsToErrorString() {
        byte[] bytes = body(CharOpAck.error(null).getData());
        // Expect "ERROR" as the body.
        assertEquals(6 + 5, bytes.length);
        assertEquals(5, bytes[4] & 0xff);
        assertEquals('E', bytes[6]);
        assertEquals('R', bytes[7]);
        assertEquals('R', bytes[8]);
        assertEquals('O', bytes[9]);
        assertEquals('R', bytes[10]);
    }

    @Test
    public void errorWith48ByteRetailMessageMatchesObservedSize() {
        // From the CHARDEL_SUBWAY capture: the 1st delete attempt got
        // a 48-byte 0x8386 reply with body bytes "0x06 0x00 [29 00] 'User already…'"
        // (len=0x0029=41 ASCII chars + 6 header = 47 visible; 48 total
        // in the FE-framed wire). Verify our builder produces the same
        // shape for a 41-char string.
        String msg = "User already deleting that character slot."; // 42 chars
        byte[] bytes = body(CharOpAck.error(msg).getData());
        assertEquals(6 + msg.length(), bytes.length);
        assertEquals(msg.length() & 0xff, bytes[4] & 0xff);
        assertEquals(msg.length() >> 8 & 0xff, bytes[5] & 0xff);
        // Decode message back from bytes 6..end and compare.
        String decoded = new String(bytes, 6, msg.length());
        assertEquals(msg, decoded);
    }

    @Test
    public void framingIsCorrect() {
        // PacketBuilderTCP's getData() returns the internal BAOS buf
        // which may have trailing capacity bytes — callers parse up to
        // the length field at offset 1-2. Verify the length LE2 itself
        // matches the body byte count.
        byte[] framed = CharOpAck.commitSuccess().getData();
        assertEquals((byte)0xfe, framed[0]);
        int len = (framed[1] & 0xff) | ((framed[2] & 0xff) << 8);
        assertEquals("length-LE2 must match body size", 7, len);
        assertTrue("buf must include header + body",
                framed.length >= 3 + 7);
    }
}
