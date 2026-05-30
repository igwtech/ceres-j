package server.gameserver.packets.client_tcp;

import server.gameserver.GameServerTCPConnection;
import server.gameserver.packets.GamePacketDecoderTCP;
import server.gameserver.packets.server_tcp.SessionReady;

/**
 * ReadyProbe ({@code a0 03}) — modern NC2 client's "ready for session
 * advance" ping, sent between AuthAck and AuthB on the GameLobby
 * (Stage 2) connection.
 *
 * <p><b>Live-verified 2026-05-22</b> from Braine real-client capture
 * against retail 157.90.195.74: after receiving AuthAck the client
 * sends this 2-byte body ({@code a0 03}, no payload) and waits ~170 ms
 * for the server's {@code 0xa0/0x01} ({@link SessionReady}) reply
 * before it sends the GetCharList AuthB. Without our reply the client
 * stays stuck on the "updating data" screen.
 *
 * <p>This is the current-retail replacement for the older
 * {@code 0x87/0x37 → 0x87/0x3a} GetGamedata sub-exchange seen in
 * 2026-04 captures (AUGUSTO and similar). Both flows are now supported
 * by Ceres-J: legacy clients still use {@code GetGamedata.execute};
 * modern clients drive the {@code ReadyProbe} path.
 *
 * <p><b>Wire body</b>:
 * <pre>
 *   [0..1] a0 03           opcode (no payload)
 * </pre>
 *
 * <p>Server reply: see {@link SessionReady} (10-byte body with the
 * {@code 15 00 00 00 00 00 80 3f} constant payload).
 */
public final class ReadyProbe extends GamePacketDecoderTCP {

	public ReadyProbe(byte[] arg0) {
		super(arg0);
	}

	@Override
	public void execute(GameServerTCPConnection tcp) {
		// No body to decode — just send the SessionReady reply.
		tcp.send(new SessionReady());
	}
}
