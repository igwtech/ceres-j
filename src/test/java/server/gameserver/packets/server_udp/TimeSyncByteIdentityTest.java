package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link TimeSync} (UDP S→C reliable
 * {@code 0x03/0x0d}). Pins the trailing 4-byte
 * {@code d5 0a 58 00} world identifier against retail catalog
 * evidence (80 samples across 17/17 captures uniformly carry
 * those bytes); pre-fix Ceres-J wrote {@code fb 0a 00 00}.
 */
public class TimeSyncByteIdentityTest {

    private static byte[] datagramBytes(TimeSync pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP1303):
     *  {@code [0x13][counter LE2][counter+sk LE2][size LE2][0x03][seq LE2][0x0d][body 12B]}
     *  Body starts at offset 11. */
    private static byte[] extractInnerBody(byte[] datagram) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("reliable 0x03",   0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x0d", 0x0d, datagram[10] & 0xFF);
        byte[] body = new byte[12];
        System.arraycopy(datagram, 11, body, 0, 12);
        return body;
    }

    @Test
    public void clientTimeEchoEncodesLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        byte[] body = extractInnerBody(datagramBytes(
                new TimeSync(pl, 0x05_02_C8_FF)));
        // Bytes [4..7] are the client-time echo, LE32.
        assertEquals(0xFF, body[4] & 0xFF);
        assertEquals(0xC8, body[5] & 0xFF);
        assertEquals(0x02, body[6] & 0xFF);
        assertEquals(0x05, body[7] & 0xFF);
    }

    @Test
    public void trailingFourBytesAreRetailWorldId() {
        // The critical fix: trailing 4 bytes must be `d5 0a 58 00`,
        // not the previously-emitted `fb 0a 00 00`. Catalog evidence:
        // 80/80 retail samples carry these exact bytes.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        byte[] body = extractInnerBody(datagramBytes(
                new TimeSync(pl, 0)));
        assertEquals(0xD5, body[8]  & 0xFF);
        assertEquals(0x0A, body[9]  & 0xFF);
        assertEquals(0x58, body[10] & 0xFF);
        assertEquals(0x00, body[11] & 0xFF);
    }

    @Test
    public void trailingBytesNotFB0A0000Anymore() {
        // Regression guard: the pre-fix bytes were `fb 0a 00 00`.
        // Confirm we no longer emit them. A future refactor that
        // resurrects the wrong bytes will fail here.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        byte[] body = extractInnerBody(datagramBytes(
                new TimeSync(pl, 0)));
        boolean isOldBytes =
                (body[8]  & 0xFF) == 0xFB &&
                (body[9]  & 0xFF) == 0x0A &&
                (body[10] & 0xFF) == 0x00 &&
                (body[11] & 0xFF) == 0x00;
        assertFalse("trailing bytes must not regress to the "
                + "pre-fix `fb 0a 00 00`", isOldBytes);
    }

    @Test
    public void worldIdConstantMatchesEmittedTail() {
        // The public WORLD_ID_TAIL constant must equal the bytes
        // actually written. If they ever drift, fix one or the
        // other.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(datagramBytes(
                new TimeSync(pl, 0)));
        for (int i = 0; i < 4; i++) {
            assertEquals("byte " + i + " of WORLD_ID_TAIL",
                    TimeSync.WORLD_ID_TAIL[i],
                    body[8 + i]);
        }
    }

    @Test
    public void totalDatagramSizeIsTwentyThreeBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 1 (0x0d) + 12 (body) = 23 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(23, datagramBytes(new TimeSync(pl, 0)).length);
    }
}
