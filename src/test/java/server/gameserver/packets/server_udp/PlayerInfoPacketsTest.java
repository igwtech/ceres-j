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
 * These tests lock down the outer reliable wrapper structure and a handful of
 * payload invariants. They're specifically needed because retail NCE clients
 * refuse to leave the login loading screen until the server sends a
 * LongPlayerInfo for the local player's own mapId.
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
        assertEquals(0x03, b[6] & 0xFF);
        // Sub-type 0x30 (REL_SHORT_PLAYER) at offset 9
        assertEquals(0x30, b[9] & 0xFF);

        // mapId LE at [10..11] = 5
        assertEquals(5, b[10] & 0xFF);
        assertEquals(0, b[11] & 0xFF);

        // Two zero bytes of padding at [12..13]
        assertEquals(0, b[12] & 0xFF);
        assertEquals(0, b[13] & 0xFF);

        // Player ID LE at [14..17] = 0x01020304
        assertEquals(0x04, b[14] & 0xFF);
        assertEquals(0x03, b[15] & 0xFF);
        assertEquals(0x02, b[16] & 0xFF);
        assertEquals(0x01, b[17] & 0xFF);

        // Name "Runner" begins at [18] and is null-terminated
        byte[] name = "Runner".getBytes();
        for (int i = 0; i < name.length; i++) {
            assertEquals("name byte " + i, name[i] & 0xFF, b[18 + i] & 0xFF);
        }
        assertEquals("null terminator", 0, b[18 + name.length] & 0xFF);
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

        // Outer 0x13 + 0x03 reliable wrapper
        assertEquals(0x13, b[0] & 0xFF);
        assertEquals(0x03, b[6] & 0xFF);
        // Sub-type 0x25 (REL_PLAYER_INFO)
        assertEquals(0x25, b[9] & 0xFF);

        // mapId LE = 1
        assertEquals(1, b[10] & 0xFF);
        assertEquals(0, b[11] & 0xFF);

        // Player ID LE at [12..15]
        assertEquals(0xef, b[12] & 0xFF);
        assertEquals(0xbe, b[13] & 0xFF);
        assertEquals(0xad, b[14] & 0xFF);
        assertEquals(0xde, b[15] & 0xFF);

        // Faction byte is embedded further along; confirm 3 appears in the
        // expected slot (offset 0x16 + fixed header constants — the packet
        // writes nine bytes before the faction slot).
        // Locate faction by looking for the fraction-slot constants from
        // LongPlayerInfo: 0x00 0x08 0x09 0x6c 0x02 0x40 0x40 0xc4 0x3c 0x00
        // immediately precede 0x00 then faction.
        int markerIdx = -1;
        for (int i = 16; i < b.length - 10; i++) {
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
        assertEquals(0x03, b[6] & 0xFF);
        assertEquals("REL sub-type", 0x1b, b[9] & 0xFF);

        // mapId at [10..11] = 2
        assertEquals(2, b[10] & 0xFF);
        assertEquals(0, b[11] & 0xFF);

        // Two zero padding bytes + subPacketType 0x03 at [14]
        assertEquals(0, b[12] & 0xFF);
        assertEquals(0, b[13] & 0xFF);
        assertEquals(0x03, b[14] & 0xFF);

        // Position is written as signed short (value + 32000), little-endian:
        //   Y = 0x200 + 32000 = 0x7f00, Z = 0x300 + 32000 = 0x8000 (wraps into signed)
        //   X = 0x100 + 32000 = 0x7e00
        int y = (b[15] & 0xFF) | ((b[16] & 0xFF) << 8);
        int z = (b[17] & 0xFF) | ((b[18] & 0xFF) << 8);
        int x = (b[19] & 0xFF) | ((b[20] & 0xFF) << 8);
        assertEquals(0x200 + 32000, y);
        assertEquals(0x300 + 32000, z);
        assertEquals(0x100 + 32000, x);
    }
}
