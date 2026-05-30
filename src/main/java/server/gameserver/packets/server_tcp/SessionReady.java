package server.gameserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

/**
 * SessionReady ({@code a0 01}) — session-state sync packet.
 *
 * <p>Sent by the server in two contexts:
 * <ol>
 *   <li><b>Response to client's C→S {@code 0xa0/0x03}</b> ready probe.
 *       Modern retail clients send this immediately after receiving
 *       AuthAck on Stage 2 (GameLobby) and won't send AuthB until they
 *       receive the 0xa0/0x01 reply. See {@code ReadyProbe.java}.
 *   <li><b>Proactively after AuthB (Stage 3)</b> — emitted by
 *       {@code AuthB.execute} right before {@code UDPServerData} +
 *       {@code Location} to advance the post-AuthB world-entry burst
 *       on legacy clients that don't drive the 0xa0/0x03 exchange.
 * </ol>
 *
 * <p><b>Body layout</b> (live-verified from Braine real-client capture
 * 2026-05-22 against retail 157.90.195.74):
 * <pre>
 *   [0..1]   0xa0 0x01    opcode
 *   [2..5]   15 00 00 00  LE32 = 21 (session-state constant)
 *   [6..9]   00 00 80 3f  float32 LE = 1.0 (version-ish constant)
 * </pre>
 *
 * <p>Older retail captures (April 2026) showed only the 2-byte
 * {@code fe 02 00 a0 01} variant. Both forms are accepted by the
 * client; the 10-byte form is what current retail (May 2026) sends.
 * Ceres-J emits the 10-byte form for byte-faithfulness.
 *
 * <p><b>Historical hypothesis</b> (superseded by Braine capture):
 * the comment used to suggest {@code a0 NN} was a free-form
 * "session state" pair. The May 2026 capture revealed it's a fixed
 * S→C response to a fixed C→S ready-probe {@code 0xa0/0x03}.
 */
public final class SessionReady extends PacketBuilderTCP {
	/** The 8-byte payload that follows the {@code a0 01} opcode in the
	 *  modern retail variant. Live-verified constant 2026-05-22. */
	public static final byte[] RETAIL_BODY_PAYLOAD_2_5 = new byte[] {
		0x15, 0x00, 0x00, 0x00,    // LE32 = 21
		0x00, 0x00, (byte) 0x80, 0x3f,  // float32 LE = 1.0
	};

	public SessionReady() {
		super();
		write(0xa0);
		write(0x01);
		for (byte b : RETAIL_BODY_PAYLOAD_2_5) {
			write(b & 0xff);
		}
	}
}
