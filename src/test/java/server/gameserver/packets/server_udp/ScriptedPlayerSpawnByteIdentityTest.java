package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.Player;

/**
 * Byte-identity test for {@link ScriptedPlayerSpawn} — the WWORLDMGR
 * <strong>Type-0x1E SCRIPTEDPLAYER spawn</strong> (reliable
 * {@code 0x13 -> 0x03 -> 0x1e}).
 *
 * <p>Every asserted byte is traced to either:
 * <ul>
 *   <li><b>RE_state_sync.md §1 / §1.1 / §1.2</b> (disassembled from
 *       {@code neocronclient.exe}: dispatcher {@code FUN_00541f20}
 *       {@code case '\x1e'}, raw decompile {@code docs/re_state_sync_dump.txt};
 *       WA factory {@code FUN_00567e50} class-{@code 0x100} branch,
 *       {@code docs/re_state_sync_dump5.txt}; SCRIPTEDPLAYER raw-stream
 *       ctor {@code FUN_00699fd0}, {@code docs/re_state_sync_dump3.txt}
 *       lines 416-585); OR</li>
 *   <li>the live retail pcap
 *       {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} (server
 *       157.90.195.74), machine-decoded with
 *       {@code tools/npc-lifecycle.py}: the scripted-city-NPC class
 *       entities 266 "WSK" / 299 "WCOP" / 325 "PATROL_COPBOT6".</li>
 * </ul>
 *
 * <p>The retail capture contains only {@code 0x03/0x28} refreshes for
 * these NPCs (no literal {@code 0x1e} — the player sat next to
 * already-spawned NPCs whose Type-0x1E create fired before capture
 * start). The {@code 0x28} body is therefore the authoritative source
 * for the SHARED identity fields the Type-0x1E stream also carries
 * (entity id LE32, NPC class id LE16, X/Y/Z LE16, ASCIIZ script_name
 * then ASCIIZ orientation token); those are cross-checked here against
 * the pcap-decoded {@code 0x28} WSK sample. The Type-0x1E-only framing
 * (Message Type {@code 0x1E}, WA class {@code 0x0100}, the stream's
 * {@code [0]} id / waypoint-count / HP-float / 10B skill / flags
 * offsets) is pinned to RE_state_sync.md §1.2 / the {@code FUN_00699fd0}
 * decompile.
 */
public class ScriptedPlayerSpawnByteIdentityTest {

