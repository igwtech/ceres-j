package server.gameserver.packets.server_udp;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP130307;

/**
 * CharInfo message with header {@code 0x22 0x01} — distinct from {@link
 * CharInfo} which starts with {@code 0x22 0x02 0x01} and is actually a
 * <b>CharsysInfo</b> payload from the client's perspective.
 *
 * <p>The retail client's sync state machine in
 * {@code FUN_0055c270} (WorldClient.cpp) dispatches on byte[1] of the
 * incoming gamedata payload:
 * <ul>
 *   <li>{@code byte[1] == 0x01} → "CharInfo received" → sets sync bit 0</li>
 *   <li>{@code byte[1] == 0x02} → "CharsysInfo received" → sets sync bit 1</li>
 * </ul>
 *
 * <p>The existing {@link CharInfo} class (misnamed — starts with
 * {@code 0x22 0x02 0x01}) only sets bit 1. Without bit 0 the client's
 * post-handshake sync never completes, the state machine stays in state 2
 * and times out after 15 seconds with "WORLDCLIENT : Connection to
 * worldserver failed". This packet exists to set sync bit 0 by sending a
 * gamedata chunk whose payload starts with {@code 0x22 0x01}.
 *
 * <p>The handler at {@code FUN_0055c270} reads data via
 * {@code vtable[8](0xa7, 0x45, buf + 3)} — a 0x45 (69)-byte block starting
 * at offset 3 — so the minimum payload is around 72 bytes. Content of that
 * block is currently unknown; we send zeros, which satisfy the length
 * constraint and unblock the bit-0 set.
 */
public class CharInfoV1 extends PacketBuilderUDP130307 {

	public CharInfoV1(Player pl) {
		super(pl);
		PlayerCharacter pc = pl.getCharacter();

		// Header: 0x22 0x01 — routes the client's dispatcher to the
		// "CharInfo received" branch which sets sync bit 0.
		write(0x22);
		write(0x01);

		// Minimum payload: 72 bytes. The client reads buf+3 with size 0x45
		// (69 bytes), so we need bytes 3..71 inclusive. Bytes 0-2 are the
		// header 0x22 0x01 + one more byte. Fill the rest with the
		// character id so the values aren't all zero (some callers check
		// for non-zero character state on bit 0 receipt).
		write(0x01); // third header byte (matches the 0x22 0x02 0x01 pattern for parallelism)
		writeInt(pc.getMisc(PlayerCharacter.MISC_ID)); // byte 3-6: char id
		// 65 more bytes (total 72) of zero — the client stores this blob
		// but its exact content isn't parsed in this state, only the
		// length is checked.
		for (int i = 0; i < 65; i++) {
			write(0);
		}
	}
}
