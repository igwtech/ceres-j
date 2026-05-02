package server.gameserver.packets.server_udp;

import java.util.LinkedList;

import server.database.items.Item;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP130307;
import server.tools.Out;

public class CharInfo extends PacketBuilderUDP130307 {

	public CharInfo(Player pl) {
		super(pl);
		PlayerCharacter pc = pl.getCharacter();
		//byte[] unknowndata = new byte[] {(byte) 0xa0, (byte) 0x8a,        0x19, (byte) 0xf4, 0x00, 0x00};
		//										short transaction ID					int charid
		write(0x22);
		write(0x02);
		write(0x01);

		newSection(1);
		// CharInfo Section 1 (10 bytes) — fully verified against retail 6 chars.
		// Layout: [fa][3 unknown bytes per-char][UID LE32][01 00][11 00 const].
		// Bytes 1-3 vary per character (Krafteo=24 e1 2f, Drstone=23 40 00, etc.)
		// — likely creation timestamp or a derived hash. Exact semantics TBD.
		// Emit as zeros for now; client appears to read only the UID (bytes 4-7)
		// and the trailing const for HUD purposes.
		write(0xfa);
		write(0); // byte 1 — TBD per-char unknown
		write(0); // byte 2 — TBD per-char unknown
		write(0); // byte 3 — TBD per-char unknown
		writeInt(pc.getMisc(pc.MISC_ID)); // UID LE32 (bytes 4-7) — verified retail position
		writeShort(17); // bytes 8-9 = 11 00 const (LE16 = 17)
		
		
		newSection(2); //pools
		write(4);
		write(4);
		writeShort(pc.getHealth()); //cur health
		writeShort(pc.getMaxHealth()); //max health
		writeShort(pc.getPsi()); //cur psi
		writeShort(pc.getMaxPsi()); //max psi
		writeShort(pc.getStamina()); //cur stamina
		writeShort(pc.getMaxStamina()); //max stamina
		writeShort(255); // 4th pool cur (sentinel "uncapped")
		writeShort(255); // 4th pool max (sentinel "uncapped")
		// HP bar zone markers (verified 2026-05-01 via 6-char differential
		// including Dr.Stone fresh char): HP_max split into 35%/45%/20%
		// for HUD bar rendering (green/yellow/red zones). Damaged-state
		// formula deflates the latter two; full-HP login uses simple ratios.
		int hpMax = pc.getMaxHealth();
		writeShort(hpMax * 7 / 20);  // green-zone boundary (35%)
		writeShort(hpMax * 9 / 20);  // yellow-zone boundary (45%)
		writeShort(hpMax * 4 / 20);  // red-zone boundary (20%)
		write(pc.getSynaptic()); // synaptic impairment cap (= 100 normally)
		write(128); // unknown constant — possibly runspeed cap
		write(0);
		write(0);
		
		newSection(3); //skills (main skill table)
		// CharInfo Section 3 — Main skill table (60 bytes total).
		// Format verified 2026-05-01 via 5-char F1 differential analysis:
		// 11-byte header (06 09 + 8 zeros + 01) + 5 entries × 9 bytes
		// (order: STR, DEX, CON, INT, PSI per PlayerCharacter.SKILLS)
		// + 4-byte trailer (f0 03 00 00).
		// Per entry: [level u8][SP_available u16le][XP u32le][rate u8][max u8].
		// Cross-validated XP at +3 offset matched all 5 retail chars.
		write(0x06);
		write(0x09);
		write(new byte[]{0, 0, 0, 0, 0, 0, 0, 0});
		write(0x01);
		for (int i = 1; i <= 5; i++) {
			write(pc.getSkillLVL(i));
			writeShort(pc.getSkillPts(i));
			writeInt(pc.getSkillXP(i));
			write(pc.getSkillRate(i));
			write(pc.getSkillMax(i));
		}
		write(0xf0); // Woc lvl
		write(0x03); // woc skill
		write(0x0);
		write(0x0);
		
		newSection(4); //subskills
		// CharInfo Section 4 — Subskill table (94 bytes total).
		// Format fully verified 2026-05-01 via 5-char F1 differential
		// analysis: 4-byte header (`2e 02 00 01`) + 45 entries × (val, rank).
		// Positions match SUBSKILL_* constants in PlayerCharacter; slots
		// without a constant (4, 5, 7, 8, 9, 18, 19, 28, 29, 38, 39, 45)
		// are NC1-era subskills retained for protocol compatibility.
		// Drives HUD pool maxes (HLT/STA/PSI) via client tick functions
		// FUN_007e87d0/8930/8a20 → FullCharsysInfo recompute → HUD events.
		write(0x2e);
		write(0x02);
		write(0x00);
		write(0x01);
		for (int slot = 1; slot <= 45; slot++) {
			write(pc.getSubskillLVL(slot));
			write(pc.getSubskillPtsPerLvl(slot));
		}

		newSection(5); //inventory
		
		int num = pc.getContainer(PlayerCharacter.PLAYERCONTAINER_F2).getNumberofItems();
		writeShort(num);
		
		LinkedList<Item> list = pc.getContainer(PlayerCharacter.PLAYERCONTAINER_F2).getallItems();
		
		for(int i = 0; i < num; i++){
			Item it = list.get(i);
			write(it.getItemInfoPacketData(Item.PACKET_CHARINFOF2));
		}
		
		
		
		newSection(6); //QB/Processor/Implants/Armour
		num = pc.getContainer(PlayerCharacter.PLAYERCONTAINER_QB).getNumberofItems();
		write(num);
		
		list = pc.getContainer(PlayerCharacter.PLAYERCONTAINER_QB).getallItems();		
		for(int i = 0; i < num; i++){
			Item it = list.get(i);
			write(it.getItemInfoPacketData(Item.PACKET_CHARINFOQB));
		}
		

		newSection(7); // unknown — single 0x00 byte (retail confirmed)
		write(0);

		newSection(8); // Wallet/GR/profile — total 67 bytes (retail size)
		// Original 39-byte layout below; keep its byte offsets intact for
		// existing tests, then pad to 67 bytes total. Retail Section 8 is
		// 67 bytes (decoded from FULL_PCAP_TRACE 2026-04-26 — Krafteo char,
		// cash=527,652 → HUD displays correctly). Hypothesis: client's
		// section 8 parser may bail out if Section 8 size != 67, dropping
		// the cash value silently.
		write(0x0a);
		writeInt(pc.getCash());
		// Retail bytes immediately after cash: 05 00 04 01 00 — replacing
		// the legacy 00 00 04 04 04 because the client's Section 8 parser
		// may use these bytes as a struct-validity discriminator. Without
		// this match, the cash field gets ignored at login (HUD shows 0).
		write(new byte[] {
				(byte)0x05, (byte)0x00,    // was 00 00 (number of grs)
				(byte)0x04, (byte)0x01, (byte)0x00,    // was 04 04 04
				(byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00});
		writeShort(pl.getTransactionID());
		write(new byte[]{
				0x00, 0x00, 0x00, 0x00
				, 0x00, 0x00, 0x00
				, 0x00}); // Epic status
		write(pc.getMisc(PlayerCharacter.MISC_CLASS)*2); // class*2 + gender stuff... // seems to have no effect
		write(0);
		write(pc.getMisc(PlayerCharacter.TEXTURE_HEAD)); // seems to have no effect
		write(pc.getMisc(PlayerCharacter.TEXTURE_TORSO)); // seems to have no effect
		write(pc.getMisc(PlayerCharacter.TEXTURE_LEG)); // seems to have no effect
		write(pc.getRank());//rank
		writeInt(100002);//App
		write(new byte[]{0x01, 0x00, 0x00, 0x00, 0x00});
		// Pad to 67 bytes (test 28 bytes of trailing 0x00 — retail layout
		// undecoded but its presence may be required for the section 8
		// parser to commit cash to HUD memory).
		write(new byte[28]);

		newSection(9); // Section 9 - Faction Sympathies
		writeShort(21); //21 fractions
		write(pc.getMisc(PlayerCharacter.MISC_FACTION)); //current fraction;
		write(0);
		write(4);
		// 20 named faction sympathy floats (sl, ca, dre, nxt, tt, bt, pp, tu,
		// ts, bd, cs, cm, doy, ab, fa, tg, rl, rm, insects, monster) backed
		// by PlayerCharacter.factionSympathies[0..19].
		for (int i = 0; i < 20; i++) {
			writeFloat(pc.getFactionSympathy(i));
		}
		writeFloat(pc.getFactionSympathy(20)); //lowsl (default 0.0f)
		writeFloat(10000); // sl padding — literal (out of scope)
		writeFloat(0); // unknown padding — literal (out of scope)
		write(pc.getMisc(PlayerCharacter.MISC_FACTION)); // current faction
		
		newSection(0x0a); // Section 10 — clan data (empty / 0 or 10 bytes per retail; conditional)

		newSection(0x0b); // Section 11 — conditional list (cabinet contents on retail; 1-38B)
		write(0);

		newSection(0x0c); // Section 12 — NPC reps / faction kill list (1-1089B on retail)
		write(0); //number of items — gogu placeholder until kill log implemented

		newSection(0x0d); // Section 13 — UID signature footer (= first 8 bytes of S1)
		// Verified retail: S13 mirrors S1[0..7] exactly. Same per-char unknown
		// bytes 1-3 are TBD; emit zeros.
		write(0xfa);
		write(0);
		write(0);
		write(0);
		writeInt(pc.getMisc(pc.MISC_ID)); // UID LE32 — same as S1
	}
}
