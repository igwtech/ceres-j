package server.gameserver.packets.server_tcp;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import server.networktools.PacketBuilderTCP;

/**
 * Byte-identity tests for the request-result pair: {@link
 * RequestSuccess} and {@link RequestFailed}. Both use opcode
 * {@code 0x83 0x86} but vary in the success/fail status word
 * and presence of an ASCII reason string.
 *
 * <h3>Wire layout</h3>
 *
 * <pre>
 *   RequestSuccess: 83 86 [01 00] [00 00] [00]            (7B body)
 *   RequestFailed:  83 86 [00 00] [len LE2] [text...] [00 00]  (variable)
 * </pre>
 *
 * <p>Note: the modern flow shipped {@link CharOpAck} which uses
 * the same {@code 0x86} opcode with refined status semantics.
 * RequestSuccess / RequestFailed remain for legacy paths.
 */
public class RequestSuccessFailedByteIdentityTest {

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

    @Test
    public void requestSuccessExactBytes() {
        byte[] body = extractBody(wireBytes(new RequestSuccess()), 7);
        // Catalog body for success: 83 86 01 00 00 00 00
        byte[] expected = {
                (byte) 0x83, (byte) 0x86,
                0x01, 0x00,    // status = 1 (success)
                0x00, 0x00,    // message length = 0
                0x00            // null
        };
        assertArrayEquals(expected, body);
    }

    @Test
    public void requestFailedHelloWorld() {
        // RequestFailed("Error 42") → length field = 9, text bytes,
        // null, extra trailing 0.
        String msg = "Error 42";
        byte[] body = extractBody(
                wireBytes(new RequestFailed(msg)),
                2 + 2 + 2 + msg.length() + 1 + 1);

        // [0..1] opcode 83 86
        assertEquals(0x83, body[0] & 0xFF);
        assertEquals(0x86, body[1] & 0xFF);
        // [2..3] status = 0 (failed)
        assertEquals(0x00, body[2] & 0xFF);
        assertEquals(0x00, body[3] & 0xFF);
        // [4..5] message length LE16 = msg.length() + 1 = 9
        assertEquals(0x09, body[4] & 0xFF);
        assertEquals(0x00, body[5] & 0xFF);
        // [6..13] message ASCII
        byte[] textBytes = msg.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < textBytes.length; i++) {
            assertEquals("text byte " + i,
                    textBytes[i], body[6 + i]);
        }
        // [14] first trailing 0
        assertEquals(0x00, body[14] & 0xFF);
        // [15] second trailing 0
        assertEquals(0x00, body[15] & 0xFF);
    }

    @Test
    public void requestSuccessTotalSize() {
        // 3-byte FE frame + 7-byte body = 10 bytes
        assertEquals(10, new RequestSuccess().size());
    }

    @Test
    public void requestFailedTotalSizeForKnownMessage() {
        // 3 (FE frame) + 6 (header: opcode + status + length) +
        //   msg.length() + 2 (trailing nulls) = 11 + msg.length()
        // Empty message → 11 bytes total.
        assertEquals(11, new RequestFailed("").size());
        assertEquals(15, new RequestFailed("test").size());
    }

    @Test
    public void requestSuccessIsConstantBytes() {
        // RequestSuccess takes no arguments — every instance must
        // produce identical wire bytes.
        byte[] a = wireBytes(new RequestSuccess());
        byte[] b = wireBytes(new RequestSuccess());
        assertArrayEquals(a, b);
    }

    @Test
    public void successAndFailedShareOpcodeButDifferAtStatusWord() {
        byte[] success = wireBytes(new RequestSuccess());
        byte[] failed = wireBytes(new RequestFailed(""));
        // Opcode 83 86 at body offset 0..1 (wire offset 3..4)
        assertEquals(success[3], failed[3]);   // 0x83
        assertEquals(success[4], failed[4]);   // 0x86
        // Status word at body offset 2..3 (wire offset 5..6)
        assertEquals("success status word lo = 1",
                0x01, success[5] & 0xFF);
        assertEquals("failed status word lo = 0",
                0x00, failed[5] & 0xFF);
    }
}
