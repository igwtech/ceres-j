package server.gameserver.packets.server_udp;

import static org.junit.Assert.assertEquals;

import java.net.DatagramPacket;

import org.junit.Test;

import server.gameserver.Player;

/**
 * Tests for the {@link WorldWeather} packet layout.
 *
 * Layout (total 18 bytes):
 * <pre>
 *   0x00  0x13                UDP gamedata header
 *   0x01  short    counter    outer counter (LE)
 *   0x03  short    ckey       counter+sessionkey (LE)
 *   0x05  byte     size       inner size = total - 6
 *   0x06  byte     0x03       reliable wrapper
 *   0x07  short    seqCounter reliable sub-sequence
 *   0x09  byte     0x2e       Weather sub-type
 *   0x0a  short    mapId      LE
 *   0x0c  byte     weatherId  0..3
 *   0x0d  byte     intensity  0..255
 *   0x0e  int      duration   LE seconds
 * </pre>
 */
public class WorldWeatherTest {

    @Test
    public void defaultConstructorUsesClearWeather() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(4);

        DatagramPacket[] dps = new WorldWeather(pl).getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        assertEquals("expected 18 bytes for a single-reliable Weather packet, got "
                + PacketTestFixture.hex(b), 18, b.length);
        assertEquals(0x13, b[0] & 0xFF);
        assertEquals(0x03, b[6] & 0xFF);
        assertEquals("Weather sub-type", 0x2e, b[9] & 0xFF);

        // mapId LE = 4
        assertEquals(4, b[10] & 0xFF);
        assertEquals(0, b[11] & 0xFF);

        // Clear weather defaults
        assertEquals("weatherId", WorldWeather.WEATHER_CLEAR, b[12] & 0xFF);
        assertEquals("intensity", 0, b[13] & 0xFF);
        assertEquals("duration byte 0", 0, b[14] & 0xFF);
        assertEquals("duration byte 1", 0, b[15] & 0xFF);
        assertEquals("duration byte 2", 0, b[16] & 0xFF);
        assertEquals("duration byte 3", 0, b[17] & 0xFF);
    }

    @Test
    public void customStormValuesEncodeLittleEndian() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(1);

        WorldWeather pkt = new WorldWeather(pl, WorldWeather.WEATHER_STORM, 0xff, 0x01020304);
        DatagramPacket[] dps = pkt.getDatagramPackets();
        byte[] b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        assertEquals(18, b.length);
        assertEquals(WorldWeather.WEATHER_STORM, b[12] & 0xFF);
        assertEquals(0xff, b[13] & 0xFF);

        // Duration little-endian 0x01020304
        assertEquals(0x04, b[14] & 0xFF);
        assertEquals(0x03, b[15] & 0xFF);
        assertEquals(0x02, b[16] & 0xFF);
        assertEquals(0x01, b[17] & 0xFF);
    }

    @Test
    public void outerHeaderSizeBytePatchedCorrectly() {
        Player pl = PacketTestFixture.newPlayerWithFixedSessionKey((short) 0);
        pl.setMapID(1);

        byte[] b;
        DatagramPacket[] dps = new WorldWeather(pl).getDatagramPackets();
        b = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, b, 0, b.length);

        int innerSize = b[5] & 0xFF;
        // innerSize = total - 6 = 12 for an 18-byte packet (the size byte is
        // written as count - sizeposition - 1 where sizeposition = 5).
        assertEquals(18 - 6, innerSize);
    }
}
