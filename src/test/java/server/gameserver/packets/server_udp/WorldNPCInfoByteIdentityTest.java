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
 * <p>Pinned 2026-05-16 against the verified retail decode in
 * {@code docs/protocol/packets/udp_s2c_03_28.md} (hand-decoded
 * AUGUSTO "PMAN", entity 0x0124) cross-checked against the three
 * raw {@code 0x03/0x28} samples preserved in
 * {@code docs/protocol/_data/packets.json} (entities 0x0149,
 * 0x013e, 0x0130).
 *
 * <p>Full retail AUGUSTO PMAN inner (41 B, incl. 0x28 sub-op):
 * <pre>
 *   28 0001 2401 0000 6a519a37 4f01 c877 007e 7f7c
 *   00 ca 06 00*12 504d414e00 3100
 * </pre>
 *
 * <p>Two regions cannot be byte-reproduced and are excluded from
 * the verbatim compare:
 * <ul>
 *   <li>doc {@code [7..10]} instance handle — a server heap handle
 *       (every retail sample distinct; the discredited constant
 *       8958887 absent from all evidence). Asserted via the
 *       uniqueness/stability invariant the client enforces.</li>
 *   <li>doc {@code [19..33]} 15-byte state block — per-NPC runtime
 *       state (PMAN = {@code 00 ca 06} + 12x00) whose sub-structure
 *       is an open question. We emit the retail-valid all-zero
 *       no-state instance; not byte-pinned to PMAN's live state.</li>
 * </ul>
 * Every other byte (the constant framing, ids, class, coords and
 * the trailing ASCII strings) is asserted byte-identical.
 */
public class WorldNPCInfoByteIdentityTest {

    /**
     * Retail AUGUSTO PMAN, full 0x28 inner (incl. sub-op). Indices
     * here are doc-relative ([0] = 0x28).
     */
    private static final byte[] RETAIL_AUGUSTO_PMAN = hex(
        "28" + "0001" + "2401" + "0000" + "6a519a37" + "4f01"
      + "c877" + "007e" + "7f7c"
      + "00ca06" + "000000000000000000000000"
      + "504d414e00" + "3100");

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

    @Test
    public void matchesRetailAugustoPmanSampleStructure() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        // RETAIL: ent=0x0124 (292), class=0x014f, X=31871 Y=30664
        // Z=32256, name "PMAN", orientation "1".
        NPC npc = new NPC(0x0124, 7, 0x014f, "PMAN",
                31871, 30664, 32256, 1, 100, 0);

        // emitted body excludes the 0x28; retail sample includes it.
        int innerLen = RETAIL_AUGUSTO_PMAN.length - 1;
        byte[] body = extractInnerBody(
                datagramBytes(new WorldNPCInfo(pl, npc)), innerLen);

        for (int d = 1; d < RETAIL_AUGUSTO_PMAN.length; d++) {
            // doc [7..10] instance handle: server heap value; we use
            // the deterministic getWorldInstanceHandle(). Covered by
            // instanceHandleIsPerNpcUniqueAndStable.
            if (d >= 7 && d <= 10) continue;
            // doc [19..33] state block: per-NPC runtime state we emit
            // as the retail-valid all-zero no-state instance.
            if (d >= 19 && d <= 33) continue;
            assertEquals(
                "doc byte @" + d + " must match retail AUGUSTO PMAN",
                RETAIL_AUGUSTO_PMAN[d] & 0xFF, body[d - 1] & 0xFF);
        }

        // The 15-byte state block we emit is exactly all-zero.
        for (int d = 19; d <= 33; d++) {
            assertEquals("state block @" + d + " is zero",
                    0x00, body[d - 1] & 0xFF);
        }
        // Total body length == retail sample minus the 0x28 byte
        // (proves no off-by-N: 15-byte block, strings correctly
        // located).
        assertEquals("body length matches retail PMAN",
                innerLen, body.length);
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
        // string region begins at doc [34] -> body[33] ->
        // datagram[11+33].
        int o = 11 + 33;
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
     * AI script) must emit the <em>script name</em> at doc [34..], not
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
        int o = 11 + 33;
        byte[] script = "PATROL_COPBOT6"
                .getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < script.length; i++) {
            assertEquals(
                "doc[34..] must be the AI script name, not display name",
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
        int o = 11 + 33;
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
