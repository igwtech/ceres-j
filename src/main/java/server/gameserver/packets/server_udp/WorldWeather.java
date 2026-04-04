package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Weather packet (0x13 -> 0x03 -> 0x2e) sent during the world-entry sequence
 * to tell the client about the current zone weather.
 *
 * Layout (reliable sub-packet payload):
 * <pre>
 *   byte    0x2e              reliable sub-type (Weather)
 *   short   mapId             player's map-id
 *   byte    weatherId         0 = clear, 1 = rain, 2 = fog, 3 = storm
 *   byte    intensity         0x00..0xff intensity scaler
 *   int     duration          duration in seconds (little-endian)
 * </pre>
 *
 * Defaults to clear weather when the no-argument style is used.
 */
public class WorldWeather extends PacketBuilderUDP1303 {

    public static final int WEATHER_CLEAR = 0;
    public static final int WEATHER_RAIN  = 1;
    public static final int WEATHER_FOG   = 2;
    public static final int WEATHER_STORM = 3;

    public WorldWeather(Player pl) {
        this(pl, WEATHER_CLEAR, 0, 0);
    }

    public WorldWeather(Player pl, int weatherId, int intensity, int duration) {
        super(pl);
        write(0x2e);
        writeShort(pl.getMapID());
        write(weatherId);
        write(intensity);
        writeInt(duration);
    }
}
