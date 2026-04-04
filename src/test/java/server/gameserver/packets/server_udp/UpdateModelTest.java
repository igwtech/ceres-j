package server.gameserver.packets.server_udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.DatagramPacket;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;

/**
 * Tests the {@link UpdateModel} packet layout.
 *
 * UpdateModel is a {@code 0x13 -> 0x03 reliable} packet (it extends
 * {@code PacketBuilderUDP1303}) carrying the character's visible model and
 * name. It's sent during world entry so the client can render the player's
 * body.
 *
 * Layout (variable length, depends on character name):
 * <pre>
 *   0x00  0x13                UDP gamedata header
 *   0x01  short    counter    outer counter (LE, patched)
 *   0x03  short    ckey       counter + sessionkey (LE, patched)
 *   0x05  byte     size       inner size (= count - 6)
 *   0x06  byte     0x03       reliable wrapper
 *   0x07  short    seqCounter reliable sequence (LE)
 *   0x09  byte     0x2f       REL_UPDATE_MODEL sub-type
 *   0x0A  short    mapId      (LE)
 *   0x0C  0x01 0x00 0x20      fixed sub-block
 *   ...   0x02 0x01 0x07
 *   ...   0x02 0x05 0x8a
 *   ...   0x02 0x08 0x01
 *   ...   0x02 0x0d [hair lo] [hair hi]
 *   ...   0x02 0x0e [beard lo] [beard hi]
 *   ...   0x03 0x00 0x0f [head lo] [head hi]
 *                        [tex_head][tex_torso][tex_leg]
 *                        0x00 0x00 0x00 0x00 0x00 0x00
 *                        [torso lo] [torso hi]
 *                        [leg lo] [leg hi]
 *   ...   0x03 0x01 [nameLen+1] [name...] 0x00
 *   ...   0x03 0x03 0x00
 * </pre>
 */
public class UpdateModelTest {

    @Test
    public void basicLayout() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0x010a);

        DatagramPacket[] dps = new UpdateModel(pl).getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        assertEquals("outer header", 0x13, b[0] & 0xFF);

        // Reliable wrapper at offset 6.
        assertEquals("0x03 reliable wrapper", 0x03, b[6] & 0xFF);

        // REL_UPDATE_MODEL sub-type is at offset 9 (after 0x13 + 4B counters
        // + 1B size + 0x03 + 2B seq counter).
        assertEquals("REL_UPDATE_MODEL sub-type", 0x2f, b[9] & 0xFF);

        // mapId little-endian at offset 10..11 (value 0x010a).
        assertEquals(0x0a, b[10] & 0xFF);
        assertEquals(0x01, b[11] & 0xFF);

        // size byte at 5 must equal count - 6.
        int innerSize = b[5] & 0xFF;
        assertEquals(b.length - 6, innerSize);
    }

    @Test
    public void containsHairAndBeardModels() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setMisc(PlayerCharacter.MODEL_HAIR,  0x0abc);
        pc.setMisc(PlayerCharacter.MODEL_BEARD, 0x0def);

        DatagramPacket[] dps = new UpdateModel(pl).getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        // Scan for 0x02 0x0d (hair model marker), starting past the header
        // to avoid matching bytes in the outer 0x13/counter fields. Sub-type
        // 0x2f is at offset 9, so payload proper starts at offset 10.
        int payloadStart = 12; // skip 0x2f + mapId short
        int hairIdx = indexOf2Byte(b, payloadStart, (byte) 0x02, (byte) 0x0d);
        assertTrue("hair marker 02 0d not found", hairIdx >= 0);
        assertEquals(0xbc, b[hairIdx + 2] & 0xFF);
        assertEquals(0x0a, b[hairIdx + 3] & 0xFF);

        int beardIdx = indexOf2Byte(b, payloadStart, (byte) 0x02, (byte) 0x0e);
        assertTrue("beard marker 02 0e not found", beardIdx >= 0);
        assertEquals(0xef, b[beardIdx + 2] & 0xFF);
        assertEquals(0x0d, b[beardIdx + 3] & 0xFF);
    }

    @Test
    public void containsCharacterName() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        DatagramPacket[] dps = new UpdateModel(pl).getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        // The 0x03 0x01 marker appears in two places (reliable wrapper at
        // offset 6 and the name sub-block near the tail), so start the scan
        // past the wrapper. payloadStart skips the 0x2f REL_UPDATE_MODEL byte
        // and the mapId short too, landing us on the first real sub-block.
        int payloadStart = 12;
        int nameIdx = indexOf2Byte(b, payloadStart, (byte) 0x03, (byte) 0x01);
        assertTrue("name marker 03 01 not found", nameIdx >= 0);
        assertEquals("name length prefix (len+1)",
                "Runner".length() + 1, b[nameIdx + 2] & 0xFF);

        byte[] expectedName = "Runner".getBytes();
        for (int i = 0; i < expectedName.length; i++) {
            assertEquals("name byte " + i, expectedName[i] & 0xFF,
                    b[nameIdx + 3 + i] & 0xFF);
        }
        // Null terminator
        assertEquals(0, b[nameIdx + 3 + expectedName.length] & 0xFF);
    }

    private static int indexOf2Byte(byte[] haystack, int start, byte a, byte b) {
        for (int i = start; i < haystack.length - 1; i++) {
            if (haystack[i] == a && haystack[i + 1] == b) return i;
        }
        return -1;
    }
}
