package server.gameserver.packets.server_tcp;

import server.database.accounts.Account;
import server.networktools.PacketBuilderTCP;

/**
 * AuthAck (0x83 0x81) — response to the client's Auth packet.
 *
 * <p>Retail wire layout (31-byte payload, observed in pcap):
 * <pre>
 *   83 81                      opcode
 *   uint32  account id
 *   uint32  unknown (0)
 *   uint8   unknown (0)
 *   uint16  data_len (LE, = 18)
 *   bytes   session_data (18 bytes, looks like an auth challenge / session
 *                        token; purpose unknown but non-zero in retail)
 *   uint8   null terminator
 * </pre>
 *
 * <p>Before this layout was reverse-engineered from the retail pcap, Ceres-J
 * was sending a 13-byte stub (opcode + id + 4 zeros + 2-byte zero + 0), which
 * is the same size field structure but with a zero data_len and no session
 * data bytes. The modern NC2 client parses the full 31-byte layout and stores
 * the session_data somewhere in its session state — when it's missing the
 * client's post-handshake state machine can't transition to "active game",
 * so the client polls its UDP receive socket forever ("Receive 0 Buffer") and
 * eventually times out with "Connection to worldserver failed".
 *
 * <p>Content of the session_data bytes is currently unknown; we send 18 zero
 * bytes which satisfies the length check and appears sufficient for the
 * client's state transition.
 */
public class AuthAck extends PacketBuilderTCP {

	public AuthAck(Account ua) {
		super();
		// Retail wire (31 payload bytes, positions after fe/len header):
		//   pos 0-1   83 81           opcode
		//   pos 2-5   <account id>    uint32 LE
		//   pos 6-9   00 00 00 00     reserved uint32
		//   pos 10-11 12 00           session_data length = 18 (uint16 LE)
		//   pos 12-29 <18 bytes>      session data (auth challenge/token)
		//   pos 30    00              null terminator
		write(0x83);
		write(0x81);
		writeInt(ua.getId());
		writeInt(0);
		writeShort(18);
		for (int i = 0; i < 18; i++) {
			write(0);
		}
		write(0);
	}
}
