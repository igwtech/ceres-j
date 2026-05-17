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
 * <p>Pinned 2026-05-16 against retail capture AUGUSTO sample #0
 * (NPC "PMAN", entity 292) and 330 further {@code 0x03/0x28}
 * packets across the AUGUSTO / NORMAN / DRSTONE / PLAZA-&gt;PEPPER
 * captures (see {@code docs/protocol/packets/udp_s2c_03_28.md}).
 *
 * <pre>
 * RETAIL AUGUSTO #0 (0x28 inner):
 *   0001 2401 0000 6a519a37 4f01 c877 007e 7f7c 00
 *   ca060000 00000000000000000000 00 00 504d414e00 3100
 * </pre>
 *
 * <p>The structurally-constant bytes are pinned verbatim against the
 * retail sample. The {@code [6..9]} instance handle (231 distinct
 * values / 331 packets; heap-pointer derived and unreproducible) is
 * pinned by the invariant the client enforces: unique per NPC and
 * stable across packets, never the discredited constant 8958887
 * (0/331 retail packets).
 */
public class WorldNPCInfoByteIdentityTest {

    /** Retail AUGUSTO sample #0, full 0x28 inner (incl. sub-op). */
    private static final byte[] RETAIL_AUGUSTO_PMAN = hex(
        "28" + "0001" + "2401" + "0000" + "6a519a37" + "4f01"
      + "c877" + "007e" + "7f7c" + "00" + "ca060000"
      + "00000000000000000000" + "00" + "00"
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

    /** Body starts at offset 11 (PacketBuilderUDP1303 header). */
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
        // RETAIL: ent=292, class=0x014f, X=31871 Y=30664 Z=32256,
        // name "PMAN", orientation "1". maxHp pinned to retail's
        // [19..22] class_attr 0x06ca = 1738 so that derived field is
        // byte-exact for this sample.
        NPC npc = new NPC(292, 7, 0x014f, "PMAN",
                31871, 30664, 32256, 1, 1738, 0);

        byte[] body = extractInnerBody(
                datagramBytes(new WorldNPCInfo(pl, npc)),
                RETAIL_AUGUSTO_PMAN.length);

        for (int i = 0; i < RETAIL_AUGUSTO_PMAN.length; i++) {
            // body offsets [7..10] = the 0x28-inner [6..9] instance
            // handle: retail is a server heap handle; ours is the
            // deterministic getWorldInstanceHandle(). Asserted in
            // instanceHandleIsPerNpcUniqueAndStable.
            if (i >= 7 && i <= 10) continue;
            assertEquals("byte @" + i + " must match retail AUGUSTO PMAN",
                    RETAIL_AUGUSTO_PMAN[i] & 0xFF, body[i] & 0xFF);
        }
    }

    @Test
    public void instanceHandleIsPerNpcUniqueAndStable() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC a1 = new NPC(0, 1, 0, "A", 0, 0, 0, 0, 100, 0);
        a1.setMapID(257);
        NPC a2 = new NPC(0, 1, 0, "B", 0, 0, 0, 0, 100, 0);
        a2.setMapID(258);

        long h1a = le32(extractInnerBody(
                datagramBytes(new WorldNPCInfo(pl, a1)), 39), 7);
        long h1b = le32(extractInnerBody(
                datagramBytes(new WorldNPCInfo(pl, a1)), 39), 7);
        long h2  = le32(extractInnerBody(
                datagramBytes(new WorldNPCInfo(pl, a2)), 39), 7);

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
    public void trailingStringsAreTypeNameThenOrientation() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(257, 1, 5, "WSK", 0, 0, 0, -90, 100, 0);

        DatagramPacket dp = new WorldNPCInfo(pl, npc)
                .getDatagramPackets()[0];
        byte[] full = new byte[dp.getLength()];
        System.arraycopy(dp.getData(), 0, full, 0, full.length);
        // body after the 0x28 sub-op starts at datagram[11]; the
        // string region begins at body offset 35 -> datagram[11+35].
        int o = 11 + 35;
        byte[] name = "WSK".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < name.length; i++) {
            assertEquals("name byte " + i, name[i], full[o + i]);
        }
        assertEquals("name NUL", 0x00, full[o + 3] & 0xFF);
        byte[] ori = "-90".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < ori.length; i++) {
            assertEquals("orient byte " + i, ori[i], full[o + 4 + i]);
        }
        assertEquals("orient NUL", 0x00, full[o + 4 + 3] & 0xFF);
    }
}
