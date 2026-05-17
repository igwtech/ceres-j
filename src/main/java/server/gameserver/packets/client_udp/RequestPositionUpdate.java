package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.CharInfo;
import server.gameserver.packets.server_udp.InfoResponse;
import server.gameserver.packets.server_udp.PositionUpdate;
import server.tools.Out;

/**
 * Handles the C&rarr;S {@code 0x13 -> 0x2a} RequestPos packet &mdash;
 * the client asking the server to re-deliver its own state (a
 * resync trigger).
 *
 * <h2>Wire layout (byte-pinned from 98 retail C&rarr;S observations,
 * 17/17 retail captures &mdash; task #169)</h2>
 *
 * <pre>
 * [0]       0x2a                  sub-opcode (all 98 samples)
 * [1..4]    character_uid  LE32   session-stable per character
 * [5..15]   request_token  11 B   opaque request identifier
 *                                  (only present in the 16-byte form)
 * </pre>
 *
 * <p>Two valid body lengths were observed:
 * <ul>
 *   <li><b>5 bytes</b> &mdash; header only ({@code 0x2a} + UID, no
 *       token). 11 of 98 samples (e.g. {@code 2ab0a20000},
 *       {@code 2abd7e0100}, {@code 2a1a7f0100}).</li>
 *   <li><b>16 bytes</b> &mdash; header + 11-byte request token. 86
 *       of 98 samples (e.g.
 *       {@code 2a1a7f0100fce02ddbff6dd90eb90a00}).</li>
 * </ul>
 *
 * <p>The {@code character_uid} is session-stable: a given character
 * always emits the same UID across every one of its captures
 * (0x00017f1a in 6 caps, 0x0000a2b0 in 5 caps, 0x00017ebd in 9
 * caps). It identifies the character making the request.
 *
 * <p>The 11-byte {@code request_token} is opaque and recurs
 * <i>verbatim across unrelated sessions and different characters</i>
 * (9 distinct tokens appear with &ge;2 different UIDs across
 * 2&ndash;5 captures each). This proves the token is <b>not</b>
 * client/session state &mdash; it is an enumerated request
 * identifier drawn from a fixed client-side set. The exact semantic
 * (which response variant each token requests) is not resolvable
 * from these captures because the token bytes are an opaque hash;
 * see {@code docs/protocol/packets/udp_c2s_2a.md} "Open questions".
 *
 * <p>One outlier &mdash; a single 3-byte {@code 2a431f} in
 * CREATION_LEVELING_LONG &mdash; is a sub-packet framing artifact
 * ({@code 0x1f} is the next packet's opcode bleeding in on a length
 * desync), not a real 3-byte variant. The decoder defensively
 * ignores bodies that are neither 5 nor 16 bytes (uid = -1,
 * token = null).
 */
public class RequestPositionUpdate extends GamePacketDecoderUDP  {

	/** -1 when the body was too short to carry a UID. */
	private final long characterUid;
	/** {@code null} when the 5-byte header-only form was sent. */
	private final byte[] requestToken;

	public RequestPositionUpdate(byte[] subPacket) {
		super(subPacket);

		long uid = -1L;
		byte[] token = null;

		// [0] is 0x2a. This buffer is a fresh view that still starts
		// at 0x2a — skip it so offsets match the byte table above.
		skip(1);

		// Both the 5-byte and 16-byte forms carry the UID at [1..4].
		// The 3-byte framing artifact (2a 43 1f) falls through with
		// uid = -1 and no token.
		if (subPacket.length >= 5) {
			uid = ((long) readInt()) & 0xFFFFFFFFL;
		}
		// The 16-byte form appends an 11-byte opaque request token.
		if (subPacket.length >= 16) {
			token = new byte[11];
			int n = read(token);
			if (n != 11) token = null;
		}

		this.characterUid = uid;
		this.requestToken = token;
	}

	/** Character UID from body[1..4] (LE32), or {@code -1} if the
	 *  body was too short to contain one. */
	public long getCharacterUid() {
		return characterUid;
	}

	/** The 11-byte opaque request token from body[5..15], or
	 *  {@code null} for the 5-byte header-only form. */
	public byte[] getRequestToken() {
		return requestToken == null ? null : requestToken.clone();
	}

	public void execute(Player pl) {
		StringBuilder sb = new StringBuilder("RequestPos uid=0x")
				.append(Long.toHexString(characterUid));
		if (requestToken != null) {
			sb.append(" token=");
			for (byte b : requestToken)
				sb.append(String.format("%02x", b & 0xFF));
		} else {
			sb.append(" (header-only)");
		}
		Out.writeln(Out.Info, sb.toString());

		if (pl == null) return;

		pl.send(new PositionUpdate(pl));
		//TODO this is just a workaround
		pl.send(new CharInfo(pl));
		// Retail emits a 7-byte 0x03/0x23 zoneInfo
		// {@code [20 00 ?? 00 00 00]} after PositionUpdate +
		// CharInfo on RequestPositionUpdate. Verified 2026-05-09
		// against NORMAN step 9: retail's S→C queue pairs Ceres-J's
		// PositionUpdate with retail's first emit, then expects
		// 0x03/0x23 zoneInfo next. body[2] is session/zone state
		// (0x10/0x01/0x84/0x00 across captures) — pcap-replay
		// harness masks that byte.
		pl.send(InfoResponse.zoneInfo(pl));
	}
}
