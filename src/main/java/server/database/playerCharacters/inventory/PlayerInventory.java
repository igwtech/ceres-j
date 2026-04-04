package server.database.playerCharacters.inventory;

import java.util.LinkedList;
import java.util.TreeMap;

import server.database.items.Item;
import server.database.items.ItemContainer;
import server.database.items.ItemManager;
import server.tools.Out;

public class PlayerInventory implements ItemContainer{ 

	int Contid;
	int count;
	
	TreeMap<Integer, Item> Items 	= new TreeMap<Integer, Item>();
	Item map[] 						= new Item[256]; // F2ids
	int xymap[][] 					= new int[8][256];
	int highestid;
	
	public PlayerInventory(int ContainerID){
		Contid 		= ContainerID;
		count 		= 0;
		highestid 	= -1;
		
		for(int z = 0; z < 255; z++){
			map[z] = null;
		}
		for(int x = 0; x < 8; x++){
			for(int y = 0; y < 256; y++){
				xymap[x][y] = -1;
			}
		}
		
		ItemManager.loadContainer(this);
	}
	
	public boolean addItem(int pos, Item it, int flags){
		if(count >= 255)
			return false;
		
		if(pos == -1){
			int x 		= it.getInvSizeX();
			int y		= it.getInvSizeY();
			
			int countsize = 0;
			
			for(int j = 0; j <= 255 - y; j++){ // y axis
				for(int i = 0; i <= 8 - x; i++){ // x axis
					if(xymap[i][j] == -1){ // no item at that pos
						for(int xi = 0; xi < x; xi++){ // x axis
							for(int yj = 0; yj < y; yj++){ // y axis
								if(xymap[i+xi][j+yj] == -1){ // no item at that poas
									countsize++;
								}
							}
						}
						if(countsize == x*y){
							for(int z = highestid + 1; z < 255; z++){
								if(map[z] == null){
									map[z] = it;
									Items.put(z, it);
									it.setInventoryPos(i+256*j+65536*z);
									it.setParentContainer(this);
									
									for(int xi = 0; xi < x; xi++){ // x axis
										for(int yj = 0; yj < y; yj++){ // y axis
											xymap[i+xi][j+yj] = z;
										}
									}
									
									count++;
									highestid = z;

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
		else if(flags == FLAG_DSTPOS_XY){
			int xcoord = (byte)pos;
			int ycoord = (byte)(pos >> 8);
			
			int x 		= it.getInvSizeX();
			int y		= it.getInvSizeY();
			
			int countsize = 0;
					
			if(xymap[xcoord][ycoord] == -1){ // no item at that pos
				for(int i = 0; i < x; i++){ // x axis
					for(int j = 0; j < y; j++){ // y axis
						if(xymap[xcoord + i][ycoord + j] == -1){ // no item at that poas
							countsize++;
						}
					}
				}
				if(countsize == x*y){
					for(int z = highestid + 1; z < 255; z++){
						if(map[z] == null){
							map[z] = it;
							Items.put(z, it);
							it.setInventoryPos(xcoord+256*ycoord+65536*z);
							it.setParentContainer(this);
									
							for(int xi = 0; xi < x; xi++){ // x axis
								for(int yj = 0; yj < y; yj++){ // y axis
									xymap[xcoord+xi][ycoord+yj] = z;
								}
							}
									
							count++;
							highestid = z;

							return true;
						}
					}							
					return false;
				}
				else
				countsize = 0;
			}			
			return false;
		}
		return false;
	}
	
	public void doSort(){
		if(highestid == count -1)
			return;
		
		boolean foundone = false;
		
		highestid = count - 1;
		
		for(int i = 0; i < count; i++){
			if(map[i] == null){
				for(int y = 0; y < 256; y++){
					for(int x = 0; x < 8; x++){
						if(xymap[x][y] >= count){
							Item it = map[xymap[x][y]];
							Items.remove(xymap[x][y]);
							
							map[xymap[x][y]] = null;
							map[i] = it;
							
							Items.put(i, it);
							it.setInventoryPos(x+256*y+65536*i);
							
							for(int yi = 0; yi < it.getInvSizeY(); yi++){
								for(int xj = 0; xj < it.getInvSizeX(); xj++){
									xymap[x + xj][y + yi] = i;
								}
							}							
							foundone = true;
							
							break;
						}
					}
					if(foundone){
						foundone = false;
						break;
					}
				}
			}
		}
	}
	
	public int getContainerID(){
		return Contid;
	}
	
	public int getContainerType(){
		return ItemContainer.CONTAINERTYPE_PLINVENTORY;
	}
	
	public Item getItem(int pos, int flags){
		if(flags == FLAG_DSTPOS_XY){
			int x = (byte)pos;
			int y = (byte)(pos >> 8);
			return map[xymap[x][y]];
		}
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
			int posF2 	= pos /65536;
			int posY	= (pos - posF2*65536)/256;
			int posX	= (pos - posF2*65536 - posY*256);
			
			for(int i = 0; i<256; i++){
				if(map[i] == it){
					int x 		= it.getInvSizeX();
					int y		= it.getInvSizeY();
					
					map[i] = null;//TODO: the item has to be edited but in the itemmanager!!
					Items.remove(i);
					for(int xi = 0; xi < x; xi++){ // x axis
						for(int yj = 0; yj < y; yj++){ // y axis
							xymap[posX+xi][posY+yj] = -1;
						}
					}
					
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
/*
Interface:

public boolean addItem(int pos, long ItemId, int flags);// flags, add anywhere??!
public int getContainerID();
public long getItem(int pos);
public int getItemPos(long id);
public long getItembyType(int type);
public boolean removeItem(int pos,long ItemId, int flags);
public LinkedList<Item> getallItems(); 
*/