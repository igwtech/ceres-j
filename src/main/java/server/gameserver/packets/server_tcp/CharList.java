package server.gameserver.packets.server_tcp;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.database.playerCharacters.PlayerCharacterManager;
import server.networktools.PacketBuilderTCP;
import server.tools.Config;

public class CharList extends PacketBuilderTCP {

	/**
	 * Per-character record size. Client build 862 (installed 2026-10-04)
	 * shows every slot as "create new" unless this is 73: the current
	 * retail CharList (harness run retail_b862_1) carries header
	 * {@code 8385 0600 0400 4900} and 73-byte records = the old 41-byte
	 * layout (id, models, textures, location, name length, ...) followed by
	 * 32 new bytes, then the name. The 32 bytes are identical in all four
	 * retail records, the empty slot included
	 * ({@code 00 "NVAXXE1" "74. 74." 9bba2aa29d9b 00×11}), i.e. a reused
	 * server buffer rather than per-character data, so Ceres-J sends zeros.
	 * Builds up to 823 used 0x29 (41).
	 */
	static final int RECORD_SIZE = 0x49;

	/** Bytes appended to the legacy 41-byte record (see RECORD_SIZE). */
	static final int RECORD_EXTENSION = RECORD_SIZE - 41;

	static final byte[] CHARDUMMY = {
			(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
			0, 0, 0, 0, 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0, 
			0, 0, 0, 0, 0, 1, 1, 1,
			1, 1, 0x21, 0, 0, 0, 0, 1,
			0, 0, 0, 0, 0};
	
	public CharList(Account account) {
		write(0x83);// packet id
		write(0x85);
		// Bytes [2..3] are session-state, not a constant. Catalog
		// evidence (27 retail samples across 17 captures): 17
		// distinct values, with `00 00` being the most common
		// (5/27 = 18.5 %). The previous `fe 02` claim came from a
		// single 2026-05-01 pcap and was over-generalized. The
		// client tolerates any value in this slot — successful
		// retail logins exist with all 17 observed bytes — but
		// matching the modal value keeps Ceres-J pcaps closest
		// to the catalog's representative sample.
		write(0x00);
		write(0x00);
		// Slot count is driven by the CharsPerAccount config (was a
		// hardcoded 4). Verified against retail: this field is the
		// fixed slot-array size, not the live character count — empty
		// slots are CHARDUMMY-filled. Clamp to [1,4] for safety.
		int slotCount = 4;
		try {
			slotCount = Integer.parseInt(Config.getProperty("CharsPerAccount"));
		} catch (NumberFormatException | NullPointerException ignored) {
		}
		if (slotCount < 1) slotCount = 1;
		// HARD CAP 4: the retail client crashes during login on a
		// CharList with char_count > 4 (verified 2026-06-06 — count=5
		// killed the client before char-select; count=4 logged in
		// fine). Account holds only 4 char ids and retail never sends
		// more. Never emit > 4.
		if (slotCount > 4) slotCount = 4;
		writeShort(slotCount); // number of chars (slot-array size)
		writeShort(RECORD_SIZE);

		for (int i = 0; i < slotCount; i++) {
			PlayerCharacter pc = PlayerCharacterManager.getCharacter(account.getChar(i));
			if (pc == null) {
				write(CHARDUMMY);
				write(new byte[RECORD_EXTENSION]);
			} else {
				byte[] name = pc.getName().getBytes();
				
				writeInt(pc.getMisc(PlayerCharacter.MISC_ID));
				writeShort(pc.getModel(PlayerCharacter.MODEL_HEAD));
				writeShort(pc.getModel(PlayerCharacter.MODEL_TORSO));
				writeShort(pc.getModel(PlayerCharacter.MODEL_LEG));
				writeShort(pc.getModel(PlayerCharacter.MODEL_HAIR));
				writeShort(pc.getModel(PlayerCharacter.MODEL_BEARD));
				writeShort(pc.getTexture(PlayerCharacter.TEXTURE_HEAD));
				writeShort(pc.getTexture(PlayerCharacter.TEXTURE_TORSO));
				writeShort(pc.getTexture(PlayerCharacter.TEXTURE_LEG));
				writeInt(pc.getMisc(PlayerCharacter.MISC_LOCATION));
				write(name.length+1);
				write(1); //unknown
				write(1);
				write(1);
				write(1);
				write(1);
				writeInt(pc.getMisc(PlayerCharacter.MISC_PROFESSION));
				write(0);//unknown
				write(pc.getMisc(PlayerCharacter.MISC_CLASS) / 2);
				write(pc.getMisc(PlayerCharacter.MISC_CLASS) % 2);
				writeInt(0);//unknown
				write(new byte[RECORD_EXTENSION]);
				write(name);
				write(0);
			}
		}
	}
}
