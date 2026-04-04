package server.database.playerCharacters;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;

import com.csvreader.CsvReader;
import com.csvreader.CsvWriter;

import server.database.accounts.Account;
import server.database.items.Item;
import server.database.items.ItemContainer;
import server.database.items.ItemManager;
import server.exceptions.StartupException;
import server.tools.Out;

public class PlayerCharacterManager {

	private static LinkedList<PlayerCharacter> pcList;
	private static int pcCounter;

	public static void init() throws StartupException {
		load();
		findpcCounter();
	}

	public static void stopServer() {
		save();
	}

	private static void load() {
		pcList = new LinkedList<PlayerCharacter>();
		try {
			CsvReader reader = new CsvReader("database/playerCharacters.csv");
			reader.readHeaders();
			
			while (reader.readRecord()) {
				PlayerCharacter pc = new PlayerCharacter();
				pc.setName(reader.get("name"));
				for (int i = 0; i < PlayerCharacter.MISCLIST.length; i++) {
					if (PlayerCharacter.MISCLIST[i] != null) {
						pc.setMisc(i, Integer.parseInt(reader.get(PlayerCharacter.MISCLIST[i])));
					} else {
						pc.setMisc(i, 0);
					}
				}
				
				for (int i = 1; i < PlayerCharacter.SKILLS.length; i++) {
					pc.setSkillLVL(i, Integer.parseInt(reader.get(PlayerCharacter.SKILLS[i] + "_lvl")));
					pc.setSkillPTS(i, Integer.parseInt(reader.get(PlayerCharacter.SKILLS[i] + "_pts")));
				}
				
				for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
					if (PlayerCharacter.SUBSKILLS[i] != null) {
						pc.setSubskillLVL(i, Integer.parseInt(reader.get(PlayerCharacter.SUBSKILLS[i])));
					} else {
						pc.setSubskillLVL(i, 0);
					}
				}
				
				int[] Contid 	= new int[3];
				Contid[0]		= Integer.parseInt(reader.get("F2InventoryContID"));
				Contid[1]		= Integer.parseInt(reader.get("GoguInventoryContID"));
				Contid[2]		= Integer.parseInt(reader.get("QBInventoryContID"));
				
				pc.initContainer(Contid);
				pcList.add(pc);
			}
			reader.close();
		} catch (IOException e) {
			// Failed to load player characters CSV; starting with empty list
		}
		Out.writeln(Out.Info, "Loaded " + pcList.size() + " player characters");
	}

	private static void save() {
		try {
			CsvWriter writer = new CsvWriter("database/playerCharacters.csv");

			writer.write("name");
			for (int i = 0; i < PlayerCharacter.MISCLIST.length; i++) {
				if (PlayerCharacter.MISCLIST[i] != null) {
					writer.write(PlayerCharacter.MISCLIST[i]);
				}
			}
			
			for (int i = 1; i < PlayerCharacter.SKILLS.length; i++) {
				writer.write(PlayerCharacter.SKILLS[i] + "_lvl");
				writer.write(PlayerCharacter.SKILLS[i] + "_pts");
			}
			
			for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
				if (PlayerCharacter.SUBSKILLS[i] != null) {
					writer.write(PlayerCharacter.SUBSKILLS[i]);
				}
			}
			writer.write("F2InventoryContID");
			writer.write("GoguInventoryContID");
			writer.write("QBInventoryContID");
			writer.endRecord();
			
			for ( Iterator<PlayerCharacter> j = pcList.iterator(); j.hasNext(); )
			{
				PlayerCharacter pc = j.next();
				
				writer.write(pc.getName());
				for (int i = 0; i < PlayerCharacter.MISCLIST.length; i++) {
					if (PlayerCharacter.MISCLIST[i] != null) {
						writer.write(String.valueOf(pc.getMisc(i)));
					}
				}
				
				for (int i = 1; i < PlayerCharacter.SKILLS.length; i++) {
					writer.write(String.valueOf(pc.getSkillLVL(i)));
					writer.write(String.valueOf(pc.getSkillPts(i)));
				}
				
				for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
					if (PlayerCharacter.SUBSKILLS[i] != null) {
						writer.write(String.valueOf(pc.getSubskillLVL(i)));
					}
				}
				
				writer.write(String.valueOf(pc.getContainer(PlayerCharacter.PLAYERCONTAINER_F2).getContainerID()));
				writer.write(String.valueOf(pc.getContainer(PlayerCharacter.PLAYERCONTAINER_GOGU).getContainerID()));
				writer.write(String.valueOf(pc.getContainer(PlayerCharacter.PLAYERCONTAINER_QB).getContainerID()));
				writer.endRecord();		  
			}
			
			writer.close();
		} catch (IOException e) {
			// Failed to save player characters CSV
		}
	}

	private static void findpcCounter() {
		int id = 0;
		for ( Iterator<PlayerCharacter> i = pcList.iterator(); i.hasNext(); )
		{
			PlayerCharacter pc = i.next();
			if (pc.getMisc(PlayerCharacter.MISC_ID) > id) {
				id = pc.getMisc(PlayerCharacter.MISC_ID);
			}
		}
		pcCounter = id + 1;
	}

	public static PlayerCharacter getCharacter(int charid) {
		synchronized(pcList) {
			ListIterator<PlayerCharacter> it = pcList.listIterator();
			while (it.hasNext()) {
				PlayerCharacter pc = it.next();
				if (pc.getMisc(PlayerCharacter.MISC_ID) == charid) return pc;
			}
			return null;
		}
	}

	public static boolean checkCharName(String name) {
		synchronized(pcList) {
			ListIterator<PlayerCharacter> it = pcList.listIterator();
			while (it.hasNext()) {
				PlayerCharacter pc = it.next();
				if (pc.getName().equalsIgnoreCase(name)) return false;
			}
			return true;
		}
	}

	public static boolean createCharacter(PlayerCharacter pc, Account account, int spot) {
		switch(pc.getMisc(PlayerCharacter.MISC_CLASS) / 2) { // TODO alot here
		case 0:{ //pe
			pc.setSkillLVL(PlayerCharacter.STR, 3);
			pc.setSkillLVL(PlayerCharacter.DEX, 3);
			pc.setSkillLVL(PlayerCharacter.CON, 3);
			pc.setSkillLVL(PlayerCharacter.INT, 3);
			pc.setSkillLVL(PlayerCharacter.PSI, 1);
			break;
		}
		case 1:{ //spy
			pc.setSkillLVL(PlayerCharacter.STR, 2);
			pc.setSkillLVL(PlayerCharacter.DEX, 3);
			pc.setSkillLVL(PlayerCharacter.CON, 2);
			pc.setSkillLVL(PlayerCharacter.INT, 5);
			pc.setSkillLVL(PlayerCharacter.PSI, 1);
			break;
		}
		case 2:{ //tank
			pc.setSkillLVL(PlayerCharacter.STR, 5);
			pc.setSkillLVL(PlayerCharacter.DEX, 2);
			pc.setSkillLVL(PlayerCharacter.CON, 4);
			pc.setSkillLVL(PlayerCharacter.INT, 1);
			pc.setSkillLVL(PlayerCharacter.PSI, 1);
			break;
		}
		case 3:{ //monk
			pc.setSkillLVL(PlayerCharacter.STR, 1);
			pc.setSkillLVL(PlayerCharacter.DEX, 2);
			pc.setSkillLVL(PlayerCharacter.CON, 1);
			pc.setSkillLVL(PlayerCharacter.INT, 4);
			pc.setSkillLVL(PlayerCharacter.PSI, 5);
			break;
		}
		}
		
		// we should create some items too
		pc.setMisc(PlayerCharacter.MISC_LOCATION, 1); // TODO something more usefull?
		int[] Contid = new int[3];
		
		Contid[0]	= ItemManager.getFreeContId();
		Contid[1]	= ItemManager.getFreeContId();
		Contid[2]	= ItemManager.getFreeContId();
		
		pc.initContainer(Contid);
		ItemContainer Cont = pc.getContainer(PlayerCharacter.PLAYERCONTAINER_F2);
		
		short[] tokens = new short[17];
		for(int i = 0; i < 17; i++){
			tokens[i] = 0;
		}
		
		tokens[Item.TOKENS_CURRCOND] 		= 255;
		tokens[Item.TOKENS_MAXCOND] 		= 255;
		tokens[Item.TOKENS_DMG] 			= 200;
		tokens[Item.TOKENS_FREQUENCY] 		= 200;
		tokens[Item.TOKENS_HANDLING] 		= 200;
		tokens[Item.TOKENS_RANGE] 			= 200;
		tokens[Item.TOKENS_AMMOUSES] 		= 3;
		tokens[Item.TOKENS_ITEMSONSTACK]	= 5;
		
		ItemManager.createItem(Cont, -1, 19, tokens, Item.ITEMFLAG_WEAPON, 0);
		ItemManager.createItem(Cont, -1, 35, tokens, Item.ITEMFLAG_USES | Item.ITEMFLAG_STACK, 0);
		ItemManager.createItem(Cont, -1, 101, tokens, Item.ITEMFLAG_SPELL, 0);
		ItemManager.createItem(Cont, -1, 100, tokens, Item.ITEMFLAG_SPELL, 0);
		
		synchronized(pcList) {
			pc.setMisc(PlayerCharacter.MISC_ID, pcCounter);
			pcCounter++;
			
			pcList.add(pc);
			account.setChar(spot, pc.getMisc(PlayerCharacter.MISC_ID));
		}
		return true;
	}

	public static void deleteCharacter(int i) {
		synchronized(pcList) {
			ListIterator<PlayerCharacter> it = pcList.listIterator();
			while (it.hasNext()) {
				PlayerCharacter pc = it.next();
				if (pc.getMisc(PlayerCharacter.MISC_ID) == i) {
					pc.deleteAll();
					it.remove();
					return;
				}
			}
		}
	}

}
/*

public static void deleteCharacter(int i) {
	}
}


public boolean createCharacter(Account account, int spot) {
}


*/