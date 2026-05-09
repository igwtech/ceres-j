package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Reliable weather packet ({@code 0x13 → 0x03 → 0x2e}) — the
 * full-reliable counterpart to {@link InitWeather02}.
 *
 * <h3>Wire format (13-byte body, verified against catalog
 * {@code docs/protocol/packets/udp_s2c_03_2e.md}, 869 retail
 * samples across 17/17 captures)</h3>
 *
 * <pre>
 *   offset 0     : weather_type   1B     constant 0x01 in all samples
 *   offset 1     : active_flag    1B     0x00 = clear/inactive, 0x01 = active
 *   offset 2..4  : 0x00 0x00 0x00        reserved
 *   offset 5..8  : start_time     LE32   "minutes-of-day"; 1440 = noon
 *   offset 9..12 : end_time       LE32   typically equals start_time
 * </pre>
 *
 * <h3>Pre-fix bug</h3>
 *
 * <p>The previous emitter wrote an 8-byte body with
 * {@code [mapId LE2][weatherId 1B][intensity 1B][duration LE32]}
 * — wrong shape AND wrong size. A short payload of 8 bytes where
 * the client expects 13 leaves 5 bytes of the next sub-packet
 * misaligned, breaking subsequent reliable traffic in the same
 * datagram. {@link InitWeather02} (the {@code 0x02} sibling) was
 * already byte-correct, so this brings them into alignment.
 *
 * <h3>Sample retail bodies</h3>
 *
 * <pre>
 *   #1  01 01 00 00 00 f3 f0 01 00 f3 f0 01 00   active, t=127219
 *   #2  01 01 00 00 00 f6 f0 01 00 f6 f0 01 00   active, t=127222
 *   #3  01 00 00 00 00 a0 05 00 00 a0 05 00 00   inactive, t=1440 (noon)
 * </pre>
 */
public class WorldWeather extends PacketBuilderUDP1303 {

    public static final int WEATHER_INACTIVE = 0;
    public static final int WEATHER_ACTIVE   = 1;

    /** Default time-of-day: 1440 = noon (matches retail's
     *  most common catalog sample). */
    public static final int DEFAULT_TIME_OF_DAY = 1440;

    /** Default constructor: clear/inactive weather at noon. */
    public WorldWeather(Player pl) {
        this(pl, WEATHER_INACTIVE,
                DEFAULT_TIME_OF_DAY, DEFAULT_TIME_OF_DAY);
    }

    /**
     * @param pl         recipient player
     * @param activeFlag 0 = inactive (clear), 1 = active (storm)
     * @param startTime  start timestamp LE32 (minutes-of-day)
     * @param endTime    end timestamp; pass {@code startTime} for
     *                   the snapshot variant retail uses
     */
    public WorldWeather(Player pl, int activeFlag,
                         int startTime, int endTime) {
        super(pl);
        write(0x2e);                          // sub-opcode
        write(0x01);                          // [0] weather_type (constant)
        write(activeFlag & 0xFF);             // [1] active flag
        write(0x00);                          // [2] reserved
        write(0x00);                          // [3] reserved
        write(0x00);                          // [4] reserved
        writeInt(startTime);                  // [5..8] start_time LE32
        writeInt(endTime);                    // [9..12] end_time LE32
    }
}
