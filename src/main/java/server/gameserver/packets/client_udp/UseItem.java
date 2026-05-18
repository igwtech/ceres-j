package server.gameserver.packets.client_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.PortalResolver;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_tcp.InteractionAck;
import server.gameserver.packets.server_tcp.Packet838F;
import server.gameserver.packets.server_udp.ChangeLocation;
import server.gameserver.packets.server_udp.LocalChatMessage;
import server.gameserver.packets.server_udp.OpenDoor;
import server.tools.Out;

public class UseItem extends GamePacketDecoderUDP {

	public UseItem(byte[] subPacket) {
		super(subPacket);
	}

	public void execute(Player pl) {
		skip(7);
		int id = readInt();

		// Retail interaction-commit ack. Must arrive BEFORE any
		// state-change packets (OpenDoor, animation broadcasts,
		// vendor/trade window open, ...). Body invariant
		// `83 8f 00 00 00 00`. See Packet838F javadoc.
		pl.send(new Packet838F());

		// ── Zone-transition portal (furniture / world-change actor) ──
		// TinNS UdpUseObject.cxx: a static-actor rawItemID with the
		// low 10 bits clear (rawItemID & 1023 == 0) is a .dat
		// furniture object; its .dat object index is
		// rawItemID/1024 - 1. If that object's worldmodel.def entry
		// has a zone-change functionType (15/18/20/29), resolve the
		// destination via appplaces.def and emit ChangeLocation.
		// Doors (rawItemID & 1023 != 0, handled below via OpenDoor)
		// are NEVER portals (doc §2a). Walking sector borders
		// (plaza p1↔p2↔p3↔p4) are a separate coordinate-limit
		// mechanism handled elsewhere — out of scope here.
		if ((id & 1023) == 0) {
			int objectId = id / 1024 - 1;
			server.gameserver.Zone z = pl.getZone();
			String worldname = (z == null) ? null : z.getWorldname();
			String worldPath =
				PortalResolver.worldnameToObjectPath(worldname);

			// ── Chair (seatable furniture) ──────────────────────────
			// A static furniture object whose worldmodel.def UseFlags
			// (f1) has the ufChair bit (8) set is a chair. Clicking it
			// sits the player; the server broadcasts the seated
			// posture (0x03/0x1f/<localId>/0x21) to the whole zone and
			// echoes the rawObjectId unchanged. Byte-pinned from
			// strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap (C→S 0x17
			// id 0x00084800 → S→C 1f 03 00 21 00 48 08 00 00). Chairs
			// are NEVER portals, so this is checked before the
			// portal-resolve fall-through. See PortalResolver#isChair
			// and SitOnChair.
			if (z != null
					&& PortalResolver.isChair(worldPath, objectId)) {
				boolean reseat = pl.getSeatedChairRawId() == id;
				pl.setSeatedChairRawId(id);
				// First sit on a given object → the acting player gets a
				// byte-identical 0x03/0x1f sub-action 0x17 echo. This is
				// the ONLY packet that transitions the LOCAL player into
				// the seated state (client FUN_0064ec90 case 0x17 →
				// FUN_007a4890); the 0x21 broadcast below only updates
				// peers' observed posture (case 0x21 → FUN_00662c00).
				// Without this echo the acting player never visibly
				// sits. Byte-pinned from
				// strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap:
				//   t=199.386941 C→S 1f 03 00 17 00 c8 0c 00
				//   t=199.833702 S→C 1f 03 00 17 00 c8 0c 00 (echo)
				// (RE_tcp_confirm.md §3.2(a), §3.4). Retail sends the
				// 0x17 echo once per NEW object, then 0x21 for
				// refresh/observers — so it is gated on !reseat and
				// sent ONLY to the acting player.
				if (!reseat) {
					pl.send(new server.gameserver.packets.server_udp
							.SitConfirm(pl, pl.getMapID(), id));
				}
				// seatId 0 = real chair (1+ would be a subway cab; not
				// derivable from worldmodel.def — retail sample is 0).
				z.sendPlayerSit(pl, id, 0);
				Out.writeln(Out.Info,
					"UseItem: " + (reseat ? "re-seat" : "sit")
					+ " on chair rawObjectId=" + id
					+ " (objectId=" + objectId + ") in '"
					+ worldname + "'");
				// Same interaction-commit contract as the portal /
				// door paths: 0x83 0x8f already sent above, then the
				// transaction-ack PAIR after the state-change packet.
				pl.send(new InteractionAck());
				pl.send(new InteractionAck());
				return;
			}

			PortalResolver.Portal portal =
				PortalResolver.resolve(worldPath, objectId);
			if (portal != null) {
				Out.writeln(Out.Info,
					"UseItem: zone-change actor objectId=" + objectId
					+ " in '" + worldname + "' → " + portal);

				// Commit the destination zone server-side. The
				// client self-positions from the Entity index
				// against the destination .dat — the server sends
				// NO coordinates (doc §4). Mirror the existing
				// zone-commit path (Zoning1.SZoning1ConfirmEvent):
				// set MISC_LOCATION then updateZone().
				PlayerCharacter pc = pl.getCharacter();
				if (pc != null) {
					pc.setMisc(PlayerCharacter.MISC_LOCATION,
						portal.exitWorldId);
					pl.updateZone();
				}

				// Zone/portal/world-change TCP confirm. Per
				// RE_tcp_confirm.md §2/§2.1/§7.3 (and the retail pcap
				// strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap:
				//   t=238.352  S→C TCP 0x83/0x0d  fe0400830d0000
				//   t=238.847  S→C TCP 0x83/0x0c  fe1d00830c65…"plaza/plaza_p3"
				// ), every path that changes a player's zone MUST emit
				// 0x83/0x0d (loading UI begin) THEN 0x83/0x0c (Location:
				// destination BSP). The client runs the world-load
				// state machine ONLY on 0x83/0x0c (Ghidra FUN_0055aa30
				// case '\f'/'\r' → FUN_00558950). The portal path is a
				// zone change but the UDP ChangeLocation (0x03/0x1f/0x38)
				// alone never triggers it — without the TCP pair the
				// client never loads the destination world. Location is
				// built AFTER the zone commit above so it resolves the
				// destination BSP. Order is mandatory: 0x83/0x0d first.
				if (pl.getTcpConnection() != null) {
					pl.send(new server.gameserver.packets.server_tcp
							.Packet830D());
					pl.send(new server.gameserver.packets.server_tcp
							.Location(pl));
				} else {
					Out.writeln(Out.Warning,
						"UseItem: portal zone-change for "
						+ (pc == null ? "?" : pc.getName())
						+ " has no TCP connection — world-load "
						+ "confirm (0x83/0x0d→0x83/0x0c) dropped");
				}

				// Emit ChangeLocation (0x03/0x1f/<localId>/0x38).
				pl.send(new ChangeLocation(pl,
					portal.exitWorldId,
					portal.exitWorldEntity,
					portal.entityTypeByte));

				// Retail emits the transaction-ack PAIR after the
				// state-change packet (same contract as the door
				// path below). Without it the client's interaction
				// lock-out never releases.
				pl.send(new InteractionAck());
				pl.send(new InteractionAck());
				return;
			}
		}

		String text = new String();
		text += "UnknownItem ID: " + id + " at pos: y:" +
		Float.floatToIntBits((float)pl.getCharacter().getMisc(PlayerCharacter.MISC_Y_COORDINATE)) +
		" z:" +
		Float.floatToIntBits((float)pl.getCharacter().getMisc(PlayerCharacter.MISC_Z_COORDINATE)) +
		" x:" +
		Float.floatToIntBits((float)pl.getCharacter().getMisc(PlayerCharacter.MISC_X_COORDINATE));
		Out.writeln(Out.Info, text);
		pl.send(new LocalChatMessage(pl, text));

		pl.send(new OpenDoor(id, pl));

		// Retail emits the transaction-ack PAIR (a0 02) AFTER the
		// state-change packets. Sequence over the wire:
		//   0x83 0x8f  (commit, pre-state-change)   ← already sent
		//   state-change packets (OpenDoor, etc.)   ← already sent
		//   0xa0 0x02 ×2 (transaction-ack pair)     ← below
		// See InteractionAck javadoc for catalog evidence.
		pl.send(new InteractionAck());
		pl.send(new InteractionAck());
	}

}
