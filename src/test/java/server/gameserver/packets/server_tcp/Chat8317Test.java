package server.gameserver.packets.server_tcp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit + functional tests for {@link Chat8317}.
 *
 * <p>Functional tests use real bytes from the
 * {@code RETAIL_RETAIL_LONG_PARTY_B_20260503_130343.pcap} capture's
 * decoded timeline. Five chat reflections were observed in that
 * capture (whisper, team, buddy, plus inbound replies) — each is
 * asserted byte-equal here.
 */
public class Chat8317Test {

    /** Strip FE+LE2 framing to get the body. */
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

    // ─── Unit tests: byte layout ─────────────────────────────────────

    @Test
    public void layoutMatchesSpec() {
        Chat8317 pkt = new Chat8317(0x00018678, "Norman Gates",
                Chat8317.CHANNEL_WHISPER, "hey");
        byte[] b = body(pkt.getData());
        // Expected: 83 17 [78 86 01 00] [0c] [04] [00] "Norman Gates" "hey"
        assertEquals((byte)0x83, b[0]);
        assertEquals((byte)0x17, b[1]);
        // sender_uid LE4
        assertEquals((byte)0x78, b[2]);
        assertEquals((byte)0x86, b[3]);
        assertEquals((byte)0x01, b[4]);
        assertEquals((byte)0x00, b[5]);
        // name_len, channel, separator
        assertEquals((byte)0x0c, b[6]);
        assertEquals((byte)0x04, b[7]);
        assertEquals((byte)0x00, b[8]);
        // ASCII payload
        assertEquals('N', b[9]);
        assertEquals('o', b[10]);
        assertEquals('h', b[21]);
        assertEquals('e', b[22]);
        assertEquals('y', b[23]);
        // Total body = 9 hdr + 12 name + 3 msg = 24
        assertEquals(24, b.length);
    }

    @Test
    public void emptyMessageIsAllowed() {
        Chat8317 pkt = new Chat8317(0, "Bob", Chat8317.CHANNEL_TEAM, "");
        byte[] b = body(pkt.getData());
        // 9 hdr + 3 name + 0 msg = 12
        assertEquals(12, b.length);
        assertEquals(3, b[6]); // name_len = 3
    }

    @Test
    public void nameLengthOver255Throws() {
        char[] chars = new char[300];
        java.util.Arrays.fill(chars, 'X');
        String tooLong = new String(chars);
        try {
            new Chat8317(0, tooLong, Chat8317.CHANNEL_BUDDY, "msg");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ok) { /* good */ }
    }

    @Test
    public void nullNameAndMessageDefaultToEmpty() {
        Chat8317 pkt = new Chat8317(0, null, Chat8317.CHANNEL_CLAN, null);
        byte[] b = body(pkt.getData());
        assertEquals("9 hdr + 0 name + 0 msg", 9, b.length);
        assertEquals(0, b[6] & 0xff); // name_len = 0
    }

    @Test
    public void channelByteIsTruncatedToOneByte() {
        Chat8317 pkt = new Chat8317(0, "X", 0x12345, "y");
        byte[] b = body(pkt.getData());
        // 0x12345 & 0xff = 0x45
        assertEquals((byte)0x45, b[7]);
    }

    @Test
    public void allChannelConstantsRoundtrip() {
        for (byte c : new byte[]{
                Chat8317.CHANNEL_BUDDY,
                Chat8317.CHANNEL_CLAN,
                Chat8317.CHANNEL_TEAM,
                Chat8317.CHANNEL_WHISPER}) {
            byte[] b = body(new Chat8317(0, "x", c, "y").getData());
            assertEquals(c, b[7]);
        }
    }

    // ─── Functional tests: byte-equal against retail capture ────────

    @Test
    public void retailWhisperReflectionMatches() {
        // PARTY_B @ t=992.02s — first whisper "hey" reflected back.
        // Body: 8317 [78860100] 0c 04 00 "Norman Gates" "hey"
        // = 2+4+3+12+3 = 24 bytes.
        byte[] retail = hex(
                "83 17" +
                "78 86 01 00" +
                "0c 04 00" +
                "4e 6f 72 6d 61 6e 20 47 61 74 65 73" +
                "68 65 79");
        Chat8317 ours = new Chat8317(0x00018678, "Norman Gates",
                Chat8317.CHANNEL_WHISPER, "hey");
        byte[] mine = body(ours.getData());
        assertArrayEquals("retail whisper byte-equal", retail, mine);
    }

    @Test
    public void retailTeamChatReflectionMatches() {
        // PARTY_B @ t=1067.67s — "Hello team" reflected via team chan.
        // Body: 8317 [78860100] 0c 03 00 "Norman Gates" "Hello team"
        // = 2+4+3+12+10 = 31 bytes.
        byte[] retail = hex(
                "83 17" +
                "78 86 01 00" +
                "0c 03 00" +
                // "Norman Gates" 12 chars
                "4e 6f 72 6d 61 6e 20 47 61 74 65 73" +
                // "Hello team" 10 chars
                "48 65 6c 6c 6f 20 74 65 61 6d");
        Chat8317 ours = new Chat8317(0x00018678, "Norman Gates",
                Chat8317.CHANNEL_TEAM, "Hello team");
        byte[] mine = body(ours.getData());
        assertArrayEquals("retail team-chat byte-equal", retail, mine);
    }

    @Test
    public void retailBuddyChatReflectionMatches() {
        // PARTY_B @ t=1099.47s — "Hey  buddy" via buddy chan.
        // Note retail has TWO spaces between "Hey" and "buddy".
        // Body: 8317 [78860100] 0c 00 00 "Norman Gates" "Hey  buddy"
        // = 2+4+3+12+10 = 31 bytes.
        byte[] retail = hex(
                "83 17" +
                "78 86 01 00" +
                "0c 00 00" +
                "4e 6f 72 6d 61 6e 20 47 61 74 65 73" +
                // "Hey  buddy" — two spaces
                "48 65 79 20 20 62 75 64 64 79");
        Chat8317 ours = new Chat8317(0x00018678, "Norman Gates",
                Chat8317.CHANNEL_BUDDY, "Hey  buddy");
        byte[] mine = body(ours.getData());
        assertArrayEquals("retail buddy-chat byte-equal", retail, mine);
    }

    @Test
    public void retailInboundFromPartyAMatches() {
        // PARTY_B @ t=1036.14s — inbound whisper FROM Dra Moni
        // (Party A, uid 0x000186d2) saying "hey back".
        // Body: 8317 [d2860100] 08 04 00 "Dra Moni" "hey back"
        // = 2+4+3+8+8 = 25 bytes.
        byte[] retail = hex(
                "83 17" +
                "d2 86 01 00" +
                "08 04 00" +
                // "Dra Moni" 8 chars
                "44 72 61 20 4d 6f 6e 69" +
                // "hey back" 8 chars
                "68 65 79 20 62 61 63 6b");
        Chat8317 ours = new Chat8317(0x000186d2, "Dra Moni",
                Chat8317.CHANNEL_WHISPER, "hey back");
        byte[] mine = body(ours.getData());
        assertArrayEquals("inbound whisper byte-equal", retail, mine);
    }
}
