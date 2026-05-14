package server.gameserver.packets;

import java.net.DatagramPacket;

import server.gameserver.Player;
import server.gameserver.packets.client_udp.*;
import server.interfaces.GameServerEvent;

public final class GamePacketReaderUDP {

	public static void readPacket(DatagramPacket dp, Player pl) {
		GamePacketDecoderUDP pd = new UnknownClientUDPPacket(dp);
		if (pd.read() == 0x13) {
			// Outer 0x13 header: [counter LE][counter+sessionkey LE] = 4 bytes.
			pd.skip(4);
			// Sub-packet size is 2-byte LE (retail format). The legacy
			// emulator read a single byte here; the modern client always
			// sends two bytes (see PacketBuilderUDP13.java and the 2-byte
			// LE decision documented in docs/retail_burst_analysis.md).
			// Reading one byte here misaligns the parser on the very
			// first sub-packet and routes every subsequent event to
			// UnknownClientUDPPacket, stalling the zone sync state.
			int datasize;
			while (pd.available() >= 2 && (datasize = pd.readShort()) > 0) {
				if (datasize > pd.available()) {
					// Stated size exceeds remaining bytes — malformed
					// packet. Drop the rest rather than read garbage.
					break;
				}
				byte[] subPacket = new byte[datasize];
				pd.read(subPacket);
				logSubPacketDiagnostic(subPacket, datasize);
				// ACK every reliable (0x03) sub-packet the client sends.
				// The client's reliable delivery layer tracks unACKed
				// reliables and aborts after ~15 s if none are ACKed.
				// Confirmed by strace: client sends 153 unACKed 0x3d
				// GamePackets (retail: 27) then 0x08 ABORT exactly
				// 15 s after last activity. ACK format: a 0x13 datagram
				// with one [0x01][seq_lo][seq_hi] sub-packet.
				if (subPacket.length >= 3 && (subPacket[0] & 0xFF) == 0x03) {
					int ackSeq = (subPacket[1] & 0xFF) | ((subPacket[2] & 0xFF) << 8);
					server.networktools.PacketBuilderUDP13 ack =
						new server.networktools.PacketBuilderUDP13(pl);
					ack.write(0x01);
					ack.write(ackSeq & 0xFF);
					ack.write((ackSeq >> 8) & 0xFF);
					pl.send(ack);
					// HISTORICAL NOTE: we used to also emit
					// ServerReliableAck (0x03/0x09) here. Retail
					// captures show 0x03/0x09 ~3×/session — NOT on
					// every reliable. Because 0x03/0x09 ships via
					// PacketBuilderUDP1303 (a reliable wrapper), the
					// client treated each one as a fresh reliable
					// needing its own ack — creating an ack-of-ack
					// loop that exhausted the seq counter and the
					// retransmit ring on world entry, crashing the
					// client mid-init. Disabled until the precise
					// trigger is identified from a retail pcap. See
					// reliable_ack_08_decoded.md.
				}
				server.interfaces.GameServerEvent ev = decodesub13(subPacket);
				if (ev != null) {
					pl.addEvent(ev);
				}
			}
		} else {
			pl.addEvent(decode(dp, pl));
		}
	}

	private static GameServerEvent decode(DatagramPacket dp, Player pl) {
		GamePacketDecoderUDP pd = new UnknownClientUDPPacket(dp);
		switch (pd.read()) {
		case 0x01:
			// Two distinct 0x01 wire shapes:
			//   ≥10 B: UDP 3-way handshake (per HandshakeUDP, which
			//          skips 9 bytes and reads byte 9 as interfaceId)
			//   3 B  : post-handshake reliable-ACK request shaped
			//          [0x01][seq LE2] (e.g. retail's 010100, 010200,
			//          ..., 011100 bursts seen across all 17 captures
			//          — see docs/protocol/packets/udp_c2s_01.md).
			// Routing every 0x01 to HandshakeUDP regardless of size
			// caused the server to fire two spurious UDPAlive replies
			// for every ACK request. Discriminate by length.
			if (dp.getLength() >= 10) {
				return new HandshakeUDP(dp);
			}
			byte[] ackBody = new byte[dp.getLength()];
			System.arraycopy(dp.getData(), dp.getOffset(),
					ackBody, 0, ackBody.length);
			return new server.gameserver.packets.client_udp
					.ReliableAckSubPacket(ackBody);
		case 0x03:
			return new SyncUDP(dp);
		case 0x08:
			return new AbortSession(dp);
		default:
			pd.reset();
			return pd;
		}
	}

