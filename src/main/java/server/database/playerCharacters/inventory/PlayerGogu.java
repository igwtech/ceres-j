package server.database.playerCharacters.inventory;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.TreeMap;

import server.database.items.Item;
import server.database.items.ItemContainer;
import server.database.items.ItemManager;

public class PlayerGogu implements ItemContainer{ 

	int Contid;
	int count;
	
	TreeMap<Integer, Item> Items = new TreeMap<Integer, Item>();
	Item map[] = new Item[256];
	
	public PlayerGogu(int ContainerID){
		Contid = ContainerID;
		count = 0;
		
		for(int z = 0; z < 255; z++){
			map[z] = null;
		}
		
		ItemManager.loadContainer(this);
	}
	
	public boolean addItem(int pos, Item it, int flags){
		if(pos == -1){
			int x 		= it.getInvSizeX();
			int y		= it.getInvSizeY();
			
			int countsize = 0;
			
			for(int i = 0; i < 8 - x; i++){ // x axis
				for(int j = 0; j < 255 - y; j++){ // y axis
					if(!Items.containsKey(i+j*256)){ // no item at that pos
						for(int xi = 0; xi < x; xi++){ // x axis
							for(int yj = 0; yj < y; yj++){ // y axis
								if(!Items.containsKey(i+xi+(j+yj)*256)){ // no item at that poas
									countsize++;
								}
							}
						}
						if(countsize == x*y){
							for(int z = 0; z < 255; z++){
								if(map[z] == null){
									map[z] = it;
									Items.put(i+256*j+65536*z, it);
									it.setInventoryPos(i+256*j+65536*z);
									count++;
									return true;
								}
							}
							
							return false;
						}
						else
							countsize = 0;
					}
				}
			}
			
			return false;
		}
		return false;
	}
	
	public int getContainerID(){
		return Contid;
	}
	
	public int getContainerType(){
		return ItemContainer.CONTAINERTYPE_PLINVENTORY;
	}
	
	public Item getItem(int pos, int flags){
		return map[pos]; // TODO: correct this!
	}
	
	public Item getItembyType(int type){
		return null;
	}
	
	public int getNumberofItems(){
		return count;
	}
	
	public boolean isContainerFull(){
		for(int i = 0; i < 256; i++){
			if(map[i] == null){
				return false;
			}
		}
		return true;
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
		if(Items.containsValue(it)){ // do flag check!!!
			for(int i = 0; i<256; i++){
				if(map[i] == it){
					map[i] = null;//TODO: the item has to be edited but in the itemmanager!!
					Items.remove(it);
					count--;
					return true;
				}
			}
		}
		return false;
	}
	
	public LinkedList<Item> getallItems(){
		LinkedList<Item> List = new LinkedList<Item>();
		for(int i = 0; i<256; i++){
			if(map[i] != null){
				List.add(map[i]);
			}
		}
		return List;
	}
}