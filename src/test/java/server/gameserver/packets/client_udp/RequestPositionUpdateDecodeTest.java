package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Byte-level unit tests for {@link RequestPositionUpdate} — the
 * C→S {@code 0x13 → 0x2a} RequestPos decoder.
 *
 * <p>Every test vector below is a verbatim retail sample body
 * extracted from the pcap corpus in {@code strace/*.pcap} (decrypted
 * via {@code tools/pcap-decode.py}'s LFSR cipher, then 0x13-burst
 * sub-packet split). 98 C→S 0x2a observations across all 17/17
 * retail captures; three body lengths seen: L5 (×11, header-only),
 * L16 (×86, header + token), L3 (×1, framing artifact).
 *
 * <p>Proven layout:
 * <pre>
 * [0]      0x2a                 sub-opcode
 * [1..4]   character_uid LE32   session-stable per character
 * [5..15]  request_token 11 B   opaque request id (16-byte form only)
 * </pre>
 */
public class RequestPositionUpdateDecodeTest {

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) Integer.parseInt(
                    s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    // ── 16-byte form: header + 11-byte request token ───────────────

    @Test
    public void decodes16ByteRetailSample_CREATION() {
        // RETAIL_CREATION_LEVELING_LONG_20260502_160841 sample #1.
        // uid bytes 1a 7f 01 00 = 0x00017f1a (LE32).
        byte[] body = hex("2a1a7f0100fce02ddbff6dd90eb90a00");
        RequestPositionUpdate p = new RequestPositionUpdate(body);

        assertEquals("character_uid = LE32 of [1..4]",
                0x00017f1aL, p.getCharacterUid());
        assertNotNull("16-byte form carries a request token",
                p.getRequestToken());
        assertArrayEquals("request token = body[5..15]",
                hex("fce02ddbff6dd90eb90a00"),
                p.getRequestToken());
    }

    @Test
    public void decodes16ByteRetailSample_VEHICLE_DRONE() {
        // RETAIL_RETAIL_VEHICLE_DRONE_20260503_141715.
        // uid b0 a2 00 00 = 0x0000a2b0.
        byte[] body = hex("2ab0a200009484f3511dd2a1fa150c00");
        RequestPositionUpdate p = new RequestPositionUpdate(body);

        assertEquals(0x0000a2b0L, p.getCharacterUid());
        assertArrayEquals(hex("9484f3511dd2a1fa150c00"),
                p.getRequestToken());
    }

    @Test
    public void decodes16ByteRetailSample_ZONING_AND_ITEMS() {
        // RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613.
        // uid bd 7e 01 00 = 0x00017ebd.
        byte[] body = hex("2abd7e010005ae73323a76d49d0e0900");
        RequestPositionUpdate p = new RequestPositionUpdate(body);

        assertEquals(0x00017ebdL, p.getCharacterUid());
        assertArrayEquals(hex("05ae73323a76d49d0e0900"),
                p.getRequestToken());
    }

    @Test
    public void crossSessionTokenIsIdenticalAcrossDifferentUids() {
        // Decisive evidence the token is NOT session state: the same
        // 11-byte token recurs verbatim under two different UIDs.
        // 9484f3511dd2a1fa150c00 — VEHICLE_DRONE (uid 0x0000a2b0)
        // AND ZONING_AND_ITEMS (uid 0x00017ebd).
        RequestPositionUpdate a = new RequestPositionUpdate(
                hex("2ab0a200009484f3511dd2a1fa150c00"));
        RequestPositionUpdate b = new RequestPositionUpdate(
                hex("2abd7e01009484f3511dd2a1fa150c00"));

        assertNotEquals("UIDs differ across the two sessions",
                a.getCharacterUid(), b.getCharacterUid());
        assertArrayEquals("but the request token is byte-identical",
                a.getRequestToken(), b.getRequestToken());
    }

    @Test
    public void tokenNearPairDiffersOnlyInLastByte() {
        // 1eef3bcebab302f6120a00 vs 1eef3bcebab302f6120a01 — same
        // session (uid 0x0000a2b0), tokens differ only in the final
        // byte (00 vs 01). Both real retail observations.
        RequestPositionUpdate p0 = new RequestPositionUpdate(
                hex("2ab0a200001eef3bcebab302f6120a00"));
        RequestPositionUpdate p1 = new RequestPositionUpdate(
                hex("2ab0a200001eef3bcebab302f6120a01"));

        assertEquals(p0.getCharacterUid(), p1.getCharacterUid());
        byte[] t0 = p0.getRequestToken();
        byte[] t1 = p1.getRequestToken();
        assertEquals(0x00, t0[10] & 0xFF);
        assertEquals(0x01, t1[10] & 0xFF);
        // First 10 token bytes identical.
        for (int i = 0; i < 10; i++)
            assertEquals("token byte " + i, t0[i], t1[i]);
    }

    // ── 5-byte form: header only (no request token) ────────────────

    @Test
    public void decodes5ByteHeaderOnly_a2b0() {
        // 8 of 98 samples are this exact 5-byte body.
        byte[] body = hex("2ab0a20000");
        RequestPositionUpdate p = new RequestPositionUpdate(body);

        assertEquals(0x0000a2b0L, p.getCharacterUid());
        assertNull("5-byte form carries NO request token",
                p.getRequestToken());
    }

    @Test
    public void decodes5ByteHeaderOnly_017ebd() {
        byte[] body = hex("2abd7e0100");
        RequestPositionUpdate p = new RequestPositionUpdate(body);
        assertEquals(0x00017ebdL, p.getCharacterUid());
        assertNull(p.getRequestToken());
    }

    @Test
    public void decodes5ByteHeaderOnly_017f1a() {
        byte[] body = hex("2a1a7f0100");
        RequestPositionUpdate p = new RequestPositionUpdate(body);
        assertEquals(0x00017f1aL, p.getCharacterUid());
        assertNull(p.getRequestToken());
    }

    // ── L3 framing artifact: must NOT crash, no spurious fields ────

    @Test
    public void threeByteFramingArtifactDecodesDefensively() {
        // The single 2a431f outlier in CREATION_LEVELING_LONG is a
        // sub-packet length desync (0x1f is the next packet's
        // opcode). Decoder must not read past the buffer and must
        // expose no UID / no token.
        byte[] body = hex("2a431f");
        RequestPositionUpdate p = new RequestPositionUpdate(body);

        assertEquals("too short for a UID → -1",
                -1L, p.getCharacterUid());
        assertNull("no token on a malformed body",
                p.getRequestToken());
    }

    @Test
    public void uidIsLittleEndianNotBigEndian() {
        // Catches a byte-order regression: bd 7e 01 00 must decode
        // as 0x00017ebd, not 0xbd7e0100.
        RequestPositionUpdate p = new RequestPositionUpdate(
                hex("2abd7e0100"));
        assertEquals(0x00017ebdL, p.getCharacterUid());
        assertNotEquals(0xbd7e0100L, p.getCharacterUid());
    }

    @Test
    public void returnedTokenIsADefensiveCopy() {
        RequestPositionUpdate p = new RequestPositionUpdate(
                hex("2a1a7f0100fce02ddbff6dd90eb90a00"));
        byte[] t = p.getRequestToken();
        t[0] = 0x7F;
        assertEquals("mutating the returned array must not affect "
                + "the decoder's internal state",
                (byte) 0xfc, p.getRequestToken()[0]);
    }
}
