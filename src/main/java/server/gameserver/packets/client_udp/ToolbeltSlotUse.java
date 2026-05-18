package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.WeaponSlotEquip;
import server.tools.Out;

/**
 * Client-&gt;server toolbelt-slot equip / holster request
 * ({@code 0x03/0x1f/<localId>/0x1f} = {@code SlotUse}). Sent when
 * the player presses a toolbelt key (e.g. number key "1") to draw
 * the weapon in that belt slot, or — pressing the key of the
 * already-drawn slot — to holster it.
 *
 * <h3>Wire format (byte-pinned)</h3>
 *
 * <p>User-captured ground truth: pressing toolbelt slot "1" sent,
 * inside the {@code 0x13} reliable wrapper, exactly:
 *
 * <pre>
 *   03 11 00 | 1f 00 00 1f 00
 * </pre>
 *
 * <ul>
 *   <li>{@code 0x03} reliable wrapper, {@code seq = 0x0011}</li>
 *   <li>op {@code 0x1f} (GamePackets multiplexer)</li>
 *   <li>localId LE16 {@code 0x0000} (the acting player; the server
 *       authoritatively uses the session's own player, not this
 *       field)</li>
 *   <li>tag {@code 0x1f} = {@code SlotUse} ("Equipment slot action"
 *       — {@code docs/PROTOCOL.md} §"0x1F Game Sub-packets")</li>
 *   <li>slot byte {@code 0x00} = toolbelt slot index 0 (key "1",
 *       0-based)</li>
 * </ul>
 *
 * <p>Slot range cross-checked against the client's slot-activate
 * handler {@code FUN_007e67b0} ("SRV activate slot: %i", Ghidra
 * decompile of {@code neocronclient.exe}): it accepts slot
 * {@code 0..0x0b} and rejects {@code &gt; 0x0b}; slots
 * {@code 0..0x0a} are weapon belt slots, {@code 0x0b} is the
 * reload-related special slot. The C→S body carries a single
 * slot-index byte (the client's net-message build call
 * {@code (...)( 0x1f59, 4, &slot )} packs the slot index into the
 * SlotUse class {@code 0x1f59}; the reliable-framed wire form the
 * user captured is the 1-byte slot index).
 *
 * <p>The retail capture
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} (a
 * sit + NPC-dialog scenario) does not exercise a weapon equip, so
 * no retail S→C SlotUse sample exists; the server reply is built on
 * the established per-entity {@code 0x03/0x1f} state-broadcast
 * shape (see {@link WeaponSlotEquip} / {@link SitOnChair}).
 *
 * <h3>Server behaviour</h3>
 *
 * <p>Toggle semantics: pressing the key of the slot whose weapon is
 * already drawn holsters it; pressing a different slot's key (or
 * any slot while holstered) draws that slot's weapon. The new
 * drawn-slot state is recorded on the {@link Player} and the
 * SlotUse is re-broadcast to the whole zone (including the subject)
 * so the local client and peers play the draw / holster animation
 * and render the weapon in-hand.
 *
 * @see WeaponSlotEquip
 * @see server.gameserver.packets.server_udp.SitOnChair
 * @see ExitSeatRequest
 */
public class ToolbeltSlotUse extends GamePacketDecoderUDP {

	public ToolbeltSlotUse(byte[] subPacket) {
		super(subPacket);
	}

	public void execute(Player pl) {
		// Skip [0x03][seq LE2][0x1f][localId LE2][0x1f tag] = 7 bytes,
		// leaving the reader positioned at the slot-index byte.
		skip(7);
		int slot = read();
		if (slot < 0) {
			// Truncated SlotUse (no slot byte) — ignore rather than
			// guess; the user's sample always carries the slot byte.
			Out.writeln(Out.Info,
				"ToolbeltSlotUse: truncated packet, no slot byte");
			return;
		}

		int prev = pl.getActiveWeaponSlot();
		final int broadcastSlot;
		if (prev == slot) {
			// Pressing the already-drawn slot's key holsters it.
			pl.setActiveWeaponSlot(-1);
			broadcastSlot = WeaponSlotEquip.HOLSTER;
			Out.writeln(Out.Info,
				"ToolbeltSlotUse: holster (slot " + slot + ") for "
				+ pl.getName());
		} else {
			// Draw / switch to this slot's weapon.
			pl.setActiveWeaponSlot(slot);
			broadcastSlot = slot;
			Out.writeln(Out.Info,
				"ToolbeltSlotUse: equip toolbelt slot " + slot
				+ " for " + pl.getName());
		}

		Zone z = pl.getZone();
		if (z != null) {
			z.sendPlayerWeaponSlot(pl, broadcastSlot);
		}
	}
}
