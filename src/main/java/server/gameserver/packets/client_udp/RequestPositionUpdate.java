package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.CharInfo;
import server.gameserver.packets.server_udp.InfoResponse;
import server.gameserver.packets.server_udp.PositionUpdate;

public class RequestPositionUpdate extends GamePacketDecoderUDP  {

	public RequestPositionUpdate(byte[] subPacket) {
		super(subPacket);
		//content is still unknown
	}

	public void execute(Player pl) {
		pl.send(new PositionUpdate(pl));
		//TODO this is just a workaround
		pl.send(new CharInfo(pl));
		// Retail emits a 7-byte 0x03/0x23 zoneInfo
		// {@code [20 00 ?? 00 00 00]} after PositionUpdate +
		// CharInfo on RequestPositionUpdate. Verified 2026-05-09
		// against NORMAN step 9: retail's S→C queue pairs Ceres-J's
		// PositionUpdate with retail's first emit, then expects
		// 0x03/0x23 zoneInfo next. body[2] is session/zone state
		// (0x10/0x01/0x84/0x00 across captures) — pcap-replay
		// harness masks that byte.
		pl.send(InfoResponse.zoneInfo(pl));
	}
}