    /**
     * Retail live-pcap WSK (entity 266), full {@code 0x03/0x28} inner
     * (incl. the {@code 0x28} sub-op), machine-decoded from
     * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap}. Same
     * sample as {@code WorldNPCInfoByteIdentityTest.RETAIL_LIVE_WSK}.
     * Layout (doc [0] = 0x28):
     * <pre>
     *   28 0001 0a01 0000 10e3ed78 4a0b a078 107f 708a
     *   00 bc030000 00 0c0b 07 0909 05 0000 00 0000
     *   57534b00 2d31373800
     * </pre>
     * Decoded fields used as cross-checks: entity id LE32 = 0x0000010a
     * (266); NPC class id LE16 (0x28 [11..12]) = 0x0b4a; Y/Z/X LE16
     * (0x28 [13..18]) = a078 / 107f / 708a (30880 / 32528 / 35440);
     * script "WSK"; orientation "-178".
     */
    private static final byte[] RETAIL_LIVE_WSK_0x28 = hex(
        "28" + "0001" + "0a01" + "0000" + "10e3ed78" + "4a0b"
      + "a078" + "107f" + "708a"
      + "00bc030000000c0b0709090500000000" + "00"
      + "57534b00" + "2d31373800");

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(
                s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static byte[] datagram(ScriptedPlayerSpawn pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /**
     * Returns the WWORLDMGR application body (from the Type byte
     * onward) after stripping the reliable frame
     * {@code 0x13 [ctr LE2][ctr+sk LE2] [subLen LE2] 0x03 [seq LE2]}.
     * {@code body[0]} is the WWORLDMGR Message Type.
     */
    private static byte[] innerBody(byte[] dg) {
        assertEquals("outer 0x13",       0x13, dg[0] & 0xFF);
        assertEquals("reliable 0x03",    0x03, dg[7] & 0xFF);
        assertEquals("WWORLDMGR Type 0x1E sub-op",
                0x1E, dg[10] & 0xFF);
        byte[] b = new byte[dg.length - 10];
        System.arraycopy(dg, 10, b, 0, b.length);
        return b;
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8)
             | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    /**
     * Full byte-identity pin of the Type-0x1E SCRIPTEDPLAYER spawn for
     * the retail WSK NPC. Body offsets per RE_state_sync.md §1 / §1.2;
     * shared identity/position/script fields cross-checked against the
     * pcap-decoded {@code 0x28} WSK sample.
     */
    @Test
    public void typeOneEScriptedPlayerBodyMatchesSpecAndPcap() {
        // RETAIL live pcap entity 266: id=266, class=0x0b4a,
        // X=35440 Y=30880 Z=32528, script "WSK", orientation -178,
        // HP 100. (Same NPC the WorldNPCInfo test uses.)
        NPC npc = new NPC(266, 7, 0x0b4a, "WSK",
                35440, 30880, 32528, -178, 100, 0);
        byte[] body = innerBody(datagram(new ScriptedPlayerSpawn(
                PacketTestFixture.newPlayerWithFixedSessionKey(
                        (short) 0), npc)));

        // ---- Type-0x1E header (RE_state_sync.md §1, FUN_00541f20
        //      case '\x1e'; dump.txt) ----
        assertEquals("[0] WWORLDMGR Message Type 0x1E",
                0x1E, body[0] & 0xFF);
        // [1-2] WA class id 0x0100 -> FUN_00567e50 param_3==0x100 ->
        // SCRIPTEDPLAYER raw-stream ctor FUN_00699fd0 (dump5.txt).
        assertEquals("[1-2] WA class id 0x0100 (SCRIPTEDPLAYER "
                + "raw-stream ctor selector)", 0x0100, u16(body, 1));
        // [3-6] entity id LE32 (FUN_005412d0 lookup key) — cross-check
        // vs the pcap 0x28 entity id (doc [3..4] = 0a01 = 266).
        long retailId = u16(RETAIL_LIVE_WSK_0x28, 3);
        assertEquals("[3-6] entity id LE32 == retail 0x28 entity id",
                retailId, u32(body, 3));
        assertEquals("[3-6] entity id == 266", 266L, u32(body, 3));

        // ---- SCRIPTEDPLAYER stream (body+7 = FUN_00699fd0 param_3,
        //      RE_state_sync.md §1.2; dump3.txt FUN_00699fd0) ----
        final int S = 7;
        // stream[0x00] LE32 world/entity id (in_ECX[0x1ec]=*param_3,
        // dump3 l.487).
        assertEquals("stream[0] LE32 id == entity id",
                266L, u32(body, S + 0x00));
        // stream[0x04] LE16 class/type id (in_ECX[0x163], dump3 l.488)
        // — cross-check vs pcap 0x28 class_id (doc [11..12]=4a0b).
        int retailClass = u16(RETAIL_LIVE_WSK_0x28, 11);
        assertEquals("stream[4] class id == retail 0x28 class_id",
                retailClass, u16(body, S + 0x04));
        assertEquals("stream[4] class id == 0x0b4a",
                0x0b4a, u16(body, S + 0x04));
        // stream[0x06]/[0x08]/[0x0a] LE16 X/Y/Z — FUN_0054e210(
        // *(p+6)=X, *(p+2)=Y, *(p+10)=Z) (dump3 l.491). Cross-check
        // vs the pcap 0x28 Y/Z/X at doc [13..18] = a078/107f/708a.
        assertEquals("stream[6] X == retail X",
                u16(RETAIL_LIVE_WSK_0x28, 17), u16(body, S + 0x06));
        assertEquals("stream[8] Y == retail Y",
                u16(RETAIL_LIVE_WSK_0x28, 13), u16(body, S + 0x08));
        assertEquals("stream[10] Z == retail Z",
                u16(RETAIL_LIVE_WSK_0x28, 15), u16(body, S + 0x0a));
        assertEquals("stream[6] X == 35440", 35440, u16(body, S + 0x06));
        assertEquals("stream[8] Y == 30880", 30880, u16(body, S + 0x08));
        assertEquals("stream[10] Z == 32528", 32528, u16(body, S + 0x0a));
        // stream[0x0c] 1B waypoint count = 0 (npc_spawns has no patrol
        // path; FUN_00699fd0 null-guards a zero count, dump3 l.560).
        assertEquals("stream[0x0c] waypoint count == 0",
                0x00, body[S + 0x0c] & 0xFF);
        // stream[0x0d] LE32 HP as IEEE-754 float ((float)*(int*)
        // (param_3+0xd), dump3 l.499).
        assertEquals("stream[0x0d] HP float == 100.0f",
                Float.floatToIntBits(100.0f) & 0xFFFFFFFFL,
                u32(body, S + 0x0d));
        // stream[0x11..0x1a] 10B skill/attr array — documented
        // all-zero default (no per-NPC source; dump3 l.516-521).
        for (int i = 0; i < 10; i++) {
            assertEquals("stream[0x11+" + i + "] skill byte == 0",
                    0x00, body[S + 0x11 + i] & 0xFF);
        }
        // stream[0x1b..0x1c] LE16 flags — documented default 0x0000
        // (in_ECX[0x1e6], dump3 l.494).
        assertEquals("stream[0x1b] flags == 0x0000",
                0x0000, u16(body, S + 0x1b));
        // stream[0x1d] ASCIIZ script_name — cross-check vs pcap 0x28
        // script token ("WSK", doc [36..]).
        int o = S + 0x1d;
        byte[] script = "WSK".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < script.length; i++) {
            assertEquals("stream script byte " + i,
                    script[i], body[o + i]);
        }
        assertEquals("script NUL", 0x00, body[o + script.length] & 0xFF);
        // ASCIIZ orientation token immediately after (signed decimal
        // angle), identical to WorldNPCInfo's second token.
        int oo = o + script.length + 1;
        byte[] ori = "-178".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < ori.length; i++) {
            assertEquals("orient byte " + i, ori[i], body[oo + i]);
        }
        assertEquals("orient NUL", 0x00, body[oo + ori.length] & 0xFF);
        // No waypoint records (count == 0): the body ends exactly at
        // the orientation NUL — guards against trailing-byte drift the
        // Type-0x1E parser (FUN_00699fd0 l.578) would treat as a
        // malformed waypoint list.
        assertEquals("body ends exactly at orientation NUL "
                + "(no spurious waypoint bytes)",
                oo + ori.length + 1, body.length);
    }

