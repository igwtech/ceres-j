package server.gameserver.packets.server_tcp;

import server.database.accounts.Account;
import server.networktools.PacketBuilderTCP;

/**
 * AuthAck (0x83 0x81) — response to the client's Auth packet.
 *
 * <p>Retail wire layout (31-byte payload, 50 hits in 17/17 retail
 * captures):
 * <pre>
 *   off 0-1   83 81           opcode
 *   off 2-5   uint32 LE       account id
 *   off 6-9   uint32 LE       reserved (always 0 in retail)
 *   off 10-11 uint16 LE       session_data length (18 for a 9-char password)
 *   off 12-29 18 bytes        session_data = the client's password ciphertext
 *   off 30    uint8           trailer (0x00, 0x2d, 0xad seen in retail)
 * </pre>
 *
 * <p>session_data is the Auth packet's encoded-password bytes echoed back
 * verbatim (retail pcap nc2_ceres_DUNGEON_PORTAL_20260523: Auth
 * {@code … "msn2wolf\0" c29999f9b1b959e973a94b39b7054e994459 2c} →
 * AuthAck {@code 8381 b0a20000 00000000 1200
 * c29999f9b1b959e973a94b39b7054e994459 ad}; a second login repeats the
 * pattern with different bytes). It is the server's proof that it decoded
 * the password, not a nonce. Ceres-J used to send 18 zero bytes.
 *
 * <p>Without the full 31-byte layout the modern NC2 client polls its UDP
 * socket forever ("Receive 0 Buffer") and eventually times out.
 */
public class AuthAck extends PacketBuilderTCP {

	public AuthAck(Account ua, byte[] passwordCipher) {
		super();
		write(0x83);
		write(0x81);
		writeInt(ua.getId());
		writeInt(0);
		writeShort(passwordCipher.length);
		write(passwordCipher);
		write(0);
	}
}
