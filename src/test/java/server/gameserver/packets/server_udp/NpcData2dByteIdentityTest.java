package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.Player;

/**
 * Pins the reliable {@code 0x03/0x2d} NPCData datagram emitted by
 * {@link ZoneStateCompoundPacket} to the verified retail 55-byte
 * NPC-tick form (task #167/#177).
 *
 * <p>The body must be byte-identical to the pinned retail sample
 * (sub-action 0xf4, category 0x0003) EXCEPT the per-NPC overlay:
 * the 2-byte entity id at [7..8] and the five float32 slots at
 * [15..34]. The framing, the 0xffffffff sentinel and the
 * invariant trailer must match retail verbatim — that is what the
 * client's world-actor parser keys on (a wrong length/shape made
 * it log "Update Message corrupted" / "Unable to Spawn WA" and
 * never render NPCs).
 */
public class NpcData2dByteIdentityTest {

    /** Retail-verified template (same constant as the builder). */
    private static final byte[] TPL = hex(
        "2df4030000712085549b45ffffffff457ba93844d78a5645d063ac45"
      + "7be93044d78a564543000080060000000100000081ca0900709c53");

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(
                s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
             | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    /** Strip {@code [0x13][ctr2][ctr+sk2][subLen2][0x03][seq2]}
     *  (10-byte reliable prefix) → the 0x2d body. dps[1] is the
     *  NPCData datagram (0=0x1b bcast, 2=0x28 worldinfo). */
    private static byte[] npcBody(DatagramPacket[] dps) {
        byte[] full = dps[1].getData();
        int len = dps[1].getLength();
        byte[] body = new byte[len - 10];
        System.arraycopy(full, 10, body, 0, body.length);
        return body;
    }

    @Test
    public void npcDataIs55ByteRetailForm() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        // NPC(x, y, z, hp, armor, type, id)
        NPC npc = new NPC(1234, -567, 89, 100, 0, 42, 0x0137);
        ZoneStateCompoundPacket pkt =
                new ZoneStateCompoundPacket(pl, npc);
        byte[] b = npcBody(pkt.getDatagramPackets());

        assertEquals("0x2d body must be 55 bytes", 55, b.length);

        // Framing / sub-action / category — verbatim from retail.
        assertEquals(0x2d, b[0] & 0xFF);
        assertEquals(0xf4, b[1] & 0xFF);          // sub-action
        assertEquals(0x03, b[2] & 0xFF);          // category LE16
        assertEquals(0x00, b[3] & 0xFF);
        assertEquals(0x00, b[4] & 0xFF);
        assertEquals(0x71, b[5] & 0xFF);          // entity desc const
        assertEquals(0x20, b[6] & 0xFF);
        assertEquals(0x9b, b[9] & 0xFF);
        assertEquals(0x45, b[10] & 0xFF);
        // 0xffffffff sentinel
        for (int i = 11; i <= 14; i++) {
            assertEquals("sentinel @" + i, 0xFF, b[i] & 0xFF);
        }

        // Per-NPC overlay: entity id LE16 at [7..8].
        assertEquals(0x0137,
                (b[7] & 0xFF) | ((b[8] & 0xFF) << 8));

        // Five float32 slots = X, Y, Z, angle, Y.
        assertEquals(Float.floatToIntBits(1234f), le32(b, 15));
        assertEquals(Float.floatToIntBits(-567f), le32(b, 19));
        assertEquals(Float.floatToIntBits(89f),   le32(b, 23));
        assertEquals(Float.floatToIntBits(
                (float) npc.getAngle()),          le32(b, 27));
        assertEquals(Float.floatToIntBits(-567f), le32(b, 31));

        // Trailer [35..54] (incl. the invariant block) byte-exact
        // vs the retail template.
        for (int i = 35; i < 55; i++) {
            assertEquals("trailer @" + i,
                    TPL[i] & 0xFF, b[i] & 0xFF);
        }
    }

    @Test
    public void framingMatchesTemplateExceptOverlay() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(0, 0, 0, 100, 0, 1, 0);
        byte[] b = npcBody(
                new ZoneStateCompoundPacket(pl, npc)
                        .getDatagramPackets());
        // Every byte outside the overlay windows [7..8] and
        // [15..34] must equal the retail template.
        for (int i = 0; i < 55; i++) {
            if (i == 7 || i == 8 || (i >= 15 && i <= 34)) continue;
            assertEquals("template byte @" + i,
                    TPL[i] & 0xFF, b[i] & 0xFF);
        }
    }
}
