package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.Player;

/**
 * Byte-identity test for {@link NpcDataBroadcast}.
 *
 * <p>Wire path: {@code 0x13 → 0x03 → 0x2d → 8B body}. Used as
 * the per-NPC ~3.3 Hz heartbeat. Without it the world feels
 * dead and the client's world-alive watchdog eventually fires.
 *
 * <p>Ceres-J emits 8 bytes; the cataloged short retail variant
 * is 9 bytes ({@code 00 f8 09 00 08 bc 34 b5 42}) — a 1-byte
 * size discrepancy. The retail trailing 4 bytes look like a
 * float (0x42b534bc ≈ 90.6f) rather than the 4-zero padding
 * Ceres-J writes. Fixing the emitter requires more retail
 * decode evidence than is currently available; the test below
 * pins Ceres-J's current behaviour as a regression net and
 * documents the open question.
 */
public class NpcDataBroadcastByteIdentityTest {

    private static byte[] datagramBytes(NpcDataBroadcast pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Body starts at offset 11 (PacketBuilderUDP1303). */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x2d", 0x2d, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void bodyLayoutForKnownNpc() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(0, 0, 0, 100, 0, 1, 0xABCD);

        byte[] body = extractInnerBody(
                datagramBytes(new NpcDataBroadcast(pl, npc)), 8);
        // [0..1] mapID LE16 = 0xABCD
        assertEquals(0xCD, body[0] & 0xFF);
        assertEquals(0xAB, body[1] & 0xFF);
        // [2] padding 0x00
        assertEquals(0x00, body[2] & 0xFF);
        // [3] sub-block marker 0x08
        assertEquals(0x08, body[3] & 0xFF);
        // [4..7] 4 zero bytes
        for (int i = 4; i < 8; i++) {
            assertEquals("zero byte at offset " + i,
                    0x00, body[i] & 0xFF);
        }
    }

    @Test
    public void totalDatagramSizeIsNineteenBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 1 (0x2d) + 8 (body) = 19 bytes
        // (NOTE: catalog short variant is 9B inner / 20B total —
        // see class docstring on the open size discrepancy.)
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(0, 0, 0, 100, 0, 0, 1);
        assertEquals(19, datagramBytes(
                new NpcDataBroadcast(pl, npc)).length);
    }

    @Test
    public void mapIdEncodesLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(0, 0, 0, 100, 0, 0, 0xDEAD);
        byte[] body = extractInnerBody(
                datagramBytes(new NpcDataBroadcast(pl, npc)), 8);
        assertEquals(0xAD, body[0] & 0xFF);
        assertEquals(0xDE, body[1] & 0xFF);
    }

    @Test
    public void multipleNpcsProduceDifferentBodyByMapIdOnly() {
        // Body bytes [2..7] are constant; only mapID at [0..1]
        // should vary across NPCs.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        NPC a = new NPC(0, 0, 0, 100, 0, 0, 1);
        NPC b = new NPC(99, 88, 77, 50, 5, 9, 2);

        byte[] bodyA = extractInnerBody(
                datagramBytes(new NpcDataBroadcast(pl, a)), 8);
        byte[] bodyB = extractInnerBody(
                datagramBytes(new NpcDataBroadcast(pl, b)), 8);
        // mapId differs
        assertNotEquals(bodyA[0], bodyB[0]);
        // Bytes [2..7] identical
        for (int i = 2; i < 8; i++) {
            assertEquals("non-mapId byte " + i + " must be NPC-state-independent",
                    bodyA[i], bodyB[i]);
        }
    }
}
