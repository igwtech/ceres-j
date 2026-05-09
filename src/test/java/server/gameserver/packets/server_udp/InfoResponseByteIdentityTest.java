package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;

/**
 * Byte-identical regression tests for {@link InfoResponse}
 * (UDP S→C reliable {@code 0x03/0x23}). Four factory methods,
 * each producing a fixed retail-verified payload. Pinning so a
 * future cleanup of the InfoResponse private constructor
 * (or a refactor that moves payloads into a registry) cannot
 * silently change what the client sees.
 *
 * <p>Catalog evidence (verified, 367 retail samples across 17/17
 * captures). Sample bodies include {@code 200010000000} (zoneInfo)
 * and {@code 0e000000000000000100} (sessionInfo) — both sourced
 * from retail TCP capture ACC1_CHAR1 #11/#12.
 */
public class InfoResponseByteIdentityTest {

    private static byte[] datagramBytes(InfoResponse pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP1303):
     *  {@code [0x13][counter LE2][counter+sk LE2][size LE2][0x03][seq LE2][0x23][body...]}
     *  Body starts at offset 11. */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertTrue("datagram too short", datagram.length >= 11 + len);
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x23", 0x23, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    private static Player fixture() {
        return PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
    }

    @Test
    public void zoneInfoExactBytes() {
        // Catalog sample #1: `200010000000` (6 bytes)
        byte[] expected = {
                0x20, 0x00, 0x10, 0x00, 0x00, 0x00
        };
        byte[] body = extractInnerBody(
                datagramBytes(InfoResponse.zoneInfo(fixture())),
                expected.length);
        assertArrayEquals(expected, body);
    }

    @Test
    public void sessionInfoExactBytes() {
        // Catalog sample #2: `0e000000000000000100` (10 bytes)
        byte[] expected = {
                0x0e, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x01, 0x00
        };
        byte[] body = extractInnerBody(
                datagramBytes(InfoResponse.sessionInfo(fixture())),
                expected.length);
        assertArrayEquals(expected, body);
    }

    @Test
    public void zoneTransitionMetaCarriesCharIdAtTail() {
        // 19-byte payload with player UID LE32 in the trailing 4 bytes
        // (verified against PLAZA_TO_PEPPER_CROSS_DISTRICT capture
        // 2026-05-02 t=144.63s).
        Player pl = fixture();
        pl.getCharacter().setMisc(PlayerCharacter.MISC_ID, 0x12345678);

        byte[] body = extractInnerBody(
                datagramBytes(InfoResponse.zoneTransitionMeta(pl)),
                19);
        // First 15 bytes are the constant prefix.
        byte[] expectedPrefix = {
                0x04, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x03, 0x00, 0x00, 0x00
        };
        for (int i = 0; i < expectedPrefix.length; i++) {
            assertEquals("prefix[" + i + "]",
                    expectedPrefix[i], body[i]);
        }
        // Trailing 4 bytes: player UID LE32 = 0x78 0x56 0x34 0x12
        assertEquals(0x78, body[15] & 0xFF);
        assertEquals(0x56, body[16] & 0xFF);
        assertEquals(0x34, body[17] & 0xFF);
        assertEquals(0x12, body[18] & 0xFF);
    }

    @Test
    public void postTransitionInfoExactBytes() {
        // 6-byte fixed body `0f 00 03 00 01 00` — sent right
        // after TCP 0x83 0x0c Location during a zone transition.
        byte[] expected = {
                0x0f, 0x00, 0x03, 0x00, 0x01, 0x00
        };
        byte[] body = extractInnerBody(
                datagramBytes(InfoResponse.postTransitionInfo(fixture())),
                expected.length);
        assertArrayEquals(expected, body);
    }

    @Test
    public void totalDatagramSizesMatchExpected() {
        // 11-byte frame envelope + body length:
        Player pl = fixture();
        // zoneInfo:           6B body → 17B total
        assertEquals(17, datagramBytes(InfoResponse.zoneInfo(pl)).length);
        // sessionInfo:        10B body → 21B total
        assertEquals(21, datagramBytes(InfoResponse.sessionInfo(pl)).length);
        // postTransitionInfo: 6B body → 17B total
        assertEquals(17, datagramBytes(InfoResponse.postTransitionInfo(pl)).length);
        // zoneTransitionMeta: 19B body → 30B total
        assertEquals(30, datagramBytes(InfoResponse.zoneTransitionMeta(pl)).length);
    }
}
