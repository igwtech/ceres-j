package server.gameserver.packets.client_udp;

import java.net.DatagramPacket;

import server.gameserver.Player;
import server.gameserver.internalEvents.DummyEvent;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.CharInfo;
import server.gameserver.packets.server_udp.PositionUpdate;
import server.gameserver.packets.server_udp.TimeSync;
import server.gameserver.packets.server_udp.UDPAlive;
import server.gameserver.packets.server_udp.UpdateModel;
import server.tools.Out;
import server.tools.Timer;

public class HandshakeUDP extends GamePacketDecoderUDP {

	public HandshakeUDP(DatagramPacket dp) {
		super(dp);
	}

	public void execute(Player pl) {
		skip(9);
		pl.getUdpConnection().setInterfaceId(read());

		/*if  (!pl.getUdpConnection().getHandshakingState()) {
			pl.getUdpConnection().setHandshakingState(true);
			pl.addEvent(new HandshakeUDPAnswer());
		}*/
		pl.addEvent(new HandshakeUDPAnswer());
	}

	class HandshakeUDPAnswer extends DummyEvent {
		public HandshakeUDPAnswer() {
			eventTime = Timer.getRealtime() + 100;
		}
		
		public void execute(Player pl) {
			pl.send(new UDPAlive(pl));
			if  (!pl.getUdpConnection().getHandshakingState()) {
				pl.getUdpConnection().setHandshakingState(true);
				pl.addEvent(new HandshakeUDPAnswer2());
			}
		}
	}

	class HandshakeUDPAnswer2 extends DummyEvent {
		public HandshakeUDPAnswer2() {
			eventTime = Timer.getRealtime();
		}
		
		public void execute(Player pl) {
			pl.getUdpConnection().setHandshakingState(false);
			pl.setloggedin();
			Out.writeln(Out.Info, "HandshakeUDPAnswer2: player logged in, sending world init");
			pl.send(new UDPAlive(pl));
			pl.send(new UpdateModel(pl));
			try { pl.send(new CharInfo(pl)); } catch (Exception e) {
				Out.writeln(Out.Error, "CharInfo failed: " + e.getMessage());
			}
			try { pl.send(new TimeSync(pl, 0)); } catch (Exception e) {
				Out.writeln(Out.Error, "TimeSync failed: " + e.getMessage());
			}
			try { pl.send(new PositionUpdate(pl)); } catch (Exception e) {
				Out.writeln(Out.Error, "PositionUpdate failed: " + e.getMessage());
			}
		}
	}

}
