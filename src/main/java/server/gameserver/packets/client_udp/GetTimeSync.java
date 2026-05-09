package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.InfoResponse;
import server.gameserver.packets.server_udp.TimeSync;

public class GetTimeSync extends GamePacketDecoderUDP {

	private int clienttime;

	public GetTimeSync(byte[] subPacket) {
		super(subPacket);
		skip(1);
		clienttime = readInt();
	}

	public void execute(Player pl) {
		// Retail emits a 7-byte 0x03/0x23 zoneInfo
		// {@code [20 00 ?? 00 00 00]} BEFORE the TimeSync reply
		// (verified 4/4 captures HANNIBAL/NORMAN/DRSTONE3/AUGUSTO,
		// 2026-05-09; DRSTONE3 step 10 retail queue order:
		// InfoResponse first, then TimeSync). body[2] is session/
		// zone state — varies per session (0x10/0x01/0x84/0x00).
		// Pcap-replay harness DRSTONE3 step 10 surfaced both the
		// missing emit AND the correct ordering.
		pl.send(InfoResponse.zoneInfo(pl));
		pl.send(new TimeSync(pl, clienttime));
//		new ZoneEntered().send(session);
		pl.getZone().sendPlayersinZone(pl);
		pl.getZone().sendnewPlayerinZone(pl);
	}
}
