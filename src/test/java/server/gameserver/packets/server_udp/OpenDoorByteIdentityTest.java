package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link OpenDoor}.
 *
 * <p>Wire path: {@code 0x13 → 0x03 → 0x1b → [body 11B]}. Same
 * outer family as {@link PlayerPositionUpdate} (which is the
 * position-authority variant of {@code 0x03/0x1b}); OpenDoor is
 * the door-trigger variant — uses the same outer/inner opcodes
 * but a different 11-byte body.
 *
 * <p>Body layout (currently emitted by Ceres-J):
 * <pre>
 *   [0..3] door_id LE32
 *   [4]    0x20             marker
 *   [5..8] LE32 zero
 *   [9]    0x03
 *   [10]   0x15
 * </pre>
 *
 * <p>Catalog evidence is mixed for {@code 0x03/0x1b} (1104 hits
 * dominated by position-authority); OpenDoor's specific bytes
 * aren't independently pinned in the catalog. This test is a
 * regression net on Ceres-J's current emission.
 */
public class OpenDoorByteIdentityTest {

    private static byte[] datagramBytes(OpenDoor pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Body starts at offset 11 (PacketBuilderUDP1303). */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x1b", 0x1b, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void bodyLayoutIdMarkerZeroPadding() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(
                datagramBytes(new OpenDoor(0x12345678, pl)), 11);
        // [0..3] door_id LE32
        assertEquals(0x78, body[0] & 0xFF);
        assertEquals(0x56, body[1] & 0xFF);
        assertEquals(0x34, body[2] & 0xFF);
        assertEquals(0x12, body[3] & 0xFF);
        // [4] marker
        assertEquals(0x20, body[4] & 0xFF);
        // [5..8] LE32 zero
        assertEquals(0x00, body[5] & 0xFF);
        assertEquals(0x00, body[6] & 0xFF);
        assertEquals(0x00, body[7] & 0xFF);
        assertEquals(0x00, body[8] & 0xFF);
        // [9..10] trailing 03 15
        assertEquals(0x03, body[9]  & 0xFF);
        assertEquals(0x15, body[10] & 0xFF);
    }

    @Test
    public void doorIdEncodesLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(
                datagramBytes(new OpenDoor(0xDEADBEEF, pl)), 11);
        assertEquals(0xEF, body[0] & 0xFF);
        assertEquals(0xBE, body[1] & 0xFF);
        assertEquals(0xAD, body[2] & 0xFF);
        assertEquals(0xDE, body[3] & 0xFF);
    }

    @Test
    public void totalDatagramSizeIsTwentyTwoBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 1 (0x1b) + 11 (body) = 22 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(22, datagramBytes(new OpenDoor(0, pl)).length);
    }

    @Test
    public void multipleInstancesProduceIdenticalBytesForSameId() {
        // Same door_id → same body. Catches accidental dependency
        // on instance state.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] a = extractInnerBody(
                datagramBytes(new OpenDoor(42, pl)), 11);
        byte[] b = extractInnerBody(
                datagramBytes(new OpenDoor(42, pl)), 11);
        assertArrayEquals(a, b);
    }

    @Test
    public void bodyDistinctFromPlayerPositionUpdate() {
        // OpenDoor and PlayerPositionUpdate share outer (0x03/0x1b)
        // but emit different 11-byte bodies. Pin the difference so
        // a future "merge into one class" refactor can't regress.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        byte[] door = extractInnerBody(
                datagramBytes(new OpenDoor(0x12345678, pl)), 11);
        // Inline the PlayerPositionUpdate datagram extraction since
        // datagramBytes() is overloaded for OpenDoor only.
        DatagramPacket[] dps = new PlayerPositionUpdate(
                pl, pl.getCharacter(), 0x12345678).getDatagramPackets();
        byte[] full = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, full, 0, full.length);
        byte[] pos = new byte[11];
        System.arraycopy(full, 11, pos, 0, 11);

        // The trailing animation byte differs: OpenDoor uses 0x15,
        // PlayerPositionUpdate uses 0x14.
        assertNotEquals("trailing bytes must distinguish OpenDoor "
                + "from PlayerPositionUpdate",
                door[10], pos[10]);
    }
}
