package server.gameserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

/**
 * Unknown TCP packet 0x83 0x0d sent by retail between UDPServerData
 * (0x83 0x05) and Location (0x83 0x0c).
 *
 * <p>Retail TCP capture shows: {@code \xfe\x04\x00\x83\x0d\x00\x00}
 * — a 4-byte payload (0x83, 0x0d, 0x00, 0x00).
 */
public class Packet830D extends PacketBuilderTCP {
    public Packet830D() {
        write(0x83);
        write(0x0d);
        write(0x00);
        write(0x00);
    }
}
