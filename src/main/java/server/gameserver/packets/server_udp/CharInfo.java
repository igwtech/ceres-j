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
		write(0xfa); //unknown
		write(pc.getMisc(PlayerCharacter.MISC_PROFESSION));
		pl.incrementTransactionID();
		pl.incrementTransactionID();
		pl.incrementTransactionID();
		writeShort(pl.getTransactionID());
		writeInt(pc.getMisc(pc.MISC_ID));
		writeShort(17); // in evo 2.1 this should be 16
		
		
		newSection(2); //pools
		write(4);
		write(4);
		writeShort(pc.getHealth()); //cur health
		writeShort(pc.getMaxHealth()); //max health
		writeShort(pc.getPsi()); //cur psi
		writeShort(pc.getMaxPsi()); //max psi
		writeShort(pc.getStamina()); //cur stamina
		writeShort(pc.getMaxStamina()); //max stamina
		writeShort(255); //cur unknown — literal (out of scope)
		writeShort(255); //max unknown — literal (out of scope)
		writeShort(101); //max health+1 — literal (out of scope)
		writeShort(101); //max health+1 — literal (out of scope)
		writeShort(101); //max health+1 — literal (out of scope)
		write(pc.getSynaptic()); //100 - synaptic impairment
		write(128); //unknown — literal
		write(0);
		write(0);
		
		newSection(3); //skills
		write(6);
		write(9);
		for (int i = 0; i < 6; i++) {
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
		write(0x2e);
		write(2);
		for (int i = 0; i < 0x2e; i++) {
			write(pc.getSubskillLVL(i));
			write(pc.getSubskillPtsPerLvl(i));
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
		

		newSection(7); // unknown
		write(0);

		newSection(0x0c); // gogu
		write(0); //number of items

		newSection(8); // Buddies, GRs, etc
		write(0x0a);
		writeInt(pc.getCash());
		write(new byte [] {
				0x00, 0x00 //number of grs
				, 0x04, 0x04, 0x04
				, 0x00, 0x00, 0x00, 0x00});
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
		
		newSection(0x0a); //clan data?

		newSection(0x0b);
		write(0);
		
		newSection(0x0d);
		write(0xfa); //unknown
		write(pc.getMisc(PlayerCharacter.MISC_PROFESSION));
		writeShort(pl.getTransactionID());
		writeInt(pc.getMisc(pc.MISC_ID));
	}	
}