    /**
     * The full RE_state_sync.md §1.2 byte stream pinned literally for a
     * deterministic synthetic NPC (independent of the pcap), so a
     * regression in any single offset/width is caught even if the pcap
     * cross-checks are loosened. Bytes constructed field-by-field from
     * the {@code FUN_00699fd0} decompile.
     */
    @Test
    public void typeOneEBodyIsByteIdenticalToSpecLayout() {
        NPC npc = new NPC(0x12345678, 0, 0x27e2, "PATROL_COPBOT6",
                0x1111, 0x2222, 0x3333, 45, 250, 0);
        byte[] body = innerBody(datagram(new ScriptedPlayerSpawn(
                PacketTestFixture.newPlayerWithFixedSessionKey(
                        (short) 0), npc)));

        java.io.ByteArrayOutputStream exp =
                new java.io.ByteArrayOutputStream();
        // [0] Type 0x1E
        exp.write(0x1E);
        // [1-2] WA class 0x0100 LE16
        exp.write(0x00); exp.write(0x01);
        // [3-6] entity id LE32 = 0x12345678
        exp.write(0x78); exp.write(0x56); exp.write(0x34); exp.write(0x12);
        // stream[0] LE32 id
        exp.write(0x78); exp.write(0x56); exp.write(0x34); exp.write(0x12);
        // stream[4] LE16 class 0x27e2
        exp.write(0xe2); exp.write(0x27);
        // stream[6] LE16 X 0x1111
        exp.write(0x11); exp.write(0x11);
        // stream[8] LE16 Y 0x2222
        exp.write(0x22); exp.write(0x22);
        // stream[0xa] LE16 Z 0x3333
        exp.write(0x33); exp.write(0x33);
        // stream[0xc] waypoint count 0
        exp.write(0x00);
        // stream[0xd] LE32 HP float 250.0f
        int hp = Float.floatToIntBits(250.0f);
        exp.write(hp & 0xFF); exp.write((hp >>> 8) & 0xFF);
        exp.write((hp >>> 16) & 0xFF); exp.write((hp >>> 24) & 0xFF);
        // stream[0x11..0x1a] 10B skill array, all zero
        for (int i = 0; i < 10; i++) exp.write(0x00);
        // stream[0x1b] LE16 flags 0x0000
        exp.write(0x00); exp.write(0x00);
        // stream[0x1d] ASCIIZ script_name
        for (byte c : "PATROL_COPBOT6"
                .getBytes(StandardCharsets.US_ASCII)) exp.write(c);
        exp.write(0x00);
        // ASCIIZ orientation "45"
        for (byte c : "45".getBytes(StandardCharsets.US_ASCII))
            exp.write(c);
        exp.write(0x00);

        byte[] expected = exp.toByteArray();
        assertEquals("Type-0x1E body length matches §1.2 layout",
                expected.length, body.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("§1.2 body byte @" + i,
                    expected[i] & 0xFF, body[i] & 0xFF);
        }
    }