	/**
	 * Per-sub-packet diagnostic dump. Gated behind {@code subPackets}
	 * because UDP gameplay traffic produces ~90 of these per player
	 * per second — the string-format cost alone is non-trivial on
	 * the hot path. Enable with {@code Debug = subPackets} in
	 * ceres.cfg when chasing a missing decoder. Package-private so
	 * unit tests can drive it directly without standing up a full
	 * Player + DatagramPacket.
	 */
	static void logSubPacketDiagnostic(byte[] subPacket, int datasize) {
		if (!server.tools.Debug.isSubPacketsEnabled()) return;
		if (subPacket == null || subPacket.length < 1) return;
		int t0 = subPacket[0] & 0xFF;
		int t1 = subPacket.length >= 4 ? subPacket[3] & 0xFF : -1;
		server.tools.Debug.subPacket(
			"size=" + datasize
			+ " type=0x" + String.format("%02x", t0)
			+ (t0 == 0x03 ? " sub=0x" + String.format("%02x", t1) : ""));
		// 0x03->0x07 multipart C→S: retail never sends these, so a
		// hit means our session is in a state retail never enters.
		// Full hex helps diagnose.
		if (t0 == 0x03 && t1 == 0x07) {
			StringBuilder hex = new StringBuilder();
			for (byte b : subPacket) hex.append(String.format("%02x", b));
			server.tools.Debug.subPacket(
				"CLIENT_MULTIPART_FRAG (" + subPacket.length + "B): " + hex);
		}
		// 0x03->0x1f GamePackets: dump leading bytes so missing
		// inner-opcode routes can be spotted.
		if (t0 == 0x03 && t1 == 0x1f) {
			StringBuilder hex = new StringBuilder();
			int n = Math.min(32, subPacket.length);
			for (int k = 0; k < n; k++) hex.append(String.format("%02x", subPacket[k]));
			if (subPacket.length > n) hex.append("...");
			server.tools.Debug.subPacket(
				"CLIENT_GAMEPKT (" + subPacket.length + "B): " + hex);
		}
	}

