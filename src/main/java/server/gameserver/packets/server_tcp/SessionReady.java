package server.gameserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

/**
 * SessionReady ({@code a0 01}) — 2-byte session-state transition packet.
 *
 * <p>Verified retail TCP capture (msn4wolf account, 2026-05-01):
 * the server sends a 2-byte {@code fe 02 00 a0 01} between AuthAck
 * ({@code 83 81}) and CharList ({@code 83 85}). Without this packet,
 * the modern NCE 2.5.x client rejects the CharList silently and stays
 * stuck on the "updating data" screen, sending {@code a0 03} keepalive
 * pings forever.
 *
 * <p>Hypothesis: {@code a0 NN} is a session-state byte:
 * <ul>
 *   <li>{@code a0 01} S→C — "auth complete, sending char list next"</li>
 *   <li>{@code a0 03} C→S — "waiting for char list / state-ready ping"</li>
 * </ul>
 */
public final class SessionReady extends PacketBuilderTCP {
	public SessionReady() {
		super();
		write(0xa0);
		write(0x01);
	}
}
