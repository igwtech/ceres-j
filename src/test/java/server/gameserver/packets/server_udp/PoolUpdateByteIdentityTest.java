package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identical regression test for {@link PoolUpdate}.
 *
 * <p>This packet drives the client's HP/PSI/STA bar updates. It
 * is a <b>raw {@code 0x1f}</b> sub-packet (NOT reliable-wrapped
 * like most other 0x1f variants) and contains a signed delta
 * applied client-side — passing an absolute pool level instead
 * makes the bar move the wrong direction.
 *
 * <p>Wire format (16-byte body, verified against retail death
 * capture):
 *
 * <pre>
 *   1f 01 00 50 [delta LE4 signed] [00 00 00] [pool] [max LE2] [00 00]
 * </pre>
 *
 * <p>Retail death sample: fatal blow at HP=121, max=396, delta=-278:
 * {@code 1f 01 00 50  ea fe ff ff  00 00 00 04  8c 01 00 00}.
 */
public class PoolUpdateByteIdentityTest {

    private static byte[] datagramBytes(PoolUpdate pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Frame layout (PacketBuilderUDP13 only — no 0x03 wrapper):
     *  {@code [0x13][counter LE2][counter+sk LE2][size LE2][body 16B]}
     *  Body starts at offset 7. */
    private static byte[] extractInnerBody(byte[] datagram) {
        assertEquals("outer 0x13", 0x13, datagram[0] & 0xFF);
        byte[] body = new byte[16];
        System.arraycopy(datagram, 7, body, 0, 16);
        return body;
    }

    @Test
    public void retailDeathCaptureExactBytes() {
        // Verified retail death capture: HP delta=-278, pool=HP,
        // max=396. Bytes: 1f 01 00 50 ea fe ff ff 00 00 00 04 8c 01 00 00
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        PoolUpdate pkt = new PoolUpdate(pl, PoolUpdate.POOL_HP, -278, 396);
        byte[] body = extractInnerBody(datagramBytes(pkt));

        byte[] expected = {
                0x1f, 0x01, 0x00, 0x50,
                (byte) 0xea, (byte) 0xfe, (byte) 0xff, (byte) 0xff,
                0x00, 0x00, 0x00, 0x04,
                (byte) 0x8c, 0x01, 0x00, 0x00
        };
        assertArrayEquals("body must match retail death-capture "
                + "bytes (1f 01 00 50 ea fe ff ff 00 00 00 04 8c 01 00 00)",
                expected, body);
    }

    @Test
    public void positiveHealEncodesPositiveDelta() {
        // Healing for +50 HP, max 200 → delta = 0x00000032
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        PoolUpdate pkt = new PoolUpdate(pl, PoolUpdate.POOL_HP, 50, 200);
        byte[] body = extractInnerBody(datagramBytes(pkt));
        // delta LE32
        assertEquals(0x32, body[4] & 0xFF);
        assertEquals(0x00, body[5] & 0xFF);
        assertEquals(0x00, body[6] & 0xFF);
        assertEquals(0x00, body[7] & 0xFF);
        // pool
        assertEquals(PoolUpdate.POOL_HP, body[11] & 0xFF);
        // max LE16
        assertEquals(0xC8, body[12] & 0xFF);
        assertEquals(0x00, body[13] & 0xFF);
    }

    @Test
    public void psiAndStaPoolTypesEncodeCorrectly() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        byte[] psi = extractInnerBody(datagramBytes(
                new PoolUpdate(pl, PoolUpdate.POOL_PSI, -5, 100)));
        assertEquals("PSI pool byte", 0x05, psi[11] & 0xFF);

        byte[] sta = extractInnerBody(datagramBytes(
                new PoolUpdate(pl, PoolUpdate.POOL_STA, 0, 50)));
        assertEquals("STA pool byte", 0x06, sta[11] & 0xFF);
    }

    @Test
    public void deltaIsSignedTwosComplementLE32() {
        // -278 = 0xFFFFFEEA. Already covered in retailDeathCapture
        // but confirm explicitly that negative deltas are
        // serialised as two's-complement (NOT zig-zag, NOT
        // sign-magnitude).
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);

        byte[] body = extractInnerBody(datagramBytes(
                new PoolUpdate(pl, PoolUpdate.POOL_HP, -278, 0)));
        // LE bytes of -278 = 0xFFFFFEEA
        assertEquals(0xEA, body[4] & 0xFF);
        assertEquals(0xFE, body[5] & 0xFF);
        assertEquals(0xFF, body[6] & 0xFF);
        assertEquals(0xFF, body[7] & 0xFF);
    }

    @Test
    public void totalDatagramSizeIsTwentyThreeBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   16 (body) = 23 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(23, datagramBytes(
                new PoolUpdate(pl, PoolUpdate.POOL_HP, 0, 100)).length);
    }
}
