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
				// Debug: log the first two bytes of every sub-packet so
				// we can see exactly which sub-types the client is
				// sending (0x03 reliable wrapper, 0x0b CPing, 0x20
				// Movement, etc.). Helps identify missing decoders.
				if (subPacket.length >= 1) {
					int t0 = subPacket[0] & 0xFF;
					int t1 = subPacket.length >= 4 ? subPacket[3] & 0xFF : -1;
					server.tools.Out.writeln(server.tools.Out.Info,
						"UDP sub-packet size=" + datasize
						+ " type=0x" + String.format("%02x", t0)
						+ (t0 == 0x03 ? " sub=0x" + String.format("%02x", t1) : ""));
					// Diagnostic: dump the full bytes of 0x03->0x07
					// multipart fragments coming FROM the client. Retail
					// never sends these C→S, so receiving them means our
					// session is in a state retail never enters — knowing
					// the content is required to figure out why.
					if (t0 == 0x03 && t1 == 0x07) {
						StringBuilder hex = new StringBuilder();
						for (byte b : subPacket) hex.append(String.format("%02x", b));
						server.tools.Out.writeln(server.tools.Out.Info,
							"CLIENT_MULTIPART_FRAG (" + subPacket.length + "B): " + hex);
					}
					// Also dump 0x03->0x1f GamePackets (inner opcodes we
					// don't know yet) and 0x03->0x24-like ready triggers
					// so we can correlate size patterns.
					if (t0 == 0x03 && t1 == 0x1f) {
						StringBuilder hex = new StringBuilder();
						int n = Math.min(32, subPacket.length);
						for (int k = 0; k < n; k++) hex.append(String.format("%02x", subPacket[k]));
						if (subPacket.length > n) hex.append("...");
						server.tools.Out.writeln(server.tools.Out.Info,
							"CLIENT_GAMEPKT (" + subPacket.length + "B): " + hex);
					}
				}
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
			return new HandshakeUDP(dp);
		case 0x03:
			return new SyncUDP(dp);
		case 0x08:
			return new AbortSession(dp);
		default:
			pd.reset();
			return pd;
		}
	}

	private static GameServerEvent decodesub13(byte[] subPacket) {
		UnknownClientUDPPacket pd = new UnknownClientUDPPacket(subPacket);
		switch (pd.read()) {
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
		default:
			pd.reset();
			return pd;
		}
	}
}
