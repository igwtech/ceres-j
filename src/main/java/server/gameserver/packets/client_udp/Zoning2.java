package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.internalEvents.DummyEvent;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_tcp.InteractionAck;
import server.gameserver.packets.server_tcp.Location;
import server.gameserver.packets.server_tcp.Packet830D;
import server.gameserver.packets.server_udp.UDPAlive;
import server.tools.Timer;

/**
 * Client "ready to load new zone" packet (reliable {@code 0x03/0x22}
 * sub {@code 0x03}). Restored to the legacy handler while we figure
 * out the retail two-phase Zoning1+Zoning2 flow.
 */
public class Zoning2 extends GamePacketDecoderUDP {

	public Zoning2(byte[] subPacket) {
		super(subPacket);
	}

	public void execute(Player pl) {
		pl.send(new Packet830D());
		pl.send(new Location(pl));
		// Retail closes zone-transition transactions with the
		// InteractionAck pair (0xa0 0x02 ×2). See InteractionAck
		// javadoc for catalog evidence.
		pl.send(new InteractionAck());
		pl.send(new InteractionAck());
		pl.addEvent(new Zoning2Answer());
	}

	class Zoning2Answer extends DummyEvent {
		public Zoning2Answer() {
			eventTime = Timer.getRealtime() + 20;
		}

		public void execute(Player pl) {
			pl.send(new UDPAlive(pl));
		}
	}
}
