package server.gameserver.packets.client_tcp;

import server.gameserver.GameServerTCPConnection;
import server.gameserver.Player;
import server.gameserver.internalEvents.DummyEvent;
import server.gameserver.packets.GamePacketDecoderTCP;
import server.gameserver.packets.server_tcp.Gamedata;
import server.gameserver.packets.server_tcp.Location;
import server.gameserver.packets.server_tcp.Sync;
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
