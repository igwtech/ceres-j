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

		newSection(8); // Wallet/GR/profile — 39 bytes (retail size)
		// Verified 2026-05-10 against retail Section 8 from 6 captures
		// (CREATION, DRSTONE3, DRSTONE4, DRSTONE×2, RETAIL_VEHICLE_DRONE):
		// all produce a 39-byte body, NOT 67 as previously hypothesized.
		// The earlier "padding to 67B for HUD CASH activation" theory
		// was wrong — retail's Section 8 is 39B and the HUD activates.
		// Bytes 7..9 are CONSTANT `04 04 04` across all 6 captures —
		// reverting the earlier `05 00 04 01 00` "fix" which matched
		// NO retail capture (task #138).
		//
		// Layout (39 bytes):
		//   [0]      0x0a               CONSTANT cash marker
		//   [1..4]   cash LE32
		//   [5..6]   00 00              CONSTANT
		//   [7..9]   04 04 04           CONSTANT
		//   [10..13] 00 00 00 00        CONSTANT
		//   [14]     state byte          varies (0x13/0x27/0x40/etc.)
		//   [15]     0x00               CONSTANT
		//   [16]     0x03               CONSTANT (profile marker)
		//   [17..23] 7 zeros            CONSTANT
		//   [24..25] trailer LE16        varies (0x02d0 dom, 0x02bc CREATION)
		//   [26..29] 4 bytes             rank/skill (07 06 05 05 dom)
		//   [30..33] 4 bytes             varies (ff ff ff ff dom)
		//   [34]     0x01               CONSTANT
		//   [35..38] 4 bytes             trailer (35 03 99 03 dom)
		write(0x0a);                                        // [0]
		writeInt(pc.getCash());                              // [1..4]
		write(new byte[] {0x00, 0x00});                      // [5..6]
		write(new byte[] {0x04, 0x04, 0x04});                // [7..9]  CONSTANT
		write(new byte[] {0x00, 0x00, 0x00, 0x00});          // [10..13]
		write(0x00);                                         // [14] state placeholder
		write(0x00);                                         // [15]
		write(0x03);                                         // [16]
		write(new byte[]{0x00, 0x00, 0x00, 0x00,             // [17..23]
				0x00, 0x00, 0x00});
		writeShort(pl.getTransactionID() != 0
				? pl.getTransactionID() : 0x02d0);           // [24..25] trailer LE16
		write(new byte[]{0x07, 0x06, 0x05, 0x05});           // [26..29] rank/skill
		write(new byte[]{(byte)0xff, (byte)0xff,             // [30..33]
				(byte)0xff, (byte)0xff});
		write(0x01);                                         // [34]
		write(new byte[]{0x35, 0x03, (byte)0x99, 0x03});     // [35..38]

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
