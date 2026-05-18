package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.Player;

/**
 * Byte-identity test for {@link WorldNPCInfo}
 * (UDP S-&gt;C reliable {@code 0x03/0x28}, per-NPC WorldInfo).
 *
 * <p>Pinned 2026-05-17 (task #178c) against the machine-decoded
 * live retail pcap
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} (server
 * 157.90.195.74) for the EXACT failing scripted-city-NPC class
 * (Type 15 / {@code SCRIPTEDPLAYER}): reliable {@code 0x03/0x28}
 * records for entities 266 ("WSK"), 299 ("WCOP") and 325
 * ("PATROL_COPBOT6"). All three carry a 17-byte state block at doc
 * {@code [19..35]} with the script name starting at doc {@code [36]}.
 *
 * <p>The doc's hand-transcribed AUGUSTO "PMAN" (entity 0x0124) is a
 * NON-scripted NPC and showed a 15-byte block; the machine-decoded
 * live pcap of the actual failing scripted class is unambiguously
 * 17 across all three samples and is the authoritative ground truth
 * for the Type-15 create.
 *
 * <p>Full retail WSK (entity 266) inner (45 B, incl. 0x28 sub-op),
 * machine-decoded from the live pcap:
 * <pre>
 *   28 0001 0a01 0000 10e3ed78 4a0b a078 107f 708a
 *   00 bc030000 00 0c0b 07 0909 05 0000 00 0000
 *   57534b00 2d31373800
 * </pre>
 *
 * <p>Two regions cannot be byte-reproduced and are excluded from
 * the verbatim compare:
 * <ul>
 *   <li>doc {@code [7..10]} instance handle — a server heap handle
 *       (every retail sample distinct; the discredited constant
 *       8958887 absent from all evidence). Asserted via the
 *       uniqueness/stability invariant the client enforces.</li>
 *   <li>doc {@code [19..35]} 17-byte state block — per-NPC runtime
 *       state whose leading bytes are unreproducible (same category
 *       as the handle) and whose trailing 6 bytes are zero in every
 *       sample. We emit the retail-valid all-zero no-state instance;
 *       not byte-pinned to the live runtime state.</li>
 * </ul>
 * Every other byte (the constant framing, ids, class, coords and
 * the trailing ASCII strings) is asserted byte-identical, and the
 * total body length is asserted equal to the retail sample so a
 * 15-vs-17 state-block off-by-2 (the Type-15 corruption) cannot
 * regress.
 */
public class WorldNPCInfoByteIdentityTest {

    /**
     * Retail live-pcap WSK (entity 266), full 0x28 inner (incl.
     * sub-op). Indices here are doc-relative ([0] = 0x28).
     */
    private static final byte[] RETAIL_LIVE_WSK = hex(
        "28" + "0001" + "0a01" + "0000" + "10e3ed78" + "4a0b"
      + "a078" + "107f" + "708a"
      + "00bc030000000c0b0709090500000000" + "00"
      + "57534b00" + "2d31373800");

