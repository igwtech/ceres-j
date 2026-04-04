package server.gameserver.packets.client_udp;

import java.net.DatagramPacket;

import server.gameserver.Player;
import server.gameserver.internalEvents.DummyEvent;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.UpdateModel;
import server.tools.Timer;

public class SyncUDP extends GamePacketDecoderUDP {

	public SyncUDP(DatagramPacket dp) {
		super(dp);
	}

	public void execute(Player pl) {
//TODO		pl.getZone().registerPlayer(pl.getCharacter());
		//pl.addEvent(new UpdateModelEvent());
		//pl.addEvent(new sendNPCsEvent());
		//pl.addEvent(new sendtoPlayersEvent());
	}

	class UpdateModelEvent extends DummyEvent {
		public UpdateModelEvent() {
			eventTime = Timer.getRealtime() + 1000;
		}
		
		public void execute(Player pl) {
			pl.send(new UpdateModel(pl)); //TODO after syncs this is correct, after login not
		}
	}
	
	class sendNPCsEvent extends DummyEvent {
		public sendNPCsEvent() {
			eventTime = Timer.getRealtime() + 200;
		}
		
		public void execute(Player pl) {
			pl.getZone().sendNPCsinZone(pl);
		}
	}
	
	/*class sendtoPlayersEvent extends DummyEvent {
		public sendtoPlayersEvent() {
			eventTime = Timer.getRealtime() + 500;
		}
		
		public void execute(Player pl) {
			pl.getZone().sendnewPlayerinZone(pl);
		}
	}*/
}
