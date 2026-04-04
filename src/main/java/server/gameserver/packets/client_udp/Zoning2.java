package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.internalEvents.DummyEvent;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_tcp.Location;
import server.gameserver.packets.server_tcp.Sync;
import server.gameserver.packets.server_udp.UDPAlive;
import server.tools.Timer;

public class Zoning2 extends GamePacketDecoderUDP {

	public Zoning2(byte[] subPacket) {
		super(subPacket);
	}

	public void execute(Player pl) {
		pl.send(new Sync());
		pl.send(new Location(pl));
		
		pl.addEvent(new Zoning2Answer());
	}
	
	class Zoning2Answer extends DummyEvent {
		public Zoning2Answer() {
			eventTime = Timer.getRealtime() + 20;
		}
		
		public void execute(Player pl) {
			pl.send(new UDPAlive(pl));
			//pl.send(new UpdateModel(pl)); <--- TODO: should be added here but is not correct yet
		}
	}

}
