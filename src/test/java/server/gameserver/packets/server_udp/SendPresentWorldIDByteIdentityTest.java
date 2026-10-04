package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.Player;

/**
 * Byte-identity test for {@link SendPresentWorldID} — the raw
 * {@code 0x1b} world-actor PLACEMENT record the client instantiates a
 * renderable NPC from (the missing link in the bridged-mob render
 * chain, restored 2026-06-14).
 *
 * <p>Retail reference (re-decoded from
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap}, WSK entity
 * 266 = {@code 0x010a}, one of 92 samples):
 * <pre>
 *   1b 0a01 0000 1f a078 107f 708a 40 bd030000 ff11
 * </pre>
 * Load-bearing layout (the bytes that must be byte-exact for the
 * client to place the actor): {@code [0]=1b}, {@code [1-2]=entity_id},
 * {@code [3-4]=00 00}, {@code [5]=1f}, {@code [6-11]=Y/Z/X} (identical
 * to that NPC's {@code 0x28}), {@code [12]=40} route flag. The tail
 * {@code [13-16]} heading + {@code [17-18]} phase is per-tick idle
 * look-around animation — not load-bearing — so it is not pinned to an
 * exact value here.
 */
public class SendPresentWorldIDByteIdentityTest {

    /** Raw 0x13 datagram (PacketBuilderUDP13, no 0x03 reliable layer):
     *  {@code [0x13][ctr LE2][ctr+sk LE2][subLen LE2]} then the 0x1b
     *  body at wire offset 7. */
    private static byte[] body(SendPresentWorldID pkt) {
        DatagramPacket dp = pkt.getDatagramPackets()[0];
        byte[] d = dp.getData();
        assertEquals("outer 0x13", 0x13, d[0] & 0xFF);
        int subLen = (d[5] & 0xFF) | ((d[6] & 0xFF) << 8);
        byte[] b = new byte[subLen];
        System.arraycopy(d, 7, b, 0, subLen);
        return b;
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    @Test
    public void placementBodyIsNineteenBytesWithRetailLayout() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        // NPC ctor is (x, y, z, hp, armor, type, id), world units. The
        // retail WSK wire coords Y=0x78a0 / Z=0x7f10 / X=0x8a70 are the
        // plaza_p1 world_npcs npc 11 position (2672, -1888, -239):
        // world + 0x8000, Z one lower (NPC.getWireX).
        NPC npc = new NPC(2672, -1888, -239, 100, 0, 0x0b4a, 0x010a);
        byte[] b = body(new SendPresentWorldID(pl, npc));

        assertEquals("19-byte placement record", 19, b.length);
        assertEquals("[0] sub-opcode",        0x1b, b[0] & 0xFF);
        assertEquals("[1-2] entity_id",       0x010a, le16(b, 1));
        assertEquals("[3] zero",              0x00, b[3] & 0xFF);
        assertEquals("[4] zero",              0x00, b[4] & 0xFF);
        assertEquals("[5] flag",              0x1f, b[5] & 0xFF);
        assertEquals("[6-7] Y",               0x78a0, le16(b, 6));
        assertEquals("[8-9] Z",               0x7f10, le16(b, 8));
        assertEquals("[10-11] X",             0x8a70, le16(b, 10));
        assertEquals("[12] route flag 0x40",  0x40, b[12] & 0xFF);
    }

    @Test
    public void coordsAreByteIdenticalToWorldNPCInfo() {
        // The whole point: the 0x1b placement and the 0x28 create must
        // carry the SAME Y/Z/X (retail does, byte-for-byte) or the
        // actor instantiates at a different spot than it is named.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(1234, -567, 89, 100, 0, 0x27d5, 0x0101);
        byte[] b = body(new SendPresentWorldID(pl, npc));
        assertEquals(npc.getWireY(), le16(b, 6));
        assertEquals(npc.getWireZ(), le16(b, 8));
        assertEquals(npc.getWireX(), le16(b, 10));
        assertEquals("entity id matches mapID", npc.getMapID(), le16(b, 1));
    }

    @Test
    public void routeFlagIsNotTheOldMalformed0x42() {
        // Regression guard: the pre-fix body wrote route 0x42 + HP +
        // random tail (17 B) which the client GC'd. Never again.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(1, 2, 3, 100, 0, 20, 0x010a);
        byte[] b = body(new SendPresentWorldID(pl, npc));
        assertEquals(19, b.length);
        assertEquals(0x40, b[12] & 0xFF);
        assertNotEquals(0x42, b[12] & 0xFF);
    }
}
