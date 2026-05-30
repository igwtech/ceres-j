package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.Player;

/**
 * Pins the {@link ZoneStateCompoundPacket} per-NPC zone-state refresh
 * to the retail-evidenced two-datagram chain.
 *
 * <p><strong>Byte-pinned 2026-05-17 (task #178d)</strong> from the
 * live retail pcap
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} (server
 * 157.90.195.74), machine-decoded with the corrected reliable frame
 * parser ({@code tools/npc-lifecycle.py}).
 *
 * <p>The decisive retail finding: a persistently-rendered scripted
 * city NPC (entities 266 "WSK" / 299 "WCOP" / 325 "PATROL_COPBOT6")
 * receives ONLY a reliable {@code 0x03/0x28} WorldInfo plus a
 * reliable {@code 0x03/0x2d} 6-byte ping. It NEVER receives a raw
 * {@code 0x1b} (every {@code 0x1b} in the capture carries a small
 * world-item id {@code 0x8b..0xdb}; scripted NPC ids are
 * {@code >= 0x0100} and appear only in {@code 0x28}/{@code 0x2d}).
 *
 * <p>The pre-#178d packet emitted THREE datagrams including a raw
 * {@code 0x1b} keyed on the NPC id — that registered the NPC in the
 * client's transient world-item table, which the client GC'd within a
 * frame ("appears then disappears"). It also emitted a 55-byte
 * {@code 0x2d} template (sub-action 0xf4, entity at {@code [7..8]})
 * from an older capture that does not match this class. Both are
 * corrected here: no {@code 0x1b}, and the deterministic 6-byte
 * {@code 0x2d} ping.
 */
public class NpcData2dByteIdentityTest {

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    /** Walks a bundled {@code 0x13} datagram and returns every
     *  reliable sub-packet's body bytes (post {@code 0x03 [seq LE2]}).
     *  Each entry starts with the inner sub-op byte at index 0.
     *  Handles both single-sub and multi-sub datagrams uniformly. */
    private static java.util.List<byte[]> subs(DatagramPacket dp) {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        byte[] d = dp.getData();
        int len = dp.getLength();
        int i = 5; // past [0x13][ctr LE2][ctr+sk LE2]
        while (i + 2 <= len) {
            int subLen = (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
            i += 2;
            if (subLen <= 0 || i + subLen > len) break;
            // Reliable sub-packet body = sub[3:] (post 0x03 + seq LE2)
            if (subLen >= 3 && (d[i] & 0xFF) == 0x03) {
                byte[] body = new byte[subLen - 3];
                System.arraycopy(d, i + 3, body, 0, body.length);
                out.add(body);
            }
            i += subLen;
        }
        return out;
    }

    /** First-sub inner-op byte. Back-compat with the old single-sub
     *  assertion contract. */
    private static int subOp(DatagramPacket dp) {
        return subs(dp).get(0)[0] & 0xFF;
    }

    /** First-sub inner body. */
    private static byte[] body(DatagramPacket dp) {
        return subs(dp).get(0);
    }

    @Test
    public void emitsBundledReliableDatagram_28then2d_no1b() {
        // 2026-05-30 bundling: 0x28 + 0x2d ride as TWO sub-packets in
        // ONE 0x13 datagram (was two separate datagrams pre-fix). The
        // retail two-sub-packet ordering and absence of any 0x1b are
        // preserved; only the wire framing changes.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(1234, -567, 89, 100, 0, 20, 0x010A);
        DatagramPacket[] dps =
                new ZoneStateCompoundPacket(pl, npc)
                        .getDatagramPackets();

        assertEquals("bundled into exactly 1 0x13 datagram",
                1, dps.length);
        assertEquals("outer 0x13", 0x13, dps[0].getData()[0] & 0xFF);
        assertEquals("reliable 0x03 at first sub",
                0x03, dps[0].getData()[7] & 0xFF);

        java.util.List<byte[]> bodies = subs(dps[0]);
        assertEquals("exactly 2 reliable sub-packets",
                2, bodies.size());
        assertEquals("sub 1 must be 0x28 WorldInfo",
                0x28, bodies.get(0)[0] & 0xFF);
        assertEquals("sub 2 must be 0x2d NPCData",
                0x2d, bodies.get(1)[0] & 0xFF);
        // No sub-packet is a raw 0x1b — the spurious world-item
        // registration that despawned the NPC pre-#178d.
        for (byte[] b : bodies) {
            assertNotEquals("no raw 0x1b for a scripted NPC",
                    0x1b, b[0] & 0xFF);
        }
    }

    @Test
    public void npcData2dIsRetail6ByteacPing() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(1234, -567, 89, 100, 0, 20, 0x010A);
        DatagramPacket[] dps =
                new ZoneStateCompoundPacket(pl, npc)
                        .getDatagramPackets();

        byte[] tick = subs(dps[0]).get(1);
        // Retail entity 266 idle tick: 2d 0a01 0000 06
        assertArrayEquals(
                new byte[] { 0x2d, 0x0a, 0x01, 0x00, 0x00, 0x06 },
                tick);
    }

    @Test
    public void worldInfo28CarriesTheSameEntityIdAsThe2dPing() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        int id = 0x012B;   // retail "WCOP"
        NPC npc = new NPC(10, 20, 30, 100, 0, 1, id);
        DatagramPacket[] dps =
                new ZoneStateCompoundPacket(pl, npc)
                        .getDatagramPackets();

        java.util.List<byte[]> bodies = subs(dps[0]);
        byte[] world = bodies.get(0);   // 28 0001 [id LE2] ...
        byte[] tick  = bodies.get(1);   // 2d [id LE2] 0000 06

        assertEquals(0x28, world[0] & 0xFF);
        assertEquals("0x28 world-object id @[3..4]",
                id, u16(world, 3));
        assertEquals(0x2d, tick[0] & 0xFF);
        assertEquals("0x2d entity id @[1..2]",
                id, u16(tick, 1));
        // The id the client creates the actor from (0x28) and the id
        // the keep-alive ping refreshes (0x2d) MUST be the same, or
        // the client ages out the actor it just created.
        assertEquals(u16(world, 3), u16(tick, 1));
    }
}
