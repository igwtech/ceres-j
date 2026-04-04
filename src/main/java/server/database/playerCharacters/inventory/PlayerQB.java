package server.database.playerCharacters.inventory;

import java.util.LinkedList;

import server.database.items.Item;
import server.database.items.ItemContainer;
import server.database.items.ItemManager;

public class PlayerQB implements ItemContainer{ 

	int Contid;
	int count;
	
	Item map[] = new Item[47];
	
	public PlayerQB(int ContainerID){
		Contid = ContainerID;
		count = 0;
		
		for(int z = 0; z < 47; z++){
			map[z] = null;
		}
		
		ItemManager.loadContainer(this);
	}
	
	public boolean addItem(int pos, Item it, int flags){
		if(pos >= 0 && pos <= 46){
			if(map[pos] == null){
				map[pos] = it;
				it.setInventoryPos(pos);
				it.setParentContainer(this);
				count++;
				return true;
			}
			else
				return false;
		}
		return false;
	}
	
	public int getContainerID(){
		return Contid;
	}
	
	public int getContainerType(){
		return ItemContainer.CONTAINERTYPE_PLQB;
	}
	
	public Item getItem(int pos, int flags){
		if(pos >= 0 && pos <= 46)
			return map[pos]; // TODO: correct this!
		else
			return null;
	}
	
	public Item getItembyType(int type){
		return null;
	}
	
	public int getNumberofItems(){
		return count;
	}
	
	public boolean isContainerFull(){
		if(count >= 47)
			return true;
		else
			return false;
	}
	
	public boolean moveItem(Item it, int dstpos, int flags){
		// get item at srcpos, get size, look at dstpos whether item fits or not
		return false;
	}
	
	public boolean moveItem(int srcpos, int dstpos, int flags){
		// get item at srcpos, get size, look at dstpos whether item fits or not
		return false;
	}
	
	public boolean removeItem(Item it, int pos, int flags){
		if(map[pos] == it){
			map[pos] = null;
			count--;
			return true;
		}
		return false;
	}
	
	public LinkedList<Item> getallItems(){
		LinkedList<Item> List = new LinkedList<Item>();
		for(int i = 0; i<47; i++){
			if(map[i] != null){
				List.add(map[i]);
			}
		}
		return List;
	}
}