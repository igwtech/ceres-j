package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link ForcedZoning} (regression net).
 *
 * <p>Wire path: {@code 0x13 → 0x03 → 0x1f → [mapId LE2] →
 * 0x38 0x04 0x00 → [loc LE32] [spawnID 1B] [0x00 trailer]}.
 *
 * <p>The source has comments indicating several byte values are
 * guesses ("map id?", "unknown vielleicht anzahl spieler"). This
 * test pins Ceres-J's current emission so refactors don't change
 * the wire bytes silently. A future retail-decode pass can
 * update the assertions when ground-truth bytes are known.
 */
public class ForcedZoningByteIdentityTest {

    private static byte[] datagramBytes(ForcedZoning pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Body starts at offset 11 (PacketBuilderUDP1303). */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x1f", 0x1f, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void defaultConstructorBodyLayout() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0xCAFE);

        byte[] body = extractInnerBody(
                datagramBytes(new ForcedZoning(pl, 0x12345678)),
                11);
        // [0..1] mapId LE16 = 0xCAFE
        assertEquals(0xFE, body[0] & 0xFF);
        assertEquals(0xCA, body[1] & 0xFF);
        // [2] 0x38
        assertEquals(0x38, body[2] & 0xFF);
        // [3] 0x04
        assertEquals(0x04, body[3] & 0xFF);
        // [4] 0x00
        assertEquals(0x00, body[4] & 0xFF);
        // [5..8] loc LE32 = 0x12345678
        assertEquals(0x78, body[5] & 0xFF);
        assertEquals(0x56, body[6] & 0xFF);
        assertEquals(0x34, body[7] & 0xFF);
        assertEquals(0x12, body[8] & 0xFF);
        // [9] spawnID = 0x01 (default constructor)
        assertEquals(0x01, body[9] & 0xFF);
        // [10] trailer 0x00
        assertEquals(0x00, body[10] & 0xFF);
    }

    @Test
    public void spawnIdConstructorOverridesByte9() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(1);

        byte[] body = extractInnerBody(
                datagramBytes(new ForcedZoning(pl, 100, 0x42)),
                11);
        // spawnID byte at offset 9
        assertEquals(0x42, body[9] & 0xFF);
        // Trailer still 0x00
        assertEquals(0x00, body[10] & 0xFF);
    }

    @Test
    public void locationEncodesLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0);

        byte[] body = extractInnerBody(
                datagramBytes(new ForcedZoning(pl, 0xAABBCCDD)),
                11);
        // loc LE32 at body[5..8]
        assertEquals(0xDD, body[5] & 0xFF);
        assertEquals(0xCC, body[6] & 0xFF);
        assertEquals(0xBB, body[7] & 0xFF);
        assertEquals(0xAA, body[8] & 0xFF);
    }

    @Test
    public void totalDatagramSizeIsTwentyTwoBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 1 (0x1f) + 11 (body) = 22 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(22, datagramBytes(
                new ForcedZoning(pl, 0)).length);
    }

    @Test
    public void mapIdEncodesLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(0xABCD);
        byte[] body = extractInnerBody(
                datagramBytes(new ForcedZoning(pl, 1)), 11);
        assertEquals(0xCD, body[0] & 0xFF);
        assertEquals(0xAB, body[1] & 0xFF);
    }

    @Test
    public void bothConstructorsShareTheConstantPrefix() {
        // The 5-byte prefix [mapId LE2][0x38][0x04][0x00] is
        // identical between the two-arg and three-arg
        // constructors. Pin so future refactors can't drift them.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(7);

        byte[] twoArg = extractInnerBody(
                datagramBytes(new ForcedZoning(pl, 50)), 11);
        byte[] threeArg = extractInnerBody(
                datagramBytes(new ForcedZoning(pl, 50, 9)), 11);
        // First 9 bytes (through loc LE32) must be identical.
        for (int i = 0; i < 9; i++) {
            assertEquals("byte " + i + " of shared prefix",
                    twoArg[i], threeArg[i]);
        }
        // spawnID byte at [9] differs (1 vs 9).
        assertNotEquals(twoArg[9], threeArg[9]);
    }
}
