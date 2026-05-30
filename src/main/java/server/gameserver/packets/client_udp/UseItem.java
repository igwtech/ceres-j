package server.gameserver.packets.client_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.PortalResolver;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_tcp.InteractionAck;
import server.gameserver.packets.server_tcp.Packet838F;
import server.gameserver.packets.server_udp.ChangeLocation;
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
			// Resolve world_objects.world_path with worldinfo.f2
			// override awareness (task #237) — dungeon zones use an
			// alternate .dat filename (reaktor_nc.dat vs reaktor.dat,
			// sewer_p4_x1.dat vs sewer_p4.dat) that's stored in
			// defs.worldinfo.f2. Without this override, all dungeon
			// object lookups fail and exit doors are stuck.
			//
			// Falls back to legacy worldnameToObjectPath() for zones
			// that have no worldinfo.f2 row (plaza/pepper/etc.).
			int zoneId = pl.getCharacter() == null ? -1
				: pl.getCharacter().getMisc(
					PlayerCharacter.MISC_LOCATION);
			String worldPath = (zoneId >= 0)
				? PortalResolver.worldIdToObjectPath(zoneId, worldname)
				: PortalResolver.worldnameToObjectPath(worldname);

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
				// Task #254 — retail 13B variant (RETRY3 pcap
				// 2026-05-24 verified for chair-sit on plaza_p1).
				pl.send(new InteractionAck(true));
				pl.send(new InteractionAck(true));
				return;
			}

			PortalResolver.Portal portal =
				PortalResolver.resolve(worldPath, objectId);
			if (portal != null) {
				Out.writeln(Out.Info,
					"UseItem: zone-change actor objectId=" + objectId
					+ " in '" + worldname + "' → " + portal);

				// MISC_LOCATION commit + DB save are deferred to the
				// PortalCrossCommitEvent (fires ~470ms after this
				// click — see retail-timing comment below). Both
				// run together with the 0x83/0x0c Location packet
				// so the server-side state transition is aligned
				// with the wire moment the client transitions.
				PlayerCharacter pc = pl.getCharacter();

				// Zone/portal/world-change TCP confirm. Per
				// RE_tcp_confirm.md §2/§2.1/§7.3 AND the retail pcap
				// PLAZA_TO_PEPPER_CROSS_DISTRICT_20260502_103513
				// timing analysis (task #172, 2026-05-23):
				//
				//   T+0     TCP 0x83/0x8f  (interaction-commit — sent
				//                          at line 27 above)
				//   T+180ms TCP 0x83/0x0d  (loading UI begin)
				//   T+650ms TCP 0x83/0x0c  (Location: destination BSP)
				//
				// Earlier builds emitted 0x83/0x0d and 0x83/0x0c
				// back-to-back. The modern NCE 2.5 client appears to
				// need the ~470ms gap between them to enter its
				// loading state before processing the new Location —
				// without the gap the client silently drops 0x83/0x0c
				// and the cross never transitions on-screen (verified
				// 2026-05-23 in a Ceres-J pcap: server emitted
				// 830d+830c in 9ms, client never reconnected to
				// destination worldserver, timed out, kicked to
				// login). The 0x83/0x0d alone IS sent immediately;
				// the rest of the burst (0x83/0x0c, ChangeLocation,
				// InteractionAck pair, MISC_LOCATION commit + DB
				// save) is deferred via PortalCrossCommitEvent at
				// +470ms.
				if (pl.getTcpConnection() != null) {
					// Task #253 — emit 0x83/0x0d LoadingBegin ONLY for
					// cross-IN to a NEW BSP. Retail empirically NEVER
					// emits 0x83/0x0d when the destination is already
					// loaded (cross-OUT, re-cross). Verified 2026-05-24
					// in pcap RETRY3: cross-IN to reaktor at frame
					// 310+312 had both 830D + 830C; cross-OUT to
					// plaza_p1 at frame 5906 had ONLY 830C. Always-
					// emitting 830D for a cached BSP likely triggers
					// the client's loading state for a BSP it already
					// has — root-cause hypothesis for #208.
					//
					// PortalResolver returns the destination world_path
					// (worldinfo.f2 lookup, task #237); we resolve it
					// here to check the per-Player BSP cache.
					String destPath = PortalResolver
							.worldIdToObjectPath(
								portal.exitWorldId, null);
					if (destPath == null
							|| !pl.hasLoadedBsp(destPath)) {
						pl.send(new server.gameserver.packets.server_tcp
								.Packet830D());
						Out.writeln(Out.Info,
							"UseItem: 0x83/0x0d LoadingBegin emitted "
							+ "for new-to-client BSP '" + destPath + "'");
					} else {
						Out.writeln(Out.Info,
							"UseItem: 0x83/0x0d suppressed — client "
							+ "already loaded BSP '" + destPath + "'");
					}
					// Task #239 — observe the cross transition.
					// CROSS_PENDING_LOAD regardless of whether
					// LoadingBegin was emitted; the client is
					// still mid-cross either way.
					pl.getStateMachine().transition(
						server.gameserver.state.ClientState
							.CROSS_PENDING_LOAD,
						"UseItem portal: cross initiated");
				} else {
					Out.writeln(Out.Warning,
						"UseItem: portal zone-change for "
						+ (pc == null ? "?" : pc.getName())
						+ " has no TCP connection — world-load "
						+ "confirm (0x83/0x0d→0x83/0x0c) dropped");
				}

				// Defer the rest of the cross burst (Location +
				// ChangeLocation + commit + save + InteractionAck
				// pair) to fire ~470ms after 0x83/0x0d.
				pl.addEvent(new server.gameserver.internalEvents
						.PortalCrossCommitEvent(portal));
				return;
			}
		}

		// Diagnostic only — not a chat-visible message. Coord
		// storage in MISC_*_COORDINATE is the raw bit pattern of a
		// float32 (per `project_movement_coord_frame` memory); decode
		// via Float.intBitsToFloat to get the real world-coord value.
		// Previously this emitted Float.floatToIntBits((float) raw),
		// which double-mangles the value, AND was broadcast to local
		// chat — both removed 2026-05-23.
		PlayerCharacter pc = pl.getCharacter();
		Out.writeln(Out.Info,
			"UseItem: unrecognised object id=" + id
			+ " (fell through portal/chair/door dispatchers) at pos x="
			+ Float.intBitsToFloat(pc.getMisc(
				PlayerCharacter.MISC_X_COORDINATE))
			+ " y="
			+ Float.intBitsToFloat(pc.getMisc(
				PlayerCharacter.MISC_Y_COORDINATE))
			+ " z="
			+ Float.intBitsToFloat(pc.getMisc(
				PlayerCharacter.MISC_Z_COORDINATE)));

		pl.send(new OpenDoor(id, pl));

		// Retail emits the transaction-ack PAIR (a0 02) AFTER the
		// state-change packets. Sequence over the wire:
		//   0x83 0x8f  (commit, pre-state-change)   ← already sent
		//   state-change packets (OpenDoor, etc.)   ← already sent
		//   0xa0 0x02 ×2 (transaction-ack pair)     ← below
		// Task #254 — retail 13B variant (RETRY3 pcap 2026-05-24).
		pl.send(new InteractionAck(true));
		pl.send(new InteractionAck(true));
	}

}