	private static GameServerEvent decodesub13(byte[] subPacket) {
		UnknownClientUDPPacket pd = new UnknownClientUDPPacket(subPacket);
		switch (pd.read()) {
		case 0x02:
			// Client ACK-channel for server-pushed reliables. Layout
			// mirrors 0x03 reliable: [02][seq2][sub-opcode]...
			// During normal gameplay this carries 0x1f gamedata with
			// tag 0x3d sub-tag 0x11 (in-flight heartbeat) at ~90 Hz.
			// We don't model client-side ACK state yet — server's
			// outgoing reliables are best-effort, no retransmit
			// needed. So just fall through to the same sub-opcode
			// switch as 0x03 to recognise the inner payload.
		case 0x03:
			pd.skip(2); // sequence counter (ACKed in readPacket)
			switch (pd.read()) {
			case 0x01:
				//resend packets
				// TODO implement this
				return null;
			case 0x0d:
				// Client sync request: 0x03→0x0d TimeSync. The client
				// sends this from state 3/6 every 8 seconds and expects
				// a 0x03→0x0d TimeSync reply. After 5 failed retries
				// (no reply) the client aborts with "Synchronisation to
				// WorldServer failed." Responding with TimeSync
				// advances the state machine from state 3/6 to state 4
				// (in-world). See FUN_0055b6f0 case 3 in the Ghidra
				// decompile (docs/state_string_refs.txt:575-605).
				//
				// Inner payload after 0x0d: 4-byte client time (LE).
				// pd has already consumed 0x03 + 2-byte seq + 0x0d.
				return new ReliableTimeSyncRequest(subPacket);
			case 0x1f:
				pd.skip(2);
				switch (pd.read()) {
				case 0x17:
					return new UseItem(subPacket);
				case 0x1b:
					return new LocalChat(subPacket);
				case 0x1e:
					return new InventoryMove(subPacket);
				case 0x25:{
					switch(pd.read()){
					case 0x14:
						return new InsideF2InvMove(subPacket);
					case 0x17:
						return new InventoryCombineItems(subPacket);
					default:
						pd.reset();
						return pd;
					}
				}
				case 0x3b: {
					// Cross-channel chat (whisper / team / clan / buddy).
					// Routed through the SubtagRouter so the chat
					// subsystem owns its own decoder factory. Falls
					// back to the legacy GlobalChat decoder if no
					// route is registered (e.g. unit tests that don't
					// boot GameServer).
					GameServerEvent routed = SubtagRouter.dispatch(
							subPacket, 0x03, 0x1f, 0x3b, -1);
					if (routed != null) return routed;
					return new GlobalChat(subPacket);
				}
				case 0x3d:
					// In-flight client heartbeat / ACK burst for
					// server-pushed reliables. 88K observations across
					// the corpus, ~90/sec during gameplay. Sub-tags
					// observed: 0x11 (vast majority — fixed body
					// `00 00 3d 11 00 00 00 00`), 0x32 (occasional —
					// status snapshot), 0x0c/0x0d/0x03 (rare). Server
					// has nothing to do here — just recognise so the
					// log doesn't drown in "Unknown UDP13 Packet"
					// lines. Reliable-layer ACK still fires at the
					// outer switch above, so the client's own retry
					// timer stays satisfied.
					return null;
				case 0x4c:
					return new ChangedChannels(subPacket);
				default:
					pd.reset();
					return pd;
				}
			case 0x22:
				switch (pd.read()) {
				case 0x03:
					return new Zoning2(subPacket);
//				case 0x06:
//					AddtoChat.action(subPacket, this);
//					break;
				case 0x06:{
					pd.skip(1);
					switch(pd.read()){
					case 0x00:
						return new RequestPlayerNamebyPlayerId(subPacket);
					}
				}
				case 0x0d:
					return new Zoning1(subPacket);
				default:
					pd.reset();
					return pd;
				}
			case 0x24:
				// Client's "ready for world state" trigger. Previously
				// responded with a full zone-population re-burst
				// (ReadyForWorldState) but that re-sends InfoResponse +
				// ChatList + TimeSync + PlayerInfo which appears to
				// push the client's state machine back to state 1→2
				// (joining session), triggering a 15 s timeout. The
				// WorldEntryEvent initial burst already sends everything
				// the client needs. Treat this as a no-op.
				return null;
			case 0x27:
				return new RequestInfoAboutWordlID(subPacket);
			case 0x2d: {
				// Drone-control (and future mob-state C->S, if any).
				// Routed through SubtagRouter so the npc subsystem
				// owns its own decoder factory.
				GameServerEvent routed = SubtagRouter.dispatch(
						subPacket, 0x03, 0x2d, -1, -1);
				if (routed != null) return routed;
				pd.reset();
				return pd;
			}
			case 0x31:
				return new RequestShortPlayerInfo(subPacket);
			default:
				pd.reset();
				return pd;
			}
		case 0x0b:
			return new CPing(subPacket);
		case 0x0c:
			return new GetTimeSync(subPacket);
		case 0x20:
			return new Movement(subPacket);
		case 0x2a:
			return new RequestPositionUpdate(subPacket);
		// ─── Catalog-driven recognition (task #137) ──────────────────
		// Sub-packet top-bytes that retail captures show the *server*
		// emits but our parser previously logged as "Unknown UDP13
		// Packet". Recognising here keeps the operator log clean when
		// retail traffic is replayed against the decoder (e.g. via
		// the ReplayHarness). See docs/protocol/packets/udp_s2c_*.md
		// for byte-level evidence and the per-opcode rationale.
		case 0x00:
			// 12-of-17 captures, 147 hits. Heterogeneous; well-attested.
			return new Sub0x00Recognized(subPacket);
		case 0x01:
			// 3-of-17 captures, 13 hits. 3-byte reliable-ACK frame.
			return new ReliableAckSubPacket(subPacket);
		case 0x06:
			// 4-of-17 captures, 6 hits. 1-byte singleton.
			return new Sub0x06Recognized(subPacket);
		case 0x07:
			// 1-of-17 captures, 4 hits. 10-byte reliable-shaped wrapper.
			return new Sub0x07Recognized(subPacket);
		case 0x0d:
			// 2-of-17 captures, 4 hits. 10-byte reliable-shaped wrapper.
			return new Sub0x0DRecognized(subPacket);
		case 0x0f:
			// 5-of-17 captures, 11 hits. 10-byte reliable-shaped wrapper.
			return new Sub0x0FRecognized(subPacket);
		case 0x11:
			// 2-of-17 captures, 2 hits. 10-byte reliable-shaped wrapper.
			return new Sub0x11Recognized(subPacket);
		case 0x31:
			// 1-of-17 captures, 1 hit. 1-byte singleton.
			return new Sub0x31Recognized(subPacket);
		default:
			pd.reset();
			return pd;
		}
	}
}
