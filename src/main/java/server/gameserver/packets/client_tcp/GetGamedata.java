package server.gameserver.packets.client_tcp;

import server.gameserver.GameServerTCPConnection;
import server.gameserver.Player;
import server.gameserver.internalEvents.DummyEvent;
import server.gameserver.packets.GamePacketDecoderTCP;
import server.gameserver.packets.server_tcp.Gamedata;
import server.gameserver.packets.server_tcp.Location;
import server.gameserver.packets.server_tcp.Sync;
import server.gameserver.packets.server_tcp.UDPServerData;
import server.gameserver.packets.server_udp.UDPAlive;
import server.tools.Timer;

public class GetGamedata extends GamePacketDecoderTCP {

	public GetGamedata(byte[] arg0) {
		super(arg0);
	}

	public void execute(GameServerTCPConnection tcp) {
		tcp.send(new Gamedata());
	}

	public void execute(Player pl) {
		pl.addEvent(new GetGamedataAnswer());
	}
	
	class GetGamedataAnswer extends DummyEvent {
		public GetGamedataAnswer() {
			eventTime = Timer.getRealtime() + 200;
		}

		public void execute(Player pl) {
			pl.send(new Gamedata());
			// UDPServerData (0x83 0x05) MUST arrive BEFORE Location
			// (0x83 0x0c). The client's TCP handler routes 0x83 0x05
			// into the NetMgr queue where FUN_0055aa30 case '\x05'
			// ("Client accepted") populates +0x2d4/+0x2d8 (worldserver
			// IP/port for FUN_00559520's session-create call). If
			// Location arrives first, it triggers a world-change that
			// starts the state machine before those fields are set,
			// and the client joins a session at uninitialised memory
			// → "Connection to worldserver failed" after 15 seconds.
			pl.send(new UDPServerData(pl));
			pl.send(new Location(pl));
		}
	}
	/*
	class GetGamedataAnswer2 extends DummyEvent {
		public GetGamedataAnswer2() {
			eventTime = Timer.getRealtime() + 100;
		}
		
		public void execute(Player pl) {
			pl.send(new Gamedata());
			pl.addEvent(new GetGamedataAnswer3());
		}
	}
	
	class GetGamedataAnswer3 extends DummyEvent {
		public GetGamedataAnswer3() {
			eventTime = Timer.getRealtime() + 200;
		}
		
		public void execute(Player pl) {
			pl.send(new Location(pl));
			pl.send(new UDPAlive(pl));
			//pl.addEvent(new GetGamedataAnswer4());
		}
	}*/
}
