package server.database.playerCharacters;

import server.database.items.ItemContainer;
import server.database.items.ItemManager;
import server.database.modelTextures.ModelTexture;
import server.database.modelTextures.ModelTextureManager;
import server.database.playerCharacters.inventory.*;

public class PlayerCharacter {

	public static final String[] SKILLS = {null, "str", "dex", "con", "int", "psi"}; 

	public static final int STR = 1;
	public static final int DEX = 2;
	public static final int CON = 3;
	public static final int INT = 4;
	public static final int PSI = 5;

	
	public static final String[] SUBSKILLS = {
		null,  "mc",  "hc",  "tra", null,  null,  null,  null,  null, null,
		"pc",  "rc",  "tc",  "vhc", "agl", "rep", "rec", "rcl", null, null,
		"atl", "end", "for", "fir", "enr", "xrr", "por", "hlt", null, null,
		"hck", "brt", "psu", "wep", "cst", "res", "imp", null,  null, null,
		"ppu", "apu", "mst", "ppw", "psr", "wpw"
	};
	
	public static final int SUBSKILL_MC = 1;
	public static final int SUBSKILL_HC = 2;
	public static final int SUBSKILL_TRA = 3;
	
	public static final int SUBSKILL_PC = 10;
	public static final int SUBSKILL_RC = 11;
	public static final int SUBSKILL_TC = 12;
	public static final int SUBSKILL_VHC = 13;
	public static final int SUBSKILL_AGL = 14;
	public static final int SUBSKILL_REP = 15;
	public static final int SUBSKILL_REC = 16;
	public static final int SUBSKILL_RCL = 17;
	
	public static final int SUBSKILL_ATL = 20;
	public static final int SUBSKILL_END = 21;
	public static final int SUBSKILL_FOR = 22;
	public static final int SUBSKILL_FIR = 23;
	public static final int SUBSKILL_ENR = 24;
	public static final int SUBSKILL_XRR = 25;
	public static final int SUBSKILL_POR = 26;
	public static final int SUBSKILL_HLT = 27;

	public static final int SUBSKILL_HCK = 30;
	public static final int SUBSKILL_BRT = 31;
	public static final int SUBSKILL_PSU = 32;
	public static final int SUBSKILL_WEP = 33;
	public static final int SUBSKILL_CST = 34;
	public static final int SUBSKILL_RES = 35;
	public static final int SUBSKILL_IMP = 36;
	
	public static final int SUBSKILL_PPU = 40;
	public static final int SUBSKILL_APU = 41;
	public static final int SUBSKILL_MST = 42;
	public static final int SUBSKILL_PPW = 43;
	public static final int SUBSKILL_PSR = 44;
	public static final int SUBSKILL_WPW = 45;
	
	//evo 2.1
/*	public static final String[] SUBSKILLSSQL = {
		null,  "mc",  "hc",  "tra", null,  null,  null,  null,  null, null,
		"pc",  "rc",  "tc",  "vhc", "agl", "rep", "rec", "rcl", null, null,
		"atl", "edn", "fro", "fir", "enr", "xrr", "por", "hlt", null, null,
		"hck", "brt", "psu", "wep", "cst", "res", "imp", null,  null, null,
		"ppu", "apu", "mst", "ppw", "psr", "wpw"
	};
	//evo 2.1
	public static final int SUBSKILL_MC = 1;
	public static final int SUBSKILL_HC = 2;
	public static final int SUBSKILL_TRA = 3;
	
	public static final int SUBSKILL_PC = 10;
	public static final int SUBSKILL_RC = 11;
	public static final int SUBSKILL_TC = 12;
	public static final int SUBSKILL_VHC = 13;
	public static final int SUBSKILL_AGL = 14;
	public static final int SUBSKILL_REP = 15;
	public static final int SUBSKILL_REC = 16;
	public static final int SUBSKILL_RCL = 17;
	
	public static final int SUBSKILL_ATL = 20;
	public static final int SUBSKILL_END = 21;
	public static final int SUBSKILL_FOR = 22;
	public static final int SUBSKILL_FIR = 23;
	public static final int SUBSKILL_ENR = 24;
	public static final int SUBSKILL_XRR = 25;
	public static final int SUBSKILL_POR = 26;
	public static final int SUBSKILL_HLT = 27;

	public static final int SUBSKILL_HCK = 30;
	public static final int SUBSKILL_BRT = 31;
	public static final int SUBSKILL_PSU = 32;
	public static final int SUBSKILL_WEP = 33;
	public static final int SUBSKILL_CST = 34;
	public static final int SUBSKILL_RES = 35;
	public static final int SUBSKILL_IMP = 36;
	
	public static final int SUBSKILL_PPU = 40;
	public static final int SUBSKILL_APU = 41;
	public static final int SUBSKILL_MST = 42;
	public static final int SUBSKILL_PPW = 43;
	public static final int SUBSKILL_PSR = 44;
	public static final int SUBSKILL_WPW = 45;*/

	
	public static final String[] MISCLIST = {
		null, "class", "profession", "location", null, null, "faction",
		"model_head", "model_torso", "model_leg", "model_hair", "model_beard",
		"texture_head", "texture_torso", "texture_leg", "id",
		"x_coordinate", "y_coordinate", "z_coordinate", "orientation",
		"tilt", "status"
	};
	

