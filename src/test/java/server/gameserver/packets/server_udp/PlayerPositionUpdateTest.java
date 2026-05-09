package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;

/**
 * Byte-identical regression test for {@link PlayerPositionUpdate}
 * (UDP S→C reliable {@code 0x03/0x1b}). Pins the 11-byte body
 * against retail catalog samples so the marker byte 0x20 — the
 * critical "position-authority" discriminator the modern NCE 2.5
 * client requires to clear its "Synchronizing into city zone"
 * overlay — cannot regress.
 *
 * <p>Catalog evidence (3 retail samples, all 11 bytes):
 *
 * <pre>
 *   c9 00 00 00 20 00 00 00 00 ff 14   entity 0x00c9
 *   c8 00 00 00 20 00 00 00 00 ff 14   entity 0x00c8
 *   9f 00 00 00 20 00 00 00 00 ff 14   entity 0x009f
 * </pre>
 */
public class PlayerPositionUpdateTest {

    private static byte[] datagramBytes(PlayerPositionUpdate pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /**
     * Extract the 11 bytes that follow the inner {@code 0x1b}
     * sub-opcode in the wire datagram. Frame layout (PacketBuilderUDP1303):
     * {@code [0x13][counter LE2][counter+sk LE2][size LE2][0x03][seq LE2][0x1b][body...]}
     * — so the body starts at offset 10.
     */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertTrue("datagram too short to carry inner body, got "
                + datagram.length, datagram.length >= 10 + len);
        // Sanity check: outer opcode + reliable opcode + sub-opcode.
        assertEquals("outer 0x13",  0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03", 0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x1b", 0x1b, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void emitsRetailMinimalElevenByteForm() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();

        // entity_id 201 (0x00c9) — matches catalog sample #1
        PlayerPositionUpdate pkt = new PlayerPositionUpdate(pl, pc, 0x00c9);
        byte[] datagram = datagramBytes(pkt);
        byte[] body = extractInnerBody(datagram, 11);

        byte[] expected = {
                (byte) 0xc9, 0x00,             // entity_id LE16
                0x00, 0x00,                    // reserved
                0x20,                          // marker (position-authority)
                0x00, 0x00, 0x00, 0x00,        // padding
                (byte) 0xff,                   // status flags
                0x14                           // animation/action
        };
        assertArrayEquals("body must match retail catalog sample #1 "
                + "(c90000002000000000ff14)", expected, body);
    }

    @Test
    public void entityIdLowByteIsLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();

        PlayerPositionUpdate pkt = new PlayerPositionUpdate(pl, pc, 0x1234);
        byte[] body = extractInnerBody(datagramBytes(pkt), 11);
        assertEquals("LE16 lo byte", 0x34, body[0] & 0xFF);
        assertEquals("LE16 hi byte", 0x12, body[1] & 0xFF);
    }

    @Test
    public void markerByteIsAlways0x20() {
        // The pre-fix bug: marker byte at offset 4 was 0x03.
        // The client treats anything other than 0x20 as the wrong
        // packet class and ignores it — root cause of the
        // persistent Synchronizing overlay.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();

        for (int entityId : new int[] {0, 1, 100, 256, 0xFFFF}) {
            byte[] body = extractInnerBody(datagramBytes(
                    new PlayerPositionUpdate(pl, pc, entityId)), 11);
            assertEquals("marker byte must be 0x20 for entity " + entityId,
                    0x20, body[4] & 0xFF);
        }
    }

    @Test
    public void allRetailSamplesByteEqual() {
        // Replay all 3 cataloged samples by feeding their entity_id.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();

        int[][] samples = {
                {0x00c9, 0xc9},
                {0x00c8, 0xc8},
                {0x009f, 0x9f},
        };
        for (int[] s : samples) {
            byte[] body = extractInnerBody(datagramBytes(
                    new PlayerPositionUpdate(pl, pc, s[0])), 11);
            byte[] expected = {
                    (byte) s[1], 0x00,
                    0x00, 0x00,
                    0x20,
                    0x00, 0x00, 0x00, 0x00,
                    (byte) 0xff,
                    0x14
            };
            assertArrayEquals("entity 0x" + Integer.toHexString(s[0]),
                    expected, body);
        }
    }

    @Test
    public void totalDatagramSizeIsTwentyTwoBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 1 (0x1b) + 11 (body) = 22 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();

        PlayerPositionUpdate pkt = new PlayerPositionUpdate(pl, pc, 0x00c9);
        byte[] datagram = datagramBytes(pkt);
        assertEquals("expected 22-byte total datagram, got "
                + datagram.length, 22, datagram.length);
    }
}
