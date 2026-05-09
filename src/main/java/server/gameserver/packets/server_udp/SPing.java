package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP;
import server.tools.Timer;

/**
 * Server's reply to a {@code 0x0b CPing} from the client.
 *
 * <p><b>Wire format (verified against retail HANNIBAL pcap
 * 2026-05-09):</b> 9-byte raw datagram (NO 0x13 outer wrapper).
 * <pre>
 *   [0x0b][server_time LE4][client_time echo LE4]
 * </pre>
 *
 * <p>Sample retail bytes:
 * {@code 0b cb6e6900 600ca704} — server_time = 0x00696ecb,
 * client_time = 0xa7040c60.
 *
 * <p>The previous implementation extended {@link
 * server.networktools.PacketBuilderUDP13} which wrapped the
 * reply in a 0x13 frame (16B total: outer header + 9B inner).
 * Pcap-replay harness against HANNIBAL surfaced this 2026-05-09;
 * with the wrapper, the client treats the byte stream as a
 * reliable sub-packet rather than the raw CPing reply it expects.
 */
public class SPing extends PacketBuilderUDP {

    public SPing(int clienttime, Player pl) {
        write(0x0b);
        writeInt(Timer.getIngametime() + 10);
        writeInt(clienttime);
    }
}