	public static final int MISC_CLASS = 1;
	public static final int MISC_PROFESSION = 2;
	public static final int MISC_LOCATION = 3;
	
	
	public static final int MISC_FACTION = 6;
	public static final int MODEL_HEAD = 7;
	public static final int MODEL_TORSO = 8;
	public static final int MODEL_LEG = 9;
	public static final int MODEL_HAIR = 10;
	public static final int MODEL_BEARD = 11;
	public static final int TEXTURE_HEAD = 12;
	public static final int TEXTURE_TORSO = 13;
	public static final int TEXTURE_LEG = 14;
	public static final int MISC_ID = 15; // unique player id
	public static final int MISC_X_COORDINATE = 16; // forward 
	public static final int MISC_Y_COORDINATE = 17; // left
	public static final int MISC_Z_COORDINATE = 18; // up
	public static final int MISC_ORIENTATION = 19;
	public static final int MISC_TILT = 20;
	public static final int MISC_STATUS = 21;
	
	public static final int PLAYERCONTAINER_GOGU	= 4;
	public static final int PLAYERCONTAINER_F2		= 3;
	public static final int PLAYERCONTAINER_QB		= 2;

	private String name = new String();
	private int[] misc = new int[MISCLIST.length];
	private int[] skillslvl = new int[SKILLS.length];
	private int[] skillspts = new int[SKILLS.length];
	private int[] subskillslvl = new int[SUBSKILLS.length];
	
	private int F2InventoryContID;
	private int GoguInventoryContID;
	private int QBInventoryContID;
	
	private PlayerInventory Inventory;
	private PlayerQB		QB;
	private PlayerGogu		Gogu;

	public String getName() {
		return name;
	}

	public int getMisc(int i) {
		return misc[i];
	}
	
	public ItemContainer getContainer(int flag){
		switch(flag){
		case PLAYERCONTAINER_F2:
			return Inventory;
		case PLAYERCONTAINER_QB:
			return QB;
		case PLAYERCONTAINER_GOGU:
			return Gogu;
			default:
				return null;
		}
	}
	
	public void initContainer(int[] Contid){
		F2InventoryContID 	= Contid[0];
		GoguInventoryContID = Contid[1];
		QBInventoryContID	= Contid[2];

		Inventory 			= new PlayerInventory(F2InventoryContID);
		Gogu				= new PlayerGogu(GoguInventoryContID);
		QB					= new PlayerQB(QBInventoryContID);
	}

	public void setMisc(int i, int value) {
		misc[i] = value;
	}

	public void setName(String string) {
		name = string;
	}

	public void setSubskillLVL(int i, int j) {
		subskillslvl[i] = j;
	}

	public void setSkillLVL(int i, int j) {
		skillslvl[i] = j;
	}

	public void setSkillPTS(int i, int j) {
		skillspts[i] = j;
	}

	public int getSkillLVL(int i) {
		return skillslvl[i];
	}

	public int getSkillPts(int i) {
		if(i == 1)
			return 195;
		return skillspts[i];
		//return 10;
	}

	public int getSubskillLVL(int i) {
		if(i == 3)
			return 5;
		return subskillslvl[i];
	}

	public int getSkillXP(int i) {
		switch(i){
		case 1:
			return 5880305;
		case 2:
			return 192224802;
		case 3:
			return 1322698;
		case 4:
			return 1639213255;
		case 5:
			return 42338;
		}
		return 0;
	}

	public int getSkillRate(int i) {
		switch(this.getMisc(PlayerCharacter.MISC_CLASS) / 2) { // TODO alot here
		case 0:{ //pe
			if(i == PlayerCharacter.STR)
				return 60;
			if(i == PlayerCharacter.DEX)
				return 75;
			if(i == PlayerCharacter.CON)
				return 65;
			if(i == PlayerCharacter.INT)
				return 65;
			if(i == PlayerCharacter.PSI)
				return 35;
			break;
		}
		case 1:{ //spy
			if(i == PlayerCharacter.STR)
				return 48;
			if(i == PlayerCharacter.DEX)
				return 8;
			if(i == PlayerCharacter.CON)
				return 48;
			if(i == PlayerCharacter.INT)
				return 8;
			if(i == PlayerCharacter.PSI)
				return 64;
			break;
		}
		case 2:{ //tank
			if(i == PlayerCharacter.STR)
				return 100;
			if(i == PlayerCharacter.DEX)
				return 75;
			if(i == PlayerCharacter.CON)
				return 100;
			if(i == PlayerCharacter.INT)
				return 25;
			if(i == PlayerCharacter.PSI)
				return 0;
			break;
		}
		case 3:{ //monk
			if(i == PlayerCharacter.STR)
				return 20;
			if(i == PlayerCharacter.DEX)
				return 40;
			if(i == PlayerCharacter.CON)
				return 40;
			if(i == PlayerCharacter.INT)
				return 100;
			if(i == PlayerCharacter.PSI)
				return 100;
			break;
		}
		}
		return 48;
	}

