package server.gameserver;

public class NPC {
	
	private int Xpos;
	private int Ypos;
	private int Zpos;
	private int HP;
	private int Armor;
	private int Type;
	private int MapID;

	public NPC(int x, int y, int z, int hp, int armor, int type, int ID){
		Xpos 	= x;
		Ypos	= y;
		Zpos	= z;
		HP		= hp;
		Armor	= armor;
		Type 	= type;
		MapID	= ID;
	}
	
	public void execute(){
		
	}
	
	public int getArmor(){
		return Armor;
	}
	
	public int getHP(){
		return HP;
	}
	
	public int getMapID(){
		return MapID;
	}
	
	public int getType(){
		return Type;
	}
	
	public int getXpos(){
		return Xpos;
	}
	
	public int getYpos(){
		return Ypos;
	}
	
	public int getZpos(){
		return Zpos;
	}
	
	public void setMapID(int ID){
		MapID = ID;
	}
}