    /**
     * Functional: a curated scripted NPC whose display name differs
     * from its AI script must place the <em>script name</em> in the
     * SCRIPTEDPLAYER stream (RE_state_sync.md §1.2 / §4.2 — an empty or
     * wrong token =&gt; {@code FUN_00699fd0} logs
     * {@code "SCRIPTEDPLAYER : Script spawn failed"} and the NPC never
     * instantiates). Cross-checked against retail entity 325
     * ("PATROL_COPBOT6").
     */
    @Test
    public void scriptedNpcStreamCarriesScriptNameNotDisplayName() {
        NPC npc = new NPC(325, 1, 0x27e2, "Copbot",
                "PATROL_COPBOT6", "", 0, 0, 0, 45, 100, 0);
        byte[] body = innerBody(datagram(new ScriptedPlayerSpawn(
                PacketTestFixture.newPlayerWithFixedSessionKey(
                        (short) 0), npc)));
        int o = 7 + 0x1d;
        byte[] script = "PATROL_COPBOT6"
                .getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < script.length; i++) {
            assertEquals("stream[0x1d..] must be the AI script name",
                    script[i], body[o + i]);
        }
        assertEquals("script NUL", 0x00, body[o + script.length] & 0xFF);
        assertNotEquals("must not emit display name as script token",
                (int) 'C', body[o] & 0xFF);
    }

    /**
     * The Type-0x1E create and the {@code 0x28} WorldInfo refresh must
     * agree on the shared identity/script fields (RE_state_sync.md §4.2:
     * the client keys the entity on these). The id and script token are
     * resolved from the same {@link NPC} the same way in both emitters.
     */
    @Test
    public void createAndRefreshAgreeOnIdentity() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(299, 7, 0x1234, "WCOP",
                100, 200, 300, 90, 100, 0);

        byte[] spawn = innerBody(datagram(
                new ScriptedPlayerSpawn(pl, npc)));

        DatagramPacket wdp = new WorldNPCInfo(pl, npc)
                .getDatagramPackets()[0];
        byte[] world = new byte[wdp.getLength()];
        System.arraycopy(wdp.getData(), 0, world, 0, world.length);

        // 0x28 entity id is at doc [3..4] -> datagram[11+2..]; the
        // Type-0x1E entity id is body[3..6] LE32.
        int worldEntityId = (world[13] & 0xFF) | ((world[14] & 0xFF) << 8);
        assertEquals("0x1e and 0x28 carry the same entity id",
                worldEntityId, (int) u32(spawn, 3));
        // 0x28 script token starts at doc [36] -> datagram[11+35].
        int wo = 11 + 35;
        int so = 7 + 0x1d;
        byte[] sc = "WCOP".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < sc.length; i++) {
            assertEquals("script token agrees @" + i,
                    world[wo + i] & 0xFF, spawn[so + i] & 0xFF);
        }
    }
}
