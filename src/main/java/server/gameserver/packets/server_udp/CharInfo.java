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
		writeShort(100); //cur health
		writeShort(100); //max health
		writeShort(100); //cur psi
		writeShort(100); //max psi
		writeShort(100); //cur stamina
		writeShort(100); //max stamina
		writeShort(255); //cur unknown
		writeShort(255); //max unknown
		writeShort(101); //max health+1
		writeShort(101); //max health+1
		writeShort(101); //max health+1
		write(100); //100 - synaptic impairment
		write(128); //unknown
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
		write(0);//rank
		writeInt(100002);//App
		write(new byte[]{0x01, 0x00, 0x00, 0x00, 0x00});

		newSection(9); // Section 9 - Faction Sympathies
		writeShort(21); //21 fractions 
		write(1); //current fraction;
		write(0);
		write(4);
		writeFloat(10000); //sl
		writeFloat(10000); //ca
		writeFloat(10000); //dre
		writeFloat(10000); //nxt
		writeFloat(10000); //tt
		writeFloat(10000); //bt
		writeFloat(10000); //pp
		writeFloat(10000); //tu
		writeFloat(10000); //ts
		writeFloat(10000); //bd
		writeFloat(10000); //cs
		writeFloat(10000); //cm
		writeFloat(10000); //doy
		writeFloat(10000); //ab
		writeFloat(10000); //fa
		writeFloat(10000); //tg
		writeFloat(10000); //rl
		writeFloat(10000); //rm
		writeFloat(10000); //insects
		writeFloat(10000); //monster
		writeFloat(0); //lowsl
		writeFloat(10000); // sl
		writeFloat(0); // unknown
		write(1); // current faction
		
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
