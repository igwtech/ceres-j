package server.gameserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

/**
 * TCP keepalive packet 0x83 0x8f sent by retail every ~10 seconds.
 *
 * <p>Retail TCP capture shows: {@code \xfe\x07\x00\x83\x8f\x00\x00\x00\x00\x00}
 * — a 7-byte payload (0x83, 0x8f, 0x00, 0x00, 0x00, 0x00, 0x00).
 */
public class TcpKeepalive extends PacketBuilderTCP {
    public TcpKeepalive() {
        write(0x83);
        write(0x8f);
        write(new byte[]{0x00, 0x00, 0x00, 0x00, 0x00});
    }
}
