package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;

/**
 * Byte-identity test for {@link ShortPlayerInfo}
 * (UDP S→C reliable {@code 0x03/0x30}).
 *
 * <p>Wire format (variable-size body, all 18 cataloged retail
 * samples verified):
 *
 * <pre>
 *   offset 0..1  : mapId  LE16
 *   offset 2..3  : 0x00 0x00         padding
 *   offset 4..7  : char_uid  LE32
 *   offset 8..   : name (ASCII)
 *   offset N     : 0x00              null terminator
 * </pre>
 *
 * <p>Catalog evidence:
 * <pre>
 *   #1  21 B  01 00 00 00  09 c5 01 00  "Adriano Raon\0"
 *   #2  17 B  03 00 00 00  00 cc 01 00  "Sokolito\0"
 *   #3  22 B  02 00 00 00  6b cb 01 00  "Krasniy Sokol\0"
 * </pre>
 */
public class ShortPlayerInfoByteIdentityTest {

    private static byte[] datagramBytes(ShortPlayerInfo pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Body starts at offset 11 (PacketBuilderUDP1303). */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x30", 0x30, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void retailSampleAdrianoRaonByteEqual() {
        // Catalog sample #1: 21B body
        //   01 00 00 00  09 c5 01 00  "Adriano Raon\0"
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setName("Adriano Raon");
        pc.setMisc(PlayerCharacter.MISC_ID, 0x0001c509);

        byte[] body = extractInnerBody(
                datagramBytes(new ShortPlayerInfo(pl, pc, 1)), 21);
        byte[] expected = new byte[21];
        // mapId LE16 = 1
        expected[0] = 0x01;
        expected[1] = 0x00;
        // padding
        expected[2] = 0x00;
        expected[3] = 0x00;
        // uid LE32 = 0x0001c509
        expected[4] = 0x09;
        expected[5] = (byte) 0xc5;
        expected[6] = 0x01;
        expected[7] = 0x00;
        // name + null
        byte[] name = "Adriano Raon".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, expected, 8, name.length);
        expected[20] = 0x00;
        assertArrayEquals(expected, body);
    }

    @Test
    public void retailSampleSokolitoByteEqual() {
        // Catalog sample #2: 17B body
        //   03 00 00 00  00 cc 01 00  "Sokolito\0"
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setName("Sokolito");
        pc.setMisc(PlayerCharacter.MISC_ID, 0x0001cc00);

        byte[] body = extractInnerBody(
                datagramBytes(new ShortPlayerInfo(pl, pc, 3)), 17);
        // Spot-check: mapId, uid, name terminator
        assertEquals(3,    body[0] & 0xFF);
        assertEquals(0,    body[1] & 0xFF);
        assertEquals(0x00, body[4] & 0xFF);   // uid lo
        assertEquals(0xCC, body[5] & 0xFF);
        assertEquals(0x01, body[6] & 0xFF);
        assertEquals(0x00, body[7] & 0xFF);   // uid hi
        // Name "Sokolito" + null = 9 bytes
        byte[] expectedName = "Sokolito\0".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < 9; i++) {
            assertEquals("name byte " + i,
                    expectedName[i], body[8 + i]);
        }
    }

    @Test
    public void mapIdEncodesLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setName("X");
        pc.setMisc(PlayerCharacter.MISC_ID, 0);

        byte[] body = extractInnerBody(
                datagramBytes(new ShortPlayerInfo(pl, pc, 0xABCD)),
                10);  // 8 fixed + "X" + null = 10
        assertEquals(0xCD, body[0] & 0xFF);
        assertEquals(0xAB, body[1] & 0xFF);
    }

    @Test
    public void uidEncodesLittleEndianAcrossAllFourBytes() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setName("X");
        pc.setMisc(PlayerCharacter.MISC_ID, 0x12345678);

        byte[] body = extractInnerBody(
                datagramBytes(new ShortPlayerInfo(pl, pc, 1)), 10);
        // uid LE32 at body[4..7]
        assertEquals(0x78, body[4] & 0xFF);
        assertEquals(0x56, body[5] & 0xFF);
        assertEquals(0x34, body[6] & 0xFF);
        assertEquals(0x12, body[7] & 0xFF);
    }

    @Test
    public void nameAlwaysNullTerminated() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setName("Tester");
        pc.setMisc(PlayerCharacter.MISC_ID, 0);

        byte[] datagram = datagramBytes(new ShortPlayerInfo(pl, pc, 1));
        // Last byte of the entire datagram must be 0x00.
        assertEquals("expected trailing null terminator",
                0x00, datagram[datagram.length - 1] & 0xFF);
    }

    @Test
    public void emptyNameProducesEightByteFixedBlockPlusNull() {
        // Edge case: empty name → body is 8B fixed + 1B null = 9B
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setName("");
        pc.setMisc(PlayerCharacter.MISC_ID, 0);

        byte[] body = extractInnerBody(
                datagramBytes(new ShortPlayerInfo(pl, pc, 1)), 9);
        assertEquals(0x00, body[8] & 0xFF);
    }
}
