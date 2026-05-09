package server.gameserver.packets.server_udp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Byte-level tests for {@link WorldWeather}
 * (UDP S→C reliable {@code 0x03/0x2e}).
 *
 * <p>Verified against {@code docs/protocol/_data/packets.json}
 * (869 retail samples). Layout (24 bytes total = 11-byte
 * outer-frame envelope + 13-byte body):
 *
 * <pre>
 *   0x00  0x13                UDP gamedata header
 *   0x01  short    counter    outer counter (LE)
 *   0x03  short    ckey       counter+sessionkey (LE)
 *   0x05  short    size       sub-packet length = total - 7
 *   0x07  byte     0x03       reliable wrapper
 *   0x08  short    seqCounter reliable sub-sequence
 *   0x0a  byte     0x2e       Weather sub-type
 *   0x0b  byte     0x01       weather_type (constant in retail)
 *   0x0c  byte     active     0x00 inactive / 0x01 active
 *   0x0d  3B       reserved   00 00 00
 *   0x10  int      start_time LE32 minutes-of-day
 *   0x14  int      end_time   LE32 typically equals start
 * </pre>
 */
public class WorldWeatherTest {

    @Test
    public void defaultConstructorEmitsInactiveAtNoon() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(4);   // retained for compat, no longer encoded

        DatagramPacket[] dps = new WorldWeather(pl).getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        assertEquals("expected 24 bytes for the reliable Weather "
                + "packet, got " + PacketTestFixture.hex(b),
                24, b.length);
        assertEquals(0x13, b[0] & 0xFF);
        assertEquals(0x03, b[7] & 0xFF);
        assertEquals("Weather sub-type", 0x2e, b[10] & 0xFF);

        // body[0] type, [1] active, [2..4] reserved
        assertEquals(0x01, b[11] & 0xFF);                 // type
        assertEquals(WorldWeather.WEATHER_INACTIVE,
                b[12] & 0xFF);                            // active
        assertEquals(0x00, b[13] & 0xFF);
        assertEquals(0x00, b[14] & 0xFF);
        assertEquals(0x00, b[15] & 0xFF);

        // start_time + end_time both = 1440 (0x05a0 LE32)
        int startLo = b[16] & 0xFF, startHi = b[17] & 0xFF;
        assertEquals(0xa0, startLo);
        assertEquals(0x05, startHi);
        assertEquals(0x00, b[18] & 0xFF);
        assertEquals(0x00, b[19] & 0xFF);
        // end_time
        assertEquals(0xa0, b[20] & 0xFF);
        assertEquals(0x05, b[21] & 0xFF);
        assertEquals(0x00, b[22] & 0xFF);
        assertEquals(0x00, b[23] & 0xFF);
    }

    @Test
    public void activeStormEncodesLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(1);

        // start = 0x000005a0 (1440), end = 0x01020304
        WorldWeather pkt = new WorldWeather(pl,
                WorldWeather.WEATHER_ACTIVE,
                0x000005a0, 0x01020304);
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        assertEquals(24, b.length);
        // active = 1
        assertEquals(WorldWeather.WEATHER_ACTIVE, b[12] & 0xFF);

        // start_time LE 0x000005a0
        assertEquals(0xa0, b[16] & 0xFF);
        assertEquals(0x05, b[17] & 0xFF);
        assertEquals(0x00, b[18] & 0xFF);
        assertEquals(0x00, b[19] & 0xFF);

        // end_time LE 0x01020304
        assertEquals(0x04, b[20] & 0xFF);
        assertEquals(0x03, b[21] & 0xFF);
        assertEquals(0x02, b[22] & 0xFF);
        assertEquals(0x01, b[23] & 0xFF);
    }

    @Test
    public void retailSampleByteEqualSnapshotNoon() {
        // Pin against retail catalog sample #3:
        //   01 00 00 00 00 a0 05 00 00 a0 05 00 00
        // (inactive, t=1440 noon, end=start)
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        DatagramPacket[] dps = new WorldWeather(pl).getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        byte[] expectedBody = {
                0x01, 0x00, 0x00, 0x00, 0x00,
                (byte) 0xa0, 0x05, 0x00, 0x00,
                (byte) 0xa0, 0x05, 0x00, 0x00
        };
        byte[] actualBody = new byte[13];
        System.arraycopy(b, 11, actualBody, 0, 13);
        assertArrayEquals("body must match retail catalog sample "
                + "#3 (01 00 00 00 00 a0 05 00 00 a0 05 00 00)",
                expectedBody, actualBody);
    }

    @Test
    public void outerHeaderSizeBytePatchedCorrectly() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        DatagramPacket[] dps = new WorldWeather(pl).getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        int innerSize = (b[5] & 0xFF) | ((b[6] & 0xFF) << 8);
        // innerSize = total - 7 = 17 for a 24-byte packet
        // (length field is at offset 5; payload starts at 7).
        assertEquals(24 - 7, innerSize);
    }
}
