package server.gameserver.packets.server_udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.DatagramPacket;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;

/**
 * Byte-layout tests for the three player-info packets used during world entry:
 * <ul>
 *   <li>{@link ShortPlayerInfo} — brief info (sub-type 0x30)</li>
 *   <li>{@link LongPlayerInfo} — full info including models (sub-type 0x25)</li>
 *   <li>{@link PlayerPositionUpdate} — movement broadcast (sub-type 0x1b)</li>
 * </ul>
 *
 * <p>Offsets reflect the 2-byte LE sub-packet length added at offset 5–6
 * (retail format). All payload offsets are therefore shifted +1 relative
 * to the old 1-byte-length layout.
 */
public class PlayerInfoPacketsTest {

    @Test
    public void shortPlayerInfoContainsIdAndName() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setMisc(PlayerCharacter.MISC_ID, 0x01020304);

        DatagramPacket[] dps = new ShortPlayerInfo(pl, pc, 5).getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        // Outer header
        assertEquals(0x13, b[0] & 0xFF);
        assertEquals(0x03, b[7] & 0xFF);
        // Sub-type 0x30 (REL_SHORT_PLAYER) at offset 10
        assertEquals(0x30, b[10] & 0xFF);

        // mapId LE at [11..12] = 5
        assertEquals(5, b[11] & 0xFF);
        assertEquals(0, b[12] & 0xFF);

        // Two zero bytes of padding at [13..14]
        assertEquals(0, b[13] & 0xFF);
        assertEquals(0, b[14] & 0xFF);

        // Player ID LE at [15..18] = 0x01020304
        assertEquals(0x04, b[15] & 0xFF);
        assertEquals(0x03, b[16] & 0xFF);
        assertEquals(0x02, b[17] & 0xFF);
        assertEquals(0x01, b[18] & 0xFF);

        // Name "Runner" begins at [19] and is null-terminated
        byte[] name = "Runner".getBytes();
        for (int i = 0; i < name.length; i++) {
            assertEquals("name byte " + i, name[i] & 0xFF, b[19 + i] & 0xFF);
        }
        assertEquals("null terminator", 0, b[19 + name.length] & 0xFF);
    }

    @Test
    public void longPlayerInfoContainsMapIdAndPlayerId() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setMisc(PlayerCharacter.MISC_ID, 0xDEADBEEF);
        pc.setMisc(PlayerCharacter.MISC_FACTION, 3);

        DatagramPacket[] dps = new LongPlayerInfo(pl, pc, 1).getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        // Outer 0x13 + 0x03 reliable wrapper (offset +1 due to 2-byte length)
        assertEquals(0x13, b[0] & 0xFF);
        assertEquals(0x03, b[7] & 0xFF);
        // Sub-type 0x25 (REL_PLAYER_INFO)
        assertEquals(0x25, b[10] & 0xFF);

        // mapId LE = 1
        assertEquals(1, b[11] & 0xFF);
        assertEquals(0, b[12] & 0xFF);

        // Player ID LE at [13..16]
        assertEquals(0xef, b[13] & 0xFF);
        assertEquals(0xbe, b[14] & 0xFF);
        assertEquals(0xad, b[15] & 0xFF);
        assertEquals(0xde, b[16] & 0xFF);

        // Locate faction by looking for the fixed marker from LongPlayerInfo:
        // 0x00 0x08 0x09 0x6c 0x02 0x40 0x40 0xc4 0x3c 0x00 immediately
        // precede the faction byte.
        int markerIdx = -1;
        for (int i = 17; i < b.length - 10; i++) {
            if ((b[i] & 0xFF) == 0x00 && (b[i+1] & 0xFF) == 0x08
             && (b[i+2] & 0xFF) == 0x09 && (b[i+3] & 0xFF) == 0x6c
             && (b[i+4] & 0xFF) == 0x02 && (b[i+5] & 0xFF) == 0x40
             && (b[i+6] & 0xFF) == 0x40 && (b[i+7] & 0xFF) == 0xc4
             && (b[i+8] & 0xFF) == 0x3c && (b[i+9] & 0xFF) == 0x00) {
                markerIdx = i;
                break;
            }
        }
        assertTrue("LongPlayerInfo fixed header marker not found", markerIdx >= 0);
        // Faction value follows the 10-byte marker
        assertEquals("faction byte", 3, b[markerIdx + 10] & 0xFF);
    }

    @Test
    public void playerPositionUpdateEmbedsPosition() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, 0x100);
        pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, 0x200);
        pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, 0x300);
        pc.setMisc(PlayerCharacter.MISC_ORIENTATION, 0x40);
        pc.setMisc(PlayerCharacter.MISC_TILT, 0x10);

        DatagramPacket[] dps = new PlayerPositionUpdate(pl, pc, 2).getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        assertEquals(0x13, b[0] & 0xFF);
        assertEquals(0x03, b[7] & 0xFF);
        assertEquals("REL sub-type", 0x1b, b[10] & 0xFF);

        // mapId at [11..12] = 2
        assertEquals(2, b[11] & 0xFF);
        assertEquals(0, b[12] & 0xFF);

        // Two zero padding bytes + subPacketType 0x03 at [15]
        assertEquals(0, b[13] & 0xFF);
        assertEquals(0, b[14] & 0xFF);
        assertEquals(0x03, b[15] & 0xFF);

        // Position is written as signed short (value + 32000), little-endian:
        //   Y = 0x200 + 32000 = 0x7f00, Z = 0x300 + 32000 = 0x8000
        //   X = 0x100 + 32000 = 0x7e00
        int y = (b[16] & 0xFF) | ((b[17] & 0xFF) << 8);
        int z = (b[18] & 0xFF) | ((b[19] & 0xFF) << 8);
        int x = (b[20] & 0xFF) | ((b[21] & 0xFF) << 8);
        assertEquals(0x200 + 32000, y);
        assertEquals(0x300 + 32000, z);
        assertEquals(0x100 + 32000, x);
    }
}
