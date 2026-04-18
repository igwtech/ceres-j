package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1302;

/**
 * Soullight (soul energy) initialization via the {@code 0x02} wrapper.
 *
 * <p>Retail sends {@code 0x02→0x1f} with inner payload
 * {@code 01 00 25 1f 00 00 c8 42} — where {@code 00 00 c8 42} is
 * the IEEE 754 float for 100.0 (the Soullight value). This appears
 * in every retail capture alongside the standard {@code 01 00 25 23 XX}
 * TimeSync heartbeats.
 *
 * <p>Without this packet the client may not initialize the Soullight
 * meter, which could prevent full session initialization.
 */
public class InitSoullight02 extends PacketBuilderUDP1302 {
    public InitSoullight02(Player pl) {
        super(pl);
        write(0x1f); // GamePackets sub-type
        write(0x01); // variant
        write(0x00);
        write(0x25); // constant tag
        write(0x1f); // soullight sub-opcode (different from 0x23 TimeSync)
        // Soullight value as IEEE 754 float LE = 100.0
        writeFloat(100.0f);
    }
}
