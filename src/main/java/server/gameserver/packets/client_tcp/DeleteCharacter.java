package server.gameserver.packets.client_tcp;

import server.gameserver.GameServerTCPConnection;
import server.gameserver.packets.GamePacketDecoderTCP;
import server.gameserver.packets.server_tcp.CharOpAck;

public class DeleteCharacter extends GamePacketDecoderTCP{

	public DeleteCharacter(byte[] arg0) {
		super(arg0);
	}

	public void execute(GameServerTCPConnection tcp) {
		skip(7); //packet header
		skip(2); // data size
		int unknownsize = readShort();
		skip(unknownsize);
		int spot = read();

		// Retail: delete returns 0x8386 with status 0x05 (delete-success)
		// or 0x06 + ASCII reason. Verified against
		// RETAIL_CHARDEL_SUBWAY_20260503_132639 — see
		// docs/protocol/FINDINGS_2026-05-03_CHARDEL_SUBWAY.md.
		if (tcp.getAccount().deleteChar(spot)) {
			tcp.send(CharOpAck.deleteSuccess());
		} else {
			tcp.send(CharOpAck.error("Delete failed."));
		}
	}

}
