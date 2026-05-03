package server.gameserver.chat;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit + functional tests for {@link Chat3bDecoder}.
 *
 * <p>The functional tests use bytes captured from
 * {@code RETAIL_RETAIL_LONG_PARTY_B_20260503_130343.pcap}. These are
 * the inner bodies of {@code 0x03/0x1f} packets after the 0x13
 * gamedata + 0x03 reliable wrappers have been stripped.
 */
public class Chat3bDecoderTest {

    private static byte[] hex(String h) {
        h = h.replaceAll("\\s+", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i*2, i*2+2), 16);
        }
        return b;
    }

    // ─── Unit tests ──────────────────────────────────────────────────

    @Test
    public void decodesWhisperWithUidAndMessage() {
        // 02 00 3b 04 [d2 86 01 00] "hey\0"
        byte[] body = hex("02 00 3b 04 d2 86 01 00 68 65 79 00");
        Chat3bDecoder.DecodedChat d = Chat3bDecoder.decode(body);
        assertNotNull(d);
        assertEquals(Chat3bDecoder.CHANNEL_WHISPER, d.channel);
        assertEquals(0x000186d2, d.targetUid);
        assertEquals("hey", d.message);
    }

    @Test
    public void decodesTeamChat() {
        // 02 00 3b 03 [00 00 00 00] "Hello team\0"
        byte[] body = hex("02 00 3b 03 00 00 00 00" +
                          "48 65 6c 6c 6f 20 74 65 61 6d 00");
        Chat3bDecoder.DecodedChat d = Chat3bDecoder.decode(body);
        assertEquals(Chat3bDecoder.CHANNEL_TEAM, d.channel);
        assertEquals(0, d.targetUid);
        assertEquals("Hello team", d.message);
    }

    @Test
    public void decodesBuddyChatWithDoubleSpace() {
        // PARTY_B sample: BUDDY chan, "Hey  buddy" with TWO spaces.
        byte[] body = hex("02 00 3b 00 00 00 00 00" +
                          "48 65 79 20 20 62 75 64 64 79 00");
        Chat3bDecoder.DecodedChat d = Chat3bDecoder.decode(body);
        assertEquals(Chat3bDecoder.CHANNEL_BUDDY, d.channel);
        assertEquals("Hey  buddy", d.message);
    }

    @Test
    public void decodesClanChat() {
        byte[] body = hex("02 00 3b 02 00 00 00 00" +
                          "68 65 79 20 63 6c 61 6e 00");
        Chat3bDecoder.DecodedChat d = Chat3bDecoder.decode(body);
        assertEquals(Chat3bDecoder.CHANNEL_CLAN, d.channel);
        assertEquals("hey clan", d.message);
    }

    @Test
    public void rejectsTooShortBody() {
        // Need at least 9 bytes (3 header + 4 uid + 1 byte msg + null).
        assertNull(Chat3bDecoder.decode(new byte[]{0x02, 0x00, 0x3b}));
        assertNull(Chat3bDecoder.decode(null));
    }

    @Test
    public void rejectsWrongTagByte() {
        // Tag byte at offset 2 must be 0x3b.
        byte[] body = hex("02 00 1b 04 d2 86 01 00 68 65 79 00");
        assertNull(Chat3bDecoder.decode(body));
    }

    @Test
    public void messageWithoutTerminatorReadsToEnd() {
        // Some payloads might be sent without a trailing null; we
        // should still return the full ASCII suffix.
        byte[] body = hex("02 00 3b 04 d2 86 01 00 68 65 79");
        Chat3bDecoder.DecodedChat d = Chat3bDecoder.decode(body);
        assertNotNull(d);
        assertEquals("hey", d.message);
    }

    // ─── Functional tests ───────────────────────────────────────────

    @Test
    public void roundtripWhisperFromCapture() {
        // PARTY_B C->S 0x03/0x1f body samples for whisper:
        //   "Dra Moni" was the recipient (uid 0x000186d2 from PARTY_A).
        //   Norman Gates → Dra Moni, message "hey".
        // 0x13 gamedata + 0x03 reliable wrappers strip to inner:
        //   1f 02 00 3b 04 d2 86 01 00 'h' 'e' 'y' 00
        // Below is just the 0x03/0x1f body (after 1f tag byte):
        byte[] inner = hex("02 00 3b 04 d2 86 01 00 68 65 79 00");
        Chat3bDecoder.DecodedChat d = Chat3bDecoder.decode(inner);
        assertEquals(Chat3bDecoder.CHANNEL_WHISPER, d.channel);
        assertEquals(0x000186d2, d.targetUid);
        assertEquals("hey", d.message);
    }
}
