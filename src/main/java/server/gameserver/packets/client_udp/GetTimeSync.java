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
		pl.send(new TimeSync(pl, clienttime));
//		new ZoneEntered().send(session);
		pl.getZone().sendPlayersinZone(pl);
		pl.getZone().sendnewPlayerinZone(pl);
		// Note: a previous attempt (commit 69ff2d3) emitted
		// InfoResponse.zoneInfo here based on DRSTONE3 step 10
		// evidence. Reverted 2026-05-09 — NORMAN replay showed
		// retail emits zoneInfo at step 9 (RequestPositionUpdate)
		// not step 10. The DRSTONE3 step-10 zoneInfo is likely a
		// buffered emission from earlier (delayed by the 814B
		// multipart 0x03/0x2c at step 9). Moved to
		// RequestPositionUpdate.execute() instead.
	}
}
