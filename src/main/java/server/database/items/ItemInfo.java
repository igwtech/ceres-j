package server.database.items;

public class ItemInfo {

	private int id;				// the id of the Item
	private int TechLevel;		// the TL of the Item
	private int Type;			// decides whether the item is useable, a weapon, equipable etc
	private float Weight;
	private boolean Stackable;	// can the item be stacked?
	private int InvSizeX;		// x size of Items in Inventory
	private int InvSizeY;		// y size of Items in Inventory
	private String Name;		// the name of the Item

	public ItemInfo(String[] tokens) {
		int length = tokens.length;
		
		if (tokens[length-1].equals("|"))
			length--;
		
		id = Integer.parseInt(tokens[1]);
		Name = new String(tokens[2]);
		TechLevel = Integer.parseInt(tokens[3]);
		Type = Integer.parseInt(tokens[4]);
		
		if(Integer.parseInt(tokens[8]) == 1)
			Stackable = true;
		else
			Stackable = false;
		
		Weight = Float.parseFloat(tokens[10]);
		
		InvSizeX = Integer.parseInt(tokens[21]);
		InvSizeY = Integer.parseInt(tokens[22]);
	}
		
	public int getID() {
		return id;
	}

	public String getName() {
		return Name;
	}
	
	public int getTechLevel(){
		return TechLevel;
	}
	
	public int getType(){
		return Type;
	}
	
	public float getWeight(){
		return Weight;
	}
	
	public boolean isStackable(){
		return Stackable;
	}
	
	public int getInvSizeX(){
		return InvSizeX;
	}
	
	public int getInvSizeY(){
		return InvSizeY;
	}
}
