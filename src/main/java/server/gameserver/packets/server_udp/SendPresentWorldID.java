package server.gameserver.packets.server_udp;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.gameserver.internalEvents.DummyEvent;
import server.networktools.PacketBuilderUDP13;
import server.tools.Timer;

import java.util.Random;

public class SendPresentWorldID extends PacketBuilderUDP13{
	
	public SendPresentWorldID(Player pl, NPC npc) {
		super(pl);
		write(0x1b);
		writeShort(npc.getMapID());		// MapID
		write(0x00);
		write(0x00);
		write(0x1f);		// 
		writeShort(npc.getYpos());	// Y-Pos
		writeShort(npc.getZpos());	// Z-Pos
		writeShort(npc.getXpos());	// X-Pos
		write(0x42);		// Status
		writeShort(npc.getHP());	// HP
		writeShort(new Random().nextInt());
		//writeShort(4622);
		
		//pl.addEvent(new sendNPCsEvent());
	}
	
	/*class sendNPCsEvent extends DummyEvent {
		public sendNPCsEvent() {
			eventTime = Timer.getRealtime() + 15000;
		}
		
		public void execute(Player pl) {
			if(pl.isAlive())
				pl.getZone().sendNPCsinZone(pl);
		}
	}*/
}
