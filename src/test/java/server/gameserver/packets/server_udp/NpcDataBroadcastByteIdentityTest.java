package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.Player;

/**
 * Byte-identity test for {@link NpcDataBroadcast}.
 *
 * <p>Wire path: {@code 0x13 -> 0x03 -> 0x2d -> 5B body}, total
 * 6-byte inner ({@code 2d [entityId LE2] 00 00 06}).
 *
 * <p><strong>Byte-pinned 2026-05-17 (task #178d)</strong> from the
 * live retail pcap
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} (server
 * 157.90.195.74), machine-decoded with {@code tools/npc-lifecycle.py}.
 * The 0x2d NPC tick for a stationary scripted-city NPC (retail
 * entities 266 "WSK" / 299 "WCOP" / 325 "PATROL_COPBOT6") is the
 * deterministic 6-byte ping
 * {@code 2d [entityId LE2] 0000 06} — e.g. entity 266:
 * {@code 2d 0a01 0000 06}.
 *
 * <p>This replaces the pre-#178d pin of a 10-byte body
 * {@code [id LE2][00][08][00 00 00 00]} which was the player-self
 * 0x2d layout, not an NPC tick — it made the client log
 * {@code LSTPLAYER : Update Message corrupted Size:19 31} and drop
 * the just-created actor.
 */
public class NpcDataBroadcastByteIdentityTest {

    private static byte[] datagramBytes(NpcDataBroadcast pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Inner body (incl. the 0x2d sub-op) starts at wire offset 10:
     *  0x13(1)+ctr(2)+ctr+sk(2)+subLen(2)+0x03(1)+seq(2) = 10. */
    private static byte[] innerBody(byte[] datagram) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x2d", 0x2d, datagram[10] & 0xFF);
        byte[] body = new byte[datagram.length - 10];
        System.arraycopy(datagram, 10, body, 0, body.length);
        return body;
    }

    @Test
    public void sixByteRetailPingForKnownNpc() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        // entity 266 (0x010a) — the retail "WSK" sample.
        NPC npc = new NPC(0, 0, 0, 100, 0, 20, 0x010A);

        byte[] body = innerBody(
                datagramBytes(new NpcDataBroadcast(pl, npc)));

        // Exactly the retail 6-byte ping: 2d 0a01 0000 06
        assertArrayEquals(
                new byte[] { 0x2d, 0x0a, 0x01, 0x00, 0x00, 0x06 },
                body);
    }

    @Test
    public void entityIdEncodesLittleEndian() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(0, 0, 0, 100, 0, 0, 0xDEAD);
        byte[] body = innerBody(
                datagramBytes(new NpcDataBroadcast(pl, npc)));
        assertEquals(0x2d, body[0] & 0xFF);
        assertEquals(0xAD, body[1] & 0xFF);   // id lo
        assertEquals(0xDE, body[2] & 0xFF);   // id hi
        assertEquals(0x00, body[3] & 0xFF);
        assertEquals(0x00, body[4] & 0xFF);
        assertEquals(0x06, body[5] & 0xFF);   // form discriminator
    }

    @Test
    public void totalDatagramSizeIsSixteenBytes() {
        // 1 (0x13) + 2 (ctr) + 2 (ctr+sk) + 2 (subLen) +
        //   1 (0x03) + 2 (seq) + 6 (body) = 16 bytes.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(0, 0, 0, 100, 0, 0, 1);
        assertEquals(16, datagramBytes(
                new NpcDataBroadcast(pl, npc)).length);
    }

    @Test
    public void onlyEntityIdVariesAcrossNpcs() {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        NPC a = new NPC(0, 0, 0, 100, 0, 0, 1);
        NPC b = new NPC(99, 88, 77, 50, 5, 9, 2);

        byte[] ba = innerBody(datagramBytes(new NpcDataBroadcast(pl, a)));
        byte[] bb = innerBody(datagramBytes(new NpcDataBroadcast(pl, b)));
        // entity id differs at [1]
        assertNotEquals(ba[1], bb[1]);
        // every other byte is NPC-state independent
        for (int i : new int[] { 0, 2, 3, 4, 5 }) {
            assertEquals("non-id byte " + i + " must be constant",
                    ba[i], bb[i]);
        }
    }
}