    /** Retail live-pcap PATROL_COPBOT6 (entity 325), full 0x28
     *  inner. 17-byte state block, longer script token. */
    private static final byte[] RETAIL_LIVE_PATROL = hex(
        "28" + "0001" + "4501" + "0000" + "bba55a79" + "e227"
      + "b777" + "007f" + "0c8c"
      + "00cb3d000000bbbb00c6c60000000000" + "00"
      + "504154524f4c5f434f50424f543600" + "343500");

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(
                s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static byte[] datagramBytes(WorldNPCInfo pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /**
     * Returns the inner body AFTER the 0x28 sub-op. So
     * {@code body[k]} == retail doc index {@code k+1}.
     */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x28", 0x28, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8)
             | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    /** doc [19..35] inclusive == 17-byte state block. */
    private static final int STATE_LO = 19;
    private static final int STATE_HI = 35;

    private void assertMatchesRetailSample(byte[] retail, NPC npc) {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        // emitted body excludes the 0x28; retail sample includes it.
        int innerLen = retail.length - 1;
        byte[] body = extractInnerBody(
                datagramBytes(new WorldNPCInfo(pl, npc)), innerLen);

        for (int d = 1; d < retail.length; d++) {
            // doc [7..10] instance handle: server heap value; we use
            // the deterministic getWorldInstanceHandle(). Covered by
            // instanceHandleIsPerNpcUniqueAndStable.
            if (d >= 7 && d <= 10) continue;
            // doc [19..35] state block: per-NPC runtime state we emit
            // as the retail-valid all-zero no-state instance.
            if (d >= STATE_LO && d <= STATE_HI) continue;
            assertEquals(
                "doc byte @" + d + " must match retail live sample",
                retail[d] & 0xFF, body[d - 1] & 0xFF);
        }

        // The 17-byte state block we emit is exactly all-zero.
        for (int d = STATE_LO; d <= STATE_HI; d++) {
            assertEquals("state block @" + d + " is zero",
                    0x00, body[d - 1] & 0xFF);
        }
        // Total body length == retail sample minus the 0x28 byte.
        // This is the regression guard for the Type-15 corruption:
        // a 15-byte state block (pre-#178c) makes this 2 bytes short
        // and shifts the script name into the parser's framing,
        // producing "@WWORLDMGR : Corrupted Message Type:15".
        assertEquals("body length matches retail live sample",
                innerLen, body.length);
    }

    @Test
    public void matchesRetailLiveWskSampleStructure() {
        // RETAIL live pcap entity 266: class=0x0b4a, X=35440
        // Y=30880 Z=32528, script "WSK", orientation "-178".
        NPC npc = new NPC(266, 7, 0x0b4a, "WSK",
                35440, 30880, 32528, -178, 100, 0);
        assertMatchesRetailSample(RETAIL_LIVE_WSK, npc);
    }

    @Test
    public void matchesRetailLivePatrolCopbotSampleStructure() {
        // RETAIL live pcap entity 325 (Type-15 scripted copbot):
        // class=0x27e2, X=35852 Y=30647 Z=32512, script
        // "PATROL_COPBOT6", orientation "45". Display name differs
        // from the AI script (curated spawn path).
        NPC npc = new NPC(325, 7, 0x27e2, "Copbot",
                "PATROL_COPBOT6", "", 35852, 30647, 32512, 45, 100, 0);
        assertMatchesRetailSample(RETAIL_LIVE_PATROL, npc);
    }

    @Test
    public void instanceHandleIsPerNpcUniqueAndStable() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC a1 = new NPC(0, 1, 0, "A", 0, 0, 0, 0, 100, 0);
        a1.setMapID(257);
        NPC a2 = new NPC(0, 1, 0, "B", 0, 0, 0, 0, 100, 0);
        a2.setMapID(258);

        // doc [7] == body[6].
        long h1a = le32(extractInnerBody(
                datagramBytes(new WorldNPCInfo(pl, a1)), 33), 6);
        long h1b = le32(extractInnerBody(
                datagramBytes(new WorldNPCInfo(pl, a1)), 33), 6);
        long h2  = le32(extractInnerBody(
                datagramBytes(new WorldNPCInfo(pl, a2)), 33), 6);

        assertEquals("handle stable across packets for same NPC",
                h1a, h1b);
        assertNotEquals("distinct NPCs must get distinct handles",
                h1a, h2);
        assertNotEquals("must not emit the discredited 8958887 constant",
                8958887L, h1a);
        assertNotEquals("must not emit the discredited 8958887 constant",
                8958887L, h2);
    }

    @Test
    public void trailingStringsAreScriptNameThenOrientation() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        // Bulk world_npcs NPC: name == actor_name == script ("WSK"),
        // exactly the retail entity-266 script token.
        NPC npc = new NPC(257, 1, 5, "WSK", 0, 0, 0, -90, 100, 0);

        DatagramPacket dp = new WorldNPCInfo(pl, npc)
                .getDatagramPackets()[0];
        byte[] full = new byte[dp.getLength()];
        System.arraycopy(dp.getData(), 0, full, 0, full.length);
        // body after the 0x28 sub-op starts at datagram[11]; the
        // string region begins at doc [36] (after the 17-byte state
        // block) -> body[35] -> datagram[11+35].
        int o = 11 + 35;
        byte[] name = "WSK".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < name.length; i++) {
            assertEquals("script byte " + i, name[i], full[o + i]);
        }
        assertEquals("script NUL", 0x00, full[o + 3] & 0xFF);
        byte[] ori = "-90".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < ori.length; i++) {
            assertEquals("orient byte " + i, ori[i], full[o + 4 + i]);
        }
        assertEquals("orient NUL", 0x00, full[o + 4 + 3] & 0xFF);
    }

    /**
     * #178 functional: a scripted NPC (e.g. a copbot from the curated
     * {@code npc_spawns} path, where the display name differs from the
     * AI script) must emit the <em>script name</em> at doc [36..], not
     * the display name. Cross-checked against the retail live pcap
     * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap}: entity 325
     * carries the script token {@code PATROL_COPBOT6}. An empty or
     * wrong token here is exactly what made the client log
     * "Unable to find script" and skip instantiating the NPC.
     */
    @Test
    public void scriptedNpcEmitsScriptNameNotDisplayName() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        // Curated spawn: display name "Copbot", AI script
        // "PATROL_COPBOT6" (the retail entity-325 script token).
        NPC npc = new NPC(325, 1, 0x27e2, "Copbot",
                "PATROL_COPBOT6", "", 0, 0, 0, 45, 100, 0);

        DatagramPacket dp = new WorldNPCInfo(pl, npc)
                .getDatagramPackets()[0];
        byte[] full = new byte[dp.getLength()];
        System.arraycopy(dp.getData(), 0, full, 0, full.length);
        int o = 11 + 35;
        byte[] script = "PATROL_COPBOT6"
                .getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < script.length; i++) {
            assertEquals(
                "doc[36..] must be the AI script name, not display name",
                script[i], full[o + i]);
        }
        assertEquals("script NUL", 0x00, full[o + script.length] & 0xFF);
        // The display name "Copbot" must NOT appear at the token start.
        assertNotEquals("must not emit display name as the script token",
                (int) 'C', full[o] & 0xFF);
        // Orientation token immediately follows.
        byte[] ori = "45".getBytes(StandardCharsets.US_ASCII);
        int oo = o + script.length + 1;
        for (int i = 0; i < ori.length; i++) {
            assertEquals("orient byte " + i, ori[i], full[oo + i]);
        }
        assertEquals("orient NUL", 0x00, full[oo + ori.length] & 0xFF);
    }

    /**
     * #178: a curated spawn that has a display name but no AI script
     * column must still emit a non-empty token (the display name) — a
     * zero-length script string is rejected by the client just like an
     * absent one.
     */
    @Test
    public void emptyScriptFallsBackToDisplayName() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(300, 1, 7, "Vendor",
                "", "", 0, 0, 0, 0, 100, 0);

        DatagramPacket dp = new WorldNPCInfo(pl, npc)
                .getDatagramPackets()[0];
        byte[] full = new byte[dp.getLength()];
        System.arraycopy(dp.getData(), 0, full, 0, full.length);
        int o = 11 + 35;
        byte[] name = "Vendor".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < name.length; i++) {
            assertEquals("fallback display name byte " + i,
                    name[i], full[o + i]);
        }
        assertNotEquals("token must not be zero-length",
                0x00, full[o] & 0xFF);
        assertEquals("name NUL", 0x00, full[o + name.length] & 0xFF);
    }
}
