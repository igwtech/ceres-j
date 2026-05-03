package server.gameserver.packets.server_tcp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Functional test: verify {@link CharOpAck} produces wire output that
 * is byte-for-byte identical to actual retail server replies captured
 * in our pcaps.
 *
 * <p>Retail bytes are taken from the analyzer-generated timelines:
 * <ul>
 *   <li>{@code RETAIL_CREATION_LEVELING_LONG_20260502_160841} — preview
 *       at t=40.24s and commit at t=90.93s.</li>
 *   <li>{@code RETAIL_RETAIL_CHARDEL_SUBWAY_20260503_132639} — delete
 *       error at t=271.92s and delete success at t=300.23s.</li>
 * </ul>
 *
 * <p>This is the Phase 1 acceptance test from the implementation plan:
 * "every server reply byte-equal except session/UID fields". For
 * {@code 0x8386} there ARE no session/UID fields — the whole 7-byte
 * (or 6+ASCII for errors) body is deterministic given the inputs.
 */
public class CharOpAckFunctionalTest {

    /** Strip the FE+len framing to get just the body bytes. */
    private static byte[] body(byte[] framed) {
        int len = (framed[1] & 0xff) | ((framed[2] & 0xff) << 8);
        byte[] out = new byte[len];
        System.arraycopy(framed, 3, out, 0, len);
        return out;
    }

    private static byte[] hex(String h) {
        h = h.replaceAll("\\s+", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i*2, i*2+2), 16);
        }
        return b;
    }

    @Test
    public void matchesRetailPreviewAck() {
        // From CREATION_LEVELING_LONG @ t=40.24s — first AT_CHARSELECT
        // name-check returned this preview-ack body.
        byte[] retailBody = hex("8386010000003d");
        byte[] ours       = body(CharOpAck.previewAck().getData());
        assertArrayEquals("preview-ack must match retail bytes", retailBody, ours);
    }

    @Test
    public void matchesRetailCommitSuccess() {
        // From CREATION_LEVELING_LONG @ t=90.93s — character commit
        // returned this success body.
        byte[] retailBody = hex("83860100000000");
        byte[] ours       = body(CharOpAck.commitSuccess().getData());
        assertArrayEquals("commit-success must match retail bytes", retailBody, ours);
    }

    @Test
    public void matchesRetailDeleteSuccess() {
        // From CHARDEL_SUBWAY @ t=300.23s — second delete attempt
        // (after the first was rejected) succeeded with this body.
        byte[] retailBody = hex("83860100000005");
        byte[] ours       = body(CharOpAck.deleteSuccess().getData());
        assertArrayEquals("delete-success must match retail bytes", retailBody, ours);
    }

    @Test
    public void matchesRetailErrorShape() {
        // From CHARDEL_SUBWAY @ t=271.92s — first delete attempt
        // returned a 48-byte body starting with these bytes (full
        // body truncated to 16-byte sample in the timeline):
        //   "8386 0600 2900 5573657220616c726561…"
        // Decoded ASCII at offset 6 starts "User alrea…", LE2 length
        // at offset 4-5 = 0x0029 = 41 chars.
        //
        // We don't know the exact retail message text, but we can
        // verify our builder produces the SHAPE: 0x83, 0x86, 0x06,
        // 0x00, [LE2 len], [ASCII msg of that length].
        String reasonGuess = "User already deleting that character slot."; // 42 chars
        byte[] ours = body(CharOpAck.error(reasonGuess).getData());

        assertEquals((byte)0x83, ours[0]);
        assertEquals((byte)0x86, ours[1]);
        assertEquals("error tag lo (matches retail 0x06)", (byte)0x06, ours[2]);
        assertEquals("error tag hi (matches retail 0x00)", (byte)0x00, ours[3]);
        // Length LE2 must equal ASCII byte count (no null terminator).
        int reportedLen = (ours[4] & 0xff) | ((ours[5] & 0xff) << 8);
        assertEquals(reasonGuess.length(), reportedLen);
        // Body length = 6 header bytes + ASCII.
        assertEquals(6 + reasonGuess.length(), ours.length);
        // ASCII payload exactly matches.
        String decoded = new String(ours, 6, reportedLen);
        assertEquals(reasonGuess, decoded);
    }

    @Test
    public void retailErrorPrefixMatchesUserAlrea() {
        // Spot-check: the retail capture body began with these bytes,
        // matching ASCII "User alrea". Our error("User already…")
        // builder must produce the same prefix.
        byte[] ours = body(CharOpAck.error("User already deleting").getData());
        // Compare bytes 0..15 (header + first 10 ASCII chars) against
        // what the retail capture observed:
        //   83 86 06 00 [len] [len] U s e r _ a l r e a
        byte[] retailPrefix = hex("83 86 06 00 15 00 5573657220616c72656164");
        // Note retail-observed length was 0x29 (=41) from the rejected
        // long message; our shorter test message is 21 chars (0x15).
        // We assert the prefix shape only.
        for (int i = 0; i < 4; i++) {
            assertEquals("byte " + i, retailPrefix[i], ours[i]);
        }
        // ASCII bytes 6..15 = "User alrea".
        assertEquals('U', ours[6]);
        assertEquals('s', ours[7]);
        assertEquals('e', ours[8]);
        assertEquals('r', ours[9]);
        assertEquals(' ', ours[10]);
        assertEquals('a', ours[11]);
        assertEquals('l', ours[12]);
        assertEquals('r', ours[13]);
        assertEquals('e', ours[14]);
        assertEquals('a', ours[15]);
    }

    @Test
    public void allSuccessVariantsAreSevenBytes() {
        // Phase 1 invariant: success/preview/delete are all the same
        // 7-byte length. The status byte at offset 6 is the only
        // differentiator. This is what makes "a single CharOpAck
        // class with 3 factories" the right modeling — they're not
        // 3 different packets, they're 1 packet with a status byte.
        assertEquals(7, body(CharOpAck.commitSuccess().getData()).length);
        assertEquals(7, body(CharOpAck.previewAck().getData()).length);
        assertEquals(7, body(CharOpAck.deleteSuccess().getData()).length);
    }
}
