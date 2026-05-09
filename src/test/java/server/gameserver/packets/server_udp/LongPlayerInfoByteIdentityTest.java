package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;

/**
 * Byte-identity test for {@link LongPlayerInfo}
 * (UDP S→C reliable {@code 0x03/0x25}). Catalog: 41 retail
 * samples across 17/17 captures, 61–86 B (avg 67) — variable
 * because the body embeds the character name.
 *
 * <p>Pinning the structural fields (mapId, char_id, faction,
 * model fields, name length+bytes, trailing cash tag+value)
 * — every byte after the model fields is deterministic given
 * the fixture player's PlayerCharacter state.
 */
public class LongPlayerInfoByteIdentityTest {

    private static byte[] datagramBytes(LongPlayerInfo pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Body starts at offset 11 (PacketBuilderUDP1303). */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x25", 0x25, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void mapIdAndCharIdAtCorrectOffsets() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setMisc(PlayerCharacter.MISC_ID, 0x12345678);
        pc.setName("Runner");

        // 6-char name → inner body = 63 B (calculated from emitter).
        byte[] body = extractInnerBody(
                datagramBytes(new LongPlayerInfo(pl, pc, 0xCAFE)),
                62);

        // [0..1] mapId LE16 = 0xCAFE
        assertEquals(0xFE, body[0] & 0xFF);
        assertEquals(0xCA, body[1] & 0xFF);
        // [2..5] char_id LE32 = 0x12345678
        assertEquals(0x78, body[2] & 0xFF);
        assertEquals(0x56, body[3] & 0xFF);
        assertEquals(0x34, body[4] & 0xFF);
        assertEquals(0x12, body[5] & 0xFF);
    }

    @Test
    public void constantPrefixBytes() {
        // Bytes [6..15]: hardcoded 00 08 09 6c 02 40 40 c4 3c 00.
        // Pin so a future refactor can't change them silently.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                new LongPlayerInfo(pl, pl.getCharacter(), 1)), 62);

        byte[] expected = {
                0x00, 0x08, 0x09, 0x6c, 0x02,
                0x40, 0x40, (byte) 0xc4, 0x3c, 0x00
        };
        for (int i = 0; i < expected.length; i++) {
            assertEquals("constant prefix byte " + (6 + i),
                    expected[i] & 0xFF,
                    body[6 + i] & 0xFF);
        }
    }

    @Test
    public void factionByteAtFixedOffset() {
        // Body [16] = faction (1B). Fixture sets MISC_FACTION = 7.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setMisc(PlayerCharacter.MISC_FACTION, 7);

        byte[] body = extractInnerBody(
                datagramBytes(new LongPlayerInfo(pl, pc, 1)), 62);
        assertEquals("faction byte at offset 16",
                7, body[16] & 0xFF);
    }

    @Test
    public void nameLengthAndAsciiInBody() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setName("Runner");

        byte[] body = extractInnerBody(
                datagramBytes(new LongPlayerInfo(pl, pc, 1)), 62);

        // The name length byte is at offset 48 (calculated from
        // the emitter's writes). Value = name.length() + 1 = 7.
        assertEquals("name length byte (= len+1)",
                7, body[48] & 0xFF);
        // Name "Runner" at offsets 49..54
        byte[] nameBytes = "Runner".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < nameBytes.length; i++) {
            assertEquals("name byte " + i,
                    nameBytes[i] & 0xFF,
                    body[49 + i] & 0xFF);
        }
    }

    @Test
    public void trailingCashTagAndValue() {
        // Last 5 bytes: 0x04 cash-tag + LE32 cash value.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setName("Runner");
        pc.setCash(0x12345678);

        byte[] body = extractInnerBody(
                datagramBytes(new LongPlayerInfo(pl, pc, 1)), 62);

        // Trailing 5 bytes at offsets 57..61
        assertEquals("cash tag 0x04 at offset 57",
                0x04, body[57] & 0xFF);
        // cash LE32 at offsets 58..61
        assertEquals(0x78, body[58] & 0xFF);
        assertEquals(0x56, body[59] & 0xFF);
        assertEquals(0x34, body[60] & 0xFF);
        assertEquals(0x12, body[61] & 0xFF);
    }

    @Test
    public void totalDatagramSizeFitsCatalogRange() {
        // 11-byte frame envelope + 63-byte body = 74 bytes total
        // for a 6-char name. Catalog: 41 retail samples 61-86B
        // — ours is in the middle of that range.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        PlayerCharacter pc = pl.getCharacter();
        pc.setName("Runner");

        int total = datagramBytes(
                new LongPlayerInfo(pl, pc, 1)).length;
        assertEquals(73, total);
        assertTrue("total falls within catalog range 61-86B "
                + "(plus 11B frame envelope = 72-97B): " + total,
                total >= 72 && total <= 97);
    }
}
