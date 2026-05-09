package server.gameserver.packets.server_tcp;

import server.database.accounts.Account;
import server.networktools.PacketBuilderTCP;

/**
 * AuthAck (0x83 0x81) — response to the client's Auth packet.
 *
 * <p>Retail wire layout (31-byte payload, verified across 50 hits in
 * 17/17 retail captures via {@code docs/protocol/_data/packets.json}):
 * <pre>
 *   off 0-1   83 81           opcode
 *   off 2-5   uint32 LE       account id
 *   off 6-9   uint32 LE       reserved (always 0 in retail)
 *   off 10-11 uint16 LE       session_data length (always 18 in retail)
 *   off 12-29 18 bytes        session_data (server-generated nonce; varies)
 *   off 30    uint8           trailer (observed both 0x00 and 0x2d in retail)
 * </pre>
 *
 * <p>Before this layout was derived from pcap, Ceres-J sent a shorter stub.
 * The modern NC2 client requires the full 31-byte layout to advance past
 * its post-handshake state; without it the client polls its UDP socket
 * forever ("Receive 0 Buffer") and eventually times out.
 *
 * <p>Content of session_data appears to be a server-side cipher/session
 * nonce — it's distinct between retail samples even for the same account.
 * Ceres-J emits 18 zero bytes plus a zero trailer, which satisfies the
 * length check and is enough for the client state transition.
 */
public class AuthAck extends PacketBuilderTCP {

	public AuthAck(Account ua) {
		super();
		// Retail wire (31 payload bytes, positions after fe/len header):
		//   pos 0-1   83 81           opcode
		//   pos 2-5   <account id>    uint32 LE
		//   pos 6-9   00 00 00 00     reserved uint32
		//   pos 10-11 12 00           session_data length = 18 (uint16 LE)
		//   pos 12-29 <18 bytes>      session_data (server nonce; varies in retail)
		//   pos 30    00              trailer byte
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
