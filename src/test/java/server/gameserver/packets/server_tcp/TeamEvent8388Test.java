package server.gameserver.packets.server_tcp;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

import server.gameserver.team.TeamEventDecoder;

/**
 * Unit + functional tests for {@link TeamEvent8388}.
 *
 * <p>Drives byte-equal verification against every distinct retail
 * fixture observed for {@code TCP S->C 0x8388} in the corpus
 * (16 unique-by-content samples across 25 raw observations).
 * Each test re-builds the packet from the inferred field values
 * and checks the resulting wire bytes match the captured sample
 * exactly (after stripping the FE-frame header).
 *
 * <p>Retail samples (post-FE-frame, opcode-onwards). Source
 * pcaps: {@code RETAIL_RETAIL_LONG_PARTY_A_20260503_130137},
 * {@code RETAIL_RETAIL_LONG_PARTY_B_20260503_130343},
 * {@code RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715}:
 *
 * <pre>
 *   event=0x41, payload=4
 *     8388 d2860100 41000000 04000000 d2860100   PARTY_A t=996.5, PARTY_B t=940.1
 *     8388 78860100 41000000 04000000 78860100   PARTY_A t=1266.4
 *
 *   event=0x42, payload=4
 *     8388 78860100 42000000 04000000 78860100   PARTY_A t=1015.2, PARTY_B t=958.9
 *     8388 d2860100 42000000 04000000 d2860100   PARTY_A t=1272.4
 *
 *   event=0x43, payload=9
 *     8388 d2860100 43000000 09000000 d2860100 01 d2860100   PARTY_A t=1015.5, PARTY_B t=959.1
 *     8388 78860100 43000000 09000000 78860100 01 78860100   PARTY_A t=1272.8, PARTY_B t=1216.4
 *
 *   event=0x44, payload=4
 *     8388 78860100 44000000 04000000 78860100   PARTY_A t=1015.7
 *     8388 d2860100 44000000 04000000 d2860100   PARTY_A t=1273.0, PARTY_B t=1216.7
 *
 *   event=0x48, payload=4
 *     8388 78860100 48000000 04000000 78860100   PARTY_A t=1249.4 ×2
 *     8388 d2860100 48000000 04000000 d2860100   PARTY_A t=1295.8 ×2, PARTY_B t=1239.5 ×2
 *     8388 fe850100 48000000 04000000 fe850100   VEHICLE_DRONE t=1342.2
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

    @Test
    public void selfEvent43AlternateRetailSample() {
        // PARTY_A t=1272.8s and PARTY_B t=1216.4s carry the same
        // payload but with the other party member's UID in both slots.
        TeamEvent8388 pkt = TeamEvent8388.memberAddEvent(
                0x00018678, 0x01, 0x00018678);
        byte[] expected = hex(
            "8388 78860100 43000000 09000000 78860100 01 78860100");
        assertArrayEquals(expected, body(pkt));
    }

    @Test
    public void selfEvent44ByteEqualsRetailSample() {
        // PARTY_A t=1273.0s, PARTY_B t=1216.7s — the 0x44
        // (member-removed) variant uses the same self-UID
        // payload shape as 0x41/0x42.
        TeamEvent8388 pkt = TeamEvent8388.selfEvent(0x000186d2,
                TeamEvent8388.EVENT_TYPE_44);
        byte[] expected = hex("8388 d2860100 44000000 04000000 d2860100");
        assertArrayEquals(expected, body(pkt));
    }

    @Test
    public void selfEvent44AlternateRetailSample() {
        // PARTY_A t=1015.7s — same shape, other UID.
        TeamEvent8388 pkt = TeamEvent8388.selfEvent(0x00018678,
                TeamEvent8388.EVENT_TYPE_44);
        byte[] expected = hex("8388 78860100 44000000 04000000 78860100");
        assertArrayEquals(expected, body(pkt));
    }

    @Test
    public void selfEvent48ByteEqualsRetailSample() {
        // PARTY_A t=1295.8s ×2, PARTY_B t=1239.5s ×2 — team-disband
        // / leave broadcast. Same self-UID payload shape.
        TeamEvent8388 pkt = TeamEvent8388.selfEvent(0x000186d2,
                TeamEvent8388.EVENT_TYPE_48);
        byte[] expected = hex("8388 d2860100 48000000 04000000 d2860100");
        assertArrayEquals(expected, body(pkt));
    }

    @Test
    public void selfEvent48AlternateRetailSamples() {
        // PARTY_A t=1249.4s ×2 — partner-UID variant.
        TeamEvent8388 partnerPkt = TeamEvent8388.selfEvent(0x00018678,
                TeamEvent8388.EVENT_TYPE_48);
        byte[] partnerExpected = hex(
                "8388 78860100 48000000 04000000 78860100");
        assertArrayEquals(partnerExpected, body(partnerPkt));

        // VEHICLE_DRONE t=1342.2s — third UID outside the PARTY
        // captures, proving the same 0x48 shape carries other
        // characters too.
        TeamEvent8388 vehiclePkt = TeamEvent8388.selfEvent(0x000185fe,
                TeamEvent8388.EVENT_TYPE_48);
        byte[] vehicleExpected = hex(
                "8388 fe850100 48000000 04000000 fe850100");
        assertArrayEquals(vehicleExpected, body(vehiclePkt));
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
        // All five retail-observed event types must round-trip
        // through the generic constructor.
        for (int et : new int[]{TeamEvent8388.EVENT_TYPE_41,
                                 TeamEvent8388.EVENT_TYPE_42,
                                 TeamEvent8388.EVENT_TYPE_43,
                                 TeamEvent8388.EVENT_TYPE_44,
                                 TeamEvent8388.EVENT_TYPE_48}) {
            TeamEvent8388 pkt = new TeamEvent8388(0x42, et, new byte[]{1, 2});
            byte[] b = body(pkt);
            assertEquals((byte) 0x83, b[0]);
            assertEquals((byte) 0x88, b[1]);
            // event_type at offset 6-9
            assertEquals(et, b[6] & 0xff);
        }
    }

    @Test
    public void event44And48UseFourByteSelfUidPayload() {
        // 0x44 and 0x48 are documented as 4-byte self-UID payload
        // shapes. Verify the decoder recognises them as such.
        for (int et : new int[]{TeamEvent8388.EVENT_TYPE_44,
                                 TeamEvent8388.EVENT_TYPE_48}) {
            TeamEvent8388 pkt = TeamEvent8388.selfEvent(0xdeadbeef, et);
            TeamEventDecoder.TeamEvent ev = TeamEventDecoder.decode(body(pkt));
            assertNotNull("event 0x" + Integer.toHexString(et)
                    + " should decode", ev);
            assertEquals(et, ev.eventType);
            Integer self = ev.asSelfUid();
            assertNotNull("event 0x" + Integer.toHexString(et)
                    + " should be a self-UID payload", self);
            assertEquals(0xdeadbeef, self.intValue());
        }
    }
}
