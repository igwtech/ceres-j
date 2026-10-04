package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP13;
import server.tools.Timer;

/**
 * Server's reply to a {@code 0x0b CPing} from the client.
 *
 * <p><b>Wire format:</b> an ordinary {@code 0x13} datagram carrying one
 * unreliable 9-byte sub-packet (16 bytes total):
 * <pre>
 *   [0x13][counter LE2][counter+sessionkey LE2][sub_len=9 LE2]
 *   [0x0b][server_time LE4][client_time echo LE4]
 * </pre>
 * The outer counter is the current reliable counter, not consumed —
 * exactly what {@link PacketBuilderUDP13} writes.
 *
 * <p>Retail ground truth is the client's own decrypted receive path
 * (Frida harness {@code udp_recv}): 884 S→C {@code 0x0b} replies
 * across the retail traces, every one inside a {@code 0x13} frame,
 * none at transport level. Sample (retail_draw_v3, t=2.724, reply to
 * C→S {@code 0b abaa9502}):
 * {@code 13 4700 3181 0900 0b 6d4a6a00 abaa9502}.
 *
 * <p>Commit bbf3b35 (2026-05-09) had switched this to a bare 9-byte
 * transport datagram after reading HANNIBAL through PcapReplay, which
 * unwraps {@code 0x13} frames into their inner sub-packets — so the
 * retail record looked "raw". Retail never sends {@code 0x0b} at
 * transport level.
 */
public class SPing extends PacketBuilderUDP13 {

	public SPing(int clienttime, Player pl) {
		super(pl);
		write(0x0b);
		writeInt(Timer.getIngametime() + 10);
		writeInt(clienttime);
	}
}
