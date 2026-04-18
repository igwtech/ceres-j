package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1302;

/**
 * Weather initialization via the {@code 0x02} wrapper.
 *
 * <p>Retail sends {@code 0x02→0x2e} with inner payload:
 * {@code 01 00 [flags] [zone_id] [time_lo LE4] [time_hi LE4]}
 *
 * <p>Retail examples:
 * <pre>
 *   ACC1: 01 00 00 00 00 a0 05 00 00 a0 05 00 00  (time=0x000005a0=1440)
 *   ACC2: 01 00 00 64 01 dd 3e 05 00 dd 3e 05 00  (time=0x00053edd=343773)
 * </pre>
 */
public class InitWeather02 extends PacketBuilderUDP1302 {
    public InitWeather02(Player pl) {
        super(pl);
        write(0x2e); // Weather sub-type
        write(0x01);
        write(0x00);
        write(0x00); // flags
        write(0x00); // zone variant
        write(0x00); // time
        writeInt(0x000005a0); // time of day (1440 = noon)
        writeInt(0x000005a0); // same
    }
}