	public int getSkillMax(int i) {
		switch(this.getMisc(PlayerCharacter.MISC_CLASS) / 2) { // TODO alot here
		case 0:{ //pe
			if(i == PlayerCharacter.STR)
				return 60;
			if(i == PlayerCharacter.DEX)
				return 75;
			if(i == PlayerCharacter.CON)
				return 65;
			if(i == PlayerCharacter.INT)
				return 65;
			if(i == PlayerCharacter.PSI)
				return 35;
			break;
		}
		case 1:{ //spy
			if(i == PlayerCharacter.STR)
				return 40;
			if(i == PlayerCharacter.DEX)
				return 100;
			if(i == PlayerCharacter.CON)
				return 40;
			if(i == PlayerCharacter.INT)
				return 100;
			if(i == PlayerCharacter.PSI)
				return 20;
			break;
		}
		case 2:{ //tank
			if(i == PlayerCharacter.STR)
				return 100;
			if(i == PlayerCharacter.DEX)
				return 75;
			if(i == PlayerCharacter.CON)
				return 100;
			if(i == PlayerCharacter.INT)
				return 25;
			if(i == PlayerCharacter.PSI)
				return 0;
			break;
		}
		case 3:{ //monk
			if(i == PlayerCharacter.STR)
				return 20;
			if(i == PlayerCharacter.DEX)
				return 40;
			if(i == PlayerCharacter.CON)
				return 40;
			if(i == PlayerCharacter.INT)
				return 100;
			if(i == PlayerCharacter.PSI)
				return 100;
			break;
		}
		}
		return 10;
	}

	public int getSubskillPtsPerLvl(int i) {
		if(subskillslvl[i] < 50)
			return 1;
		if(subskillslvl[i] >= 50 && subskillslvl[i] < 75)
			return 2;
		if(subskillslvl[i] >= 75 && subskillslvl[i] < 100)
			return 3;
		if(subskillslvl[i] >= 100)
			return 5;
		return 1;
	}

	public void setModel(int i, int value) {
		misc[i] = value;
	}

	public int getModel(int i) {
		return misc[i];
	}

	public void setTexture(int i, int value) {
		ModelTexture mte = ModelTextureManager.getEntry(getMisc(MODEL_HEAD));
		if (mte == null) {
			misc[i] = 0;
		} else if (mte.isOldFormat()) {
			switch (i) {
			case TEXTURE_HEAD:
				misc[i] = mte.findTextureHead(value);
				break;
			case TEXTURE_TORSO:
				misc[i] = mte.findTextureTorso(value);
				break;
			case TEXTURE_LEG:
				misc[i] = mte.findTextureLeg(value);
				break;
			}
		} else {
			switch (i) {
			case TEXTURE_TORSO:
				mte = ModelTextureManager.getEntry(getMisc(MODEL_TORSO));
				break;
			case TEXTURE_LEG:
				mte = ModelTextureManager.getEntry(getMisc(MODEL_LEG));
				break;
			}
			if (mte == null) {
				misc[i] = -1;
			} else {
				misc[i] = mte.findTextureHead(value);
			}
		}
	}

	public int getTexture(int i) {
		ModelTexture mte = ModelTextureManager.getEntry(getMisc(MODEL_HEAD));
		if (mte == null) return -1; // this is an error!!
		if (mte.isOldFormat()) {
			switch (i) {
			case TEXTURE_HEAD:
				return mte.getTextureHead(getMisc(i));
			case TEXTURE_TORSO:
				return mte.getTextureTorso(getMisc(i));
			case TEXTURE_LEG:
				return mte.getTextureLeg(getMisc(i));
			}
		} else {
			switch (i) {
			case TEXTURE_TORSO:
				mte = ModelTextureManager.getEntry(getMisc(MODEL_TORSO));
				break;
			case TEXTURE_LEG:
				mte = ModelTextureManager.getEntry(getMisc(MODEL_LEG));
				break;
			}
			if (mte == null) {
				return -1;
			} else {
				return mte.getTextureHead(getMisc(i));
			}
		}
		return -1;
	}

	public int getTextureIndex(int i) {
		return misc[i];
	}
	
	public int getCash(){
		return 1001;
	}

	public void deleteAll() {
		// TODO Auto-generated method stub
	}
}
