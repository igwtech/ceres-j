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
		// Raw 0x1b world-actor PLACEMENT/creation record. The client
		// instantiates the renderable world actor from THIS packet's
		// position; the reliable 0x03/0x28 then names + scripts it. This
		// is the missing link in the bridged-mob render chain.
		//
		// Byte-pinned 2026-06-14 by re-decoding the SAME retail pcap
		// strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap (server
		// 157.90.195.74): 22 scripted-NPC ids (>= 0x0100) appear in 749
		// raw 0x1b records — e.g. WSK (266 = 0x010a) 92x:
		//   1b 0a01 0000 1f a078 107f 708a 40 bd030000 ff11
		// keyed by entity id [1..2], with Y/Z/X [6..11] BYTE-IDENTICAL to
		// that NPC's 0x28. This DISPROVES the task #178d note ("no id
		// >= 0x0100 ever appears in a 0x1b") — that decode was wrong.
		// #178d saw the real "appears then disappears" symptom but
		// mis-attributed it: the OLD body here was MALFORMED (17 B, route
		// 0x42, HP+random tail) so the client GC'd the actor. The fix is
		// to frame the 0x1b correctly (19 B, route 0x40, heading LE32 +
		// phase LE16), NOT to drop it. Coords reuse the same getters the
		// 0x28 writes so the two stay byte-identical.
		write(0x1b);                       // [0]   sub-opcode
		writeShort(npc.getMapID());        // [1-2] entity_id (== 0x28 [3-4])
		write(0x00);                       // [3]
		write(0x00);                       // [4]
		write(0x1f);                       // [5]   flag
		writeShort(npc.getYpos());         // [6-7]   Y == 0x28 [13-14]
		writeShort(npc.getZpos());         // [8-9]   Z == 0x28 [15-16]
		writeShort(npc.getXpos());         // [10-11] X == 0x28 [17-18]
		write(0x40);                       // [12]  route flag (retail 0x40, was 0x42)
		writeInt(npc.getAngle() & 0xFFFF); // [13-16] heading LE32 (anim; not load-bearing)
		writeShort(0x0000);                // [17-18] idle heading-phase (sweeps in retail; 0 valid)
		
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
