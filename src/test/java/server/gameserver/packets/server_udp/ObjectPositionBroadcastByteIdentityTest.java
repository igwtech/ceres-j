package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.NPC;
import server.gameserver.Player;

/**
 * Byte-identity test for {@link ObjectPositionBroadcast} —
 * the raw (unreliable) {@code 0x1b} object-position
 * broadcast that drives the modern NCE 2.5 client's
 * world-alive watchdog at ~7.7 Hz.
 *
 * <p>Without these the "SYNCHRONIZING INTO CITY ZONE"
 * overlay reappears and the session times out after
 * ~10–25 s.
 *
 * <p>Wire format (19-byte body):
 *
 * <pre>
 *   [0]      0x1b            sub-packet type
 *   [1..2]   object_id LE16
 *   [3..4]   00 00           padding
 *   [5]      0x1f            inner opcode (constant)
 *   [6..7]   Y LE16          (raw + 32000, world coord)
 *   [8..9]   Z LE16
 *   [10..11] X LE16
 *   [12]     orientation     (0x40 default heading)
 *   [13..14] status LE16
 *   [15..16] 00 00           padding
 *   [17..18] 11 11           trailer
 * </pre>
 */
public class ObjectPositionBroadcastByteIdentityTest {

    private static byte[] datagramBytes(ObjectPositionBroadcast pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP13): body starts at offset 7. */
    private static byte[] extractInnerBody(byte[] datagram) {
        assertEquals("outer 0x13", 0x13, datagram[0] & 0xFF);
        byte[] body = new byte[19];
        System.arraycopy(datagram, 7, body, 0, 19);
        return body;
    }

    @Test
    public void bodyLayoutForKnownNpc() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        // mapID=0xABCD, pos (1000, 2000, 3000), angle=0
        NPC npc = new NPC(1000, 2000, 3000, 100, 0, 1, 0xABCD);

        byte[] body = extractInnerBody(
                datagramBytes(new ObjectPositionBroadcast(pl, npc)));

        // [0] 0x1b sub-packet type
        assertEquals(0x1b, body[0] & 0xFF);
        // [1..2] object_id LE16 = 0xABCD
        assertEquals(0xCD, body[1] & 0xFF);
        assertEquals(0xAB, body[2] & 0xFF);
        // [3..4] padding
        assertEquals(0x00, body[3] & 0xFF);
        assertEquals(0x00, body[4] & 0xFF);
        // [5] 0x1f inner opcode
        assertEquals(0x1f, body[5] & 0xFF);
        // [6..7] Y = 2000 + 32000 = 34000 = 0x84D0
        assertEquals(0xD0, body[6] & 0xFF);
        assertEquals(0x84, body[7] & 0xFF);
        // [8..9] Z = 3000 + 32000 = 35000 = 0x88B8
        assertEquals(0xB8, body[8] & 0xFF);
        assertEquals(0x88, body[9] & 0xFF);
        // [10..11] X = 1000 + 32000 = 33000 = 0x80E8
        assertEquals(0xE8, body[10] & 0xFF);
        assertEquals(0x80, body[11] & 0xFF);
        // [12] angle defaults to 0x40 when input is 0
        assertEquals(0x40, body[12] & 0xFF);
        // [13..16] status + padding zeros
        for (int i = 13; i <= 16; i++) {
            assertEquals("zero byte at offset " + i,
                    0x00, body[i] & 0xFF);
        }
        // [17..18] trailer 11 11
        assertEquals(0x11, body[17] & 0xFF);
        assertEquals(0x11, body[18] & 0xFF);
    }

    @Test
    public void totalDatagramSizeIsTwentySixBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   19 (body) = 26 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(0, 0, 0, 100, 0, 0, 1);
        assertEquals(26, datagramBytes(
                new ObjectPositionBroadcast(pl, npc)).length);
    }

    @Test
    public void positionFieldsAreOffsetByThirtyTwoThousand() {
        // Coordinates are stored as raw + 32000 (signed-to-unsigned
        // shift). 0 → 32000 = 0x7D00.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(0, 0, 0, 100, 0, 0, 1);

        byte[] body = extractInnerBody(
                datagramBytes(new ObjectPositionBroadcast(pl, npc)));
        // X=Y=Z=0 → all encode 0x7D00
        for (int off : new int[]{6, 8, 10}) {
            assertEquals("LE lo at offset " + off,
                    0x00, body[off] & 0xFF);
            assertEquals("LE hi at offset " + (off + 1),
                    0x7D, body[off + 1] & 0xFF);
        }
    }

    @Test
    public void nonZeroAngleIsEmittedRaw() {
        // The emitter uses 0x40 ONLY when angle is 0; otherwise
        // it writes the raw angle byte.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(255, 0, 90, "tester", 0, 0, 0,
                0x37,   // angle (non-zero)
                100, 0);

        byte[] body = extractInnerBody(
                datagramBytes(new ObjectPositionBroadcast(pl, npc)));
        assertEquals("non-zero angle emitted raw at offset 12",
                0x37, body[12] & 0xFF);
    }

    @Test
    public void coordinatesEncodeLittleEndian() {
        // Distinct values per axis to catch a swap.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        // X=10, Y=20, Z=30 → after +32000:
        //   X=32010=0x7D0A, Y=32020=0x7D14, Z=32030=0x7D1E
        NPC npc = new NPC(10, 20, 30, 100, 0, 1, 1);
        byte[] body = extractInnerBody(
                datagramBytes(new ObjectPositionBroadcast(pl, npc)));
        // Y at [6..7]
        assertEquals(0x14, body[6] & 0xFF);
        assertEquals(0x7D, body[7] & 0xFF);
        // Z at [8..9]
        assertEquals(0x1E, body[8] & 0xFF);
        assertEquals(0x7D, body[9] & 0xFF);
        // X at [10..11]
        assertEquals(0x0A, body[10] & 0xFF);
        assertEquals(0x7D, body[11] & 0xFF);
    }

    @Test
    public void trailerByteIsAlwaysElevenEleven() {
        // 0x11 0x11 dominant trailer in retail samples (also
        // 0x11 0x0f observed but Ceres-J commits to 11 11).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        NPC npc = new NPC(0, 0, 0, 100, 0, 0, 1);
        byte[] body = extractInnerBody(
                datagramBytes(new ObjectPositionBroadcast(pl, npc)));
        assertEquals(0x11, body[17] & 0xFF);
        assertEquals(0x11, body[18] & 0xFF);
    }
}
