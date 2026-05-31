package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-identity test for {@link InitWeather02} — the
 * world-entry weather initializer ({@code 0x2e} on the 0x03
 * reliable wrapper). Emits the same inner payload structure as
 * {@link WorldWeather}; both are now sent on the 0x03 reliable
 * wrapper (retail-faithful for login state).
 *
 * <p>13-byte body matches the verified retail samples (see
 * source javadoc).
 */
public class InitWeather02ByteIdentityTest {

    // NOTE: PacketBuilderUDP1302's old static `seq` field was
    // removed in production; the 0x03 init seq now comes from the
    // connection's unified udpSessionCounter. These tests pin only
    // op + body bytes, so no seq reset is needed.

    private static byte[] datagramBytes(InitWeather02 pkt) {
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);
        return b;
    }

    /** Body starts at offset 11 (PacketBuilderUDP1303 frame). */
    private static byte[] extractInnerBody(byte[] datagram, int len) {
        assertEquals("outer 0x13",      0x13, datagram[0] & 0xFF);
        assertEquals("0x03 wrapper",    0x03, datagram[7] & 0xFF);
        assertEquals("sub-opcode 0x2e", 0x2e, datagram[10] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(datagram, 11, body, 0, len);
        return body;
    }

    @Test
    public void retailSampleAcc1ByteEqual() {
        // Retail ACC1 sample: 01 00 00 00 00 a0 05 00 00 a0 05 00 00
        // (13 bytes, inactive weather at noon)
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(
                datagramBytes(new InitWeather02(pl)), 13);
        byte[] expected = {
                0x01, 0x00, 0x00, 0x00, 0x00,
                (byte) 0xa0, 0x05, 0x00, 0x00,
                (byte) 0xa0, 0x05, 0x00, 0x00
        };
        assertArrayEquals("body must match retail ACC1 sample "
                + "(noon, 1440 minutes-of-day)",
                expected, body);
    }

    @Test
    public void totalDatagramSizeIsTwentyFourBytes() {
        // 1 (0x13) + 2 (counter) + 2 (counter+sk) + 2 (size) +
        //   1 (0x03) + 2 (seq) + 1 (0x2e) + 13 (body) = 24 bytes
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        assertEquals(24, datagramBytes(new InitWeather02(pl)).length);
    }

    @Test
    public void weatherTypeAndActiveBytesArePinned() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(
                datagramBytes(new InitWeather02(pl)), 13);
        assertEquals("byte 0 weather_type (constant 0x01)",
                0x01, body[0] & 0xFF);
        assertEquals("byte 1 active flag (default inactive 0x00)",
                0x00, body[1] & 0xFF);
    }

    @Test
    public void timeFieldsAreEqualNoonValue() {
        // Both LE32 timestamps = 1440 (noon). Pinning so a future
        // refactor that splits start/end times unequally fails
        // here.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] body = extractInnerBody(
                datagramBytes(new InitWeather02(pl)), 13);

        // start_time at body[5..8] LE32
        int startLE = (body[5] & 0xFF)
                | ((body[6] & 0xFF) << 8)
                | ((body[7] & 0xFF) << 16)
                | ((body[8] & 0xFF) << 24);
        // end_time at body[9..12] LE32
        int endLE = (body[9]  & 0xFF)
                | ((body[10] & 0xFF) << 8)
                | ((body[11] & 0xFF) << 16)
                | ((body[12] & 0xFF) << 24);
        assertEquals("start_time = 1440 (noon)", 1440, startLE);
        assertEquals("end_time = 1440 (noon)",   1440, endLE);
        assertEquals("start == end (snapshot variant)",
                startLE, endLE);
    }

    @Test
    public void initWeather02BodyMatchesWorldWeatherWireWise() {
        // Both the InitWeather02 and WorldWeather variants emit
        // the same 13-byte inner payload format on the 0x03
        // reliable wrapper. Pin this so refactors can't drift the
        // two emitters apart.
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        byte[] init02Body = extractInnerBody(
                datagramBytes(new InitWeather02(pl)), 13);

        DatagramPacket[] dps = new WorldWeather(pl).getDatagramPackets();
        byte[] worldRaw = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, worldRaw, 0,
                worldRaw.length);
        byte[] worldBody = new byte[13];
        System.arraycopy(worldRaw, 11, worldBody, 0, 13);

        assertArrayEquals("InitWeather02 body must match "
                + "WorldWeather body byte-for-byte (both on the "
                + "0x03 reliable wrapper)",
                init02Body, worldBody);
    }
}
