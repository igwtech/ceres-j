package server.gameserver.packets.server_tcp;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

import server.gameserver.team.TeamEventDecoder;

/**
 * Unit + functional tests for {@link TeamEvent8388}.
 *
 * <p>Three retail fixtures (the only three samples in the catalog
 * evidence for {@code TCP S->C 0x8388}) drive the byte-equal
 * verification. Each test re-builds the packet from the inferred
 * field values and checks the resulting wire bytes match the
 * captured sample exactly (after stripping the FE-frame header).
 *
 * <p>Retail samples (post-FE-frame, opcode-onwards):
 *
 * <pre>
 *   #1: 8388 d2860100 41000000 04000000 d2860100
 *   #2: 8388 78860100 42000000 04000000 78860100
 *   #3: 8388 d2860100 43000000 09000000 d2860100 01 d2860100
 * </pre>
 */
public class TeamEvent8388Test {

    private static byte[] hex(String h) {
        h = h.replaceAll("\\s+", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    /** Strip the FE-frame header (FE + LE16 size) and return the
     *  body slice the caller can compare against retail evidence. */
    private static byte[] body(TeamEvent8388 pkt) {
        byte[] data = pkt.getData();
        // PacketBuilderTCP returns the internal buffer; size is the
        // committed count.
        byte[] sized = Arrays.copyOf(data, pkt.size());
        byte[] stripped = TeamEventDecoder.stripFrame(sized);
        assertNotNull(stripped);
        return stripped;
    }

    // ─── Byte-equal vs retail samples ───────────────────────────────

    @Test
    public void selfEvent41ByteEqualsRetailSample() {
        // Sample #1: target=Norman_partner uid 0x000186d2, event 0x41
        TeamEvent8388 pkt = TeamEvent8388.selfEvent(0x000186d2,
                TeamEvent8388.EVENT_TYPE_41);
        byte[] expected = hex("8388 d2860100 41000000 04000000 d2860100");
        assertArrayEquals(expected, body(pkt));
    }

    @Test
    public void selfEvent42ByteEqualsRetailSample() {
        // Sample #2: target=Norman uid 0x00018678, event 0x42
        TeamEvent8388 pkt = TeamEvent8388.selfEvent(0x00018678,
                TeamEvent8388.EVENT_TYPE_42);
        byte[] expected = hex("8388 78860100 42000000 04000000 78860100");
        assertArrayEquals(expected, body(pkt));
    }

    @Test
    public void memberAddEvent43ByteEqualsRetailSample() {
        // Sample #3: target=Norman_partner, event 0x43,
        // role=0x01, member=Norman_partner.
        TeamEvent8388 pkt = TeamEvent8388.memberAddEvent(
                0x000186d2, 0x01, 0x000186d2);
        byte[] expected = hex(
            "8388 d2860100 43000000 09000000 d2860100 01 d2860100");
        assertArrayEquals(expected, body(pkt));
    }

    // ─── Frame structure ────────────────────────────────────────────

    @Test
    public void frameHeaderEncodesCorrectSize() {
        // Build a 0x41 event (4-byte payload). Body = 2 (opcode)
        // + 4 (target) + 4 (event) + 4 (size) + 4 (payload) = 18.
        TeamEvent8388 pkt = TeamEvent8388.selfEvent(0x12345678,
                TeamEvent8388.EVENT_TYPE_41);
        byte[] data = pkt.getData();
        assertEquals((byte) 0xfe, data[0]);
        // LE16 size = body length excluding the 3-byte header.
        int size = (data[1] & 0xff) | ((data[2] & 0xff) << 8);
        assertEquals(18, size);
    }

    @Test
    public void payloadSizeFieldMatchesPayloadLength() {
        // Custom payload of 12 bytes — verify the size field reflects
        // payload only (not whole frame).
        byte[] custom = new byte[12];
        for (int i = 0; i < custom.length; i++) custom[i] = (byte) (i + 1);
        TeamEvent8388 pkt = new TeamEvent8388(0x42, 0x99, custom);
        byte[] b = body(pkt);
        // payload size field at offset 10-13.
        int sz = (b[10] & 0xff) | ((b[11] & 0xff) << 8)
               | ((b[12] & 0xff) << 16) | ((b[13] & 0xff) << 24);
        assertEquals(12, sz);
        // Bytes 14..25 should be the custom payload.
        for (int i = 0; i < custom.length; i++) {
            assertEquals(custom[i], b[14 + i]);
        }
    }

    @Test
    public void nullPayloadCoercesToEmpty() {
        TeamEvent8388 pkt = new TeamEvent8388(0x42, 0x99, null);
        byte[] b = body(pkt);
        // payload size field = 0
        assertEquals(0, b[10] & 0xff);
        assertEquals(0, b[11] & 0xff);
        // body length = 14 (header only, no payload)
        assertEquals(14, b.length);
    }

    // ─── Decoder round-trip ─────────────────────────────────────────

    @Test
    public void decoderRoundTripsSelfEvent() {
        TeamEvent8388 pkt = TeamEvent8388.selfEvent(0x000186d2,
                TeamEvent8388.EVENT_TYPE_41);
        TeamEventDecoder.TeamEvent ev = TeamEventDecoder.decode(body(pkt));
        assertNotNull(ev);
        assertEquals(0x000186d2, ev.targetUid);
        assertEquals(TeamEvent8388.EVENT_TYPE_41, ev.eventType);
        Integer self = ev.asSelfUid();
        assertNotNull(self);
        assertEquals(Integer.valueOf(0x000186d2), self);
    }

    @Test
    public void decoderRoundTripsMemberAddEvent() {
        TeamEvent8388 pkt = TeamEvent8388.memberAddEvent(
                0x000186d2, 0x01, 0x00018678);
        TeamEventDecoder.TeamEvent ev = TeamEventDecoder.decode(body(pkt));
        assertNotNull(ev);
        TeamEventDecoder.MemberAddPayload mp = ev.asMemberAdd();
        assertNotNull(mp);
        assertEquals(0x000186d2, mp.targetUid);
        assertEquals(0x01, mp.role);
        assertEquals(0x00018678, mp.memberUid);
    }

    // ─── Decoder error handling ─────────────────────────────────────

    @Test
    public void decoderRejectsNullAndShort() {
        assertNull(TeamEventDecoder.decode(null));
        assertNull(TeamEventDecoder.decode(new byte[13]));
    }

    @Test
    public void decoderRejectsWrongOpcode() {
        // Same length but wrong opcode bytes.
        byte[] body = hex("8389 00000000 00000000 00000000");
        assertNull(TeamEventDecoder.decode(body));
    }

    @Test
    public void decoderRejectsTruncatedPayload() {
        // payload_size says 8 but only 2 bytes follow.
        byte[] body = hex("8388 11220000 41000000 08000000 1234");
        assertNull(TeamEventDecoder.decode(body));
    }

    @Test
    public void stripFrameRejectsBadHeader() {
        // First byte != 0xfe.
        assertNull(TeamEventDecoder.stripFrame(new byte[]{0, 0, 0, 1, 2, 3}));
        assertNull(TeamEventDecoder.stripFrame(new byte[]{(byte) 0xfe}));
        assertNull(TeamEventDecoder.stripFrame(null));
    }

    @Test
    public void stripFrameDropsHeaderCleanly() {
        TeamEvent8388 pkt = TeamEvent8388.selfEvent(0x12345678,
                TeamEvent8388.EVENT_TYPE_41);
        byte[] data = Arrays.copyOf(pkt.getData(), pkt.size());
        byte[] stripped = TeamEventDecoder.stripFrame(data);
        assertNotNull(stripped);
        assertEquals((byte) 0x83, stripped[0]);
        assertEquals((byte) 0x88, stripped[1]);
    }

    @Test
    public void asSelfUidNullForWrongPayloadShape() {
        TeamEvent8388 pkt = TeamEvent8388.memberAddEvent(1, 2, 3);
        TeamEventDecoder.TeamEvent ev = TeamEventDecoder.decode(body(pkt));
        // 9-byte payload doesn't match the 4-byte self-uid shape.
        assertNull(ev.asSelfUid());
    }

    @Test
    public void asMemberAddNullForWrongPayloadShape() {
        TeamEvent8388 pkt = TeamEvent8388.selfEvent(1,
                TeamEvent8388.EVENT_TYPE_41);
        TeamEventDecoder.TeamEvent ev = TeamEventDecoder.decode(body(pkt));
        // 4-byte payload doesn't match the 9-byte member-add shape.
        assertNull(ev.asMemberAdd());
    }

    @Test
    public void allObservedEventTypesProduceValidPackets() {
        for (int et : new int[]{TeamEvent8388.EVENT_TYPE_41,
                                 TeamEvent8388.EVENT_TYPE_42,
                                 TeamEvent8388.EVENT_TYPE_43}) {
            TeamEvent8388 pkt = new TeamEvent8388(0x42, et, new byte[]{1, 2});
            byte[] b = body(pkt);
            assertEquals((byte) 0x83, b[0]);
            assertEquals((byte) 0x88, b[1]);
            // event_type at offset 6-9
            assertEquals(et, b[6] & 0xff);
        }
    }
}
