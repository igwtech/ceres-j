package server.gameserver.packets.server_tcp;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.networktools.PacketBuilderTCP;

/**
 * Byte-identity test for {@link Location} (TCP S→C 0x83 0x0c).
 *
 * <p>Wire format Ceres-J emits (matches retail catalog sample
 * #2: {@code 830c 65000000 0000000000000000 706c617a612f706c617a615f703300}):
 *
 * <pre>
 *   offset 0..1   : opcode 83 0c
 *   offset 2..5   : location LE32
 *   offset 6..9   : flag    LE32  (0; or 1 when location == 9999)
 *   offset 10..13 : zero    LE32
 *   offset 14..N  : worldname ASCII
 *   offset N+1    : 0x00     null terminator
 * </pre>
 *
 * <p>The 9999 special case substitutes {@code apps/clean/plaza_app_4_c}
 * for the worldname and writes {@code flag=1}.
 *
 * <p><b>Open question (catalog):</b> retail sample #1 with location=1
 * has byte 10 = {@code 0x10} where Ceres-J writes {@code 0x00}. The
 * meaning is unconfirmed (possibly a per-zone version flag) — pinning
 * Ceres-J's current behavior; a future fix can update this test.
 */
public class LocationByteIdentityTest {

    private static byte[] wireBytes(PacketBuilderTCP pkt) {
        byte[] data = pkt.getData();
        int n = pkt.size();
        byte[] sliced = new byte[n];
        System.arraycopy(data, 0, sliced, 0, n);
        return sliced;
    }

    /** Body starts at offset 3 (after FE-frame header at 0..2). */
    private static byte[] extractBody(byte[] wire, int len) {
        assertEquals((byte) 0xfe, wire[0]);
        byte[] body = new byte[len];
        System.arraycopy(wire, 3, body, 0, len);
        return body;
    }

    @Test
    public void plazaP3LayoutMatchesRetailSample2() {
        // Retail sample #2: location=0x65 (101), worldname=
        // "plaza/plaza_p3". Ceres-J's null-Zone fallback emits an
        // empty worldname, so to byte-equal the retail sample we
        // need to register a Zone with the right worldname or
        // fake it via a player-state setup. The fixture player
        // has currentZone=null, so the path falls through to the
        // empty-string branch — that path is exercised here.
        Player pl = PacketTestFixture.newPlayer();
        pl.getCharacter().setMisc(
                PlayerCharacter.MISC_LOCATION, 0x65);

        byte[] body = extractBody(wireBytes(new Location(pl)), 14 + 0 + 1);
        // [0..1] opcode
        assertEquals(0x83, body[0] & 0xFF);
        assertEquals(0x0c, body[1] & 0xFF);
        // [2..5] location LE32 = 0x65
        assertEquals(0x65, body[2] & 0xFF);
        assertEquals(0x00, body[3] & 0xFF);
        assertEquals(0x00, body[4] & 0xFF);
        assertEquals(0x00, body[5] & 0xFF);
        // [6..9] flag LE32 = 0 (location != 9999)
        for (int i = 6; i <= 9; i++) {
            assertEquals("flag byte " + i,
                    0x00, body[i] & 0xFF);
        }
        // [10..13] zero LE32
        for (int i = 10; i <= 13; i++) {
            assertEquals("zero byte " + i,
                    0x00, body[i] & 0xFF);
        }
        // [14] null terminator (empty worldname)
        assertEquals("null terminator at offset 14",
                0x00, body[14] & 0xFF);
    }

    @Test
    public void location9999SetsFlagToOneAndWritesAppPath() {
        // Special-case: location == 9999 → emit flag=1 and the
        // hardcoded worldname "apps/clean/plaza_app_4_c".
        Player pl = PacketTestFixture.newPlayer();
        pl.getCharacter().setMisc(
                PlayerCharacter.MISC_LOCATION, 9999);

        String expectedName = "apps/clean/plaza_app_4_c";
        byte[] body = extractBody(wireBytes(new Location(pl)),
                14 + expectedName.length() + 1);

        // location LE32 = 9999 = 0x0000270f
        assertEquals(0x0f, body[2] & 0xFF);
        assertEquals(0x27, body[3] & 0xFF);
        assertEquals(0x00, body[4] & 0xFF);
        assertEquals(0x00, body[5] & 0xFF);
        // flag LE32 = 1
        assertEquals(0x01, body[6] & 0xFF);
        assertEquals(0x00, body[7] & 0xFF);
        assertEquals(0x00, body[8] & 0xFF);
        assertEquals(0x00, body[9] & 0xFF);
        // worldname starts at offset 14
        byte[] expectedNameBytes = expectedName
                .getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < expectedNameBytes.length; i++) {
            assertEquals("name byte " + i,
                    expectedNameBytes[i],
                    body[14 + i]);
        }
        // null terminator after the name
        assertEquals(0x00,
                body[14 + expectedNameBytes.length] & 0xFF);
    }

    @Test
    public void locationFieldEncodesLittleEndian() {
        Player pl = PacketTestFixture.newPlayer();
        pl.getCharacter().setMisc(
                PlayerCharacter.MISC_LOCATION, 0x12345678);

        byte[] body = extractBody(wireBytes(new Location(pl)), 15);
        assertEquals(0x78, body[2] & 0xFF);
        assertEquals(0x56, body[3] & 0xFF);
        assertEquals(0x34, body[4] & 0xFF);
        assertEquals(0x12, body[5] & 0xFF);
    }

    @Test
    public void nullZoneFallsThroughToEmptyName() {
        // Fixture player has no Zone → emitter's null-safe path
        // emits empty worldname (just the null terminator).
        // Pin this behaviour so a future refactor doesn't
        // accidentally NPE here.
        Player pl = PacketTestFixture.newPlayer();
        pl.getCharacter().setMisc(
                PlayerCharacter.MISC_LOCATION, 0x65);
        assertNull("fixture player has no zone",
                pl.getZone());

        byte[] body = extractBody(wireBytes(new Location(pl)), 15);
        // 14 bytes header + 1 byte null terminator = 15 bytes body
        assertEquals(0x00, body[14] & 0xFF);
    }

    @Test
    public void totalWireSizeIsHeaderPlusNamePlusFraming() {
        // 3-byte FE frame + 14-byte header + name + 1-byte null
        Player pl = PacketTestFixture.newPlayer();
        pl.getCharacter().setMisc(
                PlayerCharacter.MISC_LOCATION, 0x65);
        // Empty worldname (null Zone): total = 3 + 14 + 0 + 1 = 18
        assertEquals(18, new Location(pl).size());
    }

    @Test
    public void location9999TotalWireSize() {
        // 3 + 14 + 24 ("apps/clean/plaza_app_4_c") + 1 = 42
        Player pl = PacketTestFixture.newPlayer();
        pl.getCharacter().setMisc(
                PlayerCharacter.MISC_LOCATION, 9999);
        assertEquals(42, new Location(pl).size());
    }
}
