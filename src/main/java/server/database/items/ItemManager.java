package server.database.items;

import java.util.LinkedList;
import java.util.TreeMap;

import server.database.items.Item;
import server.tools.Out;

public class ItemManager {

	private static TreeMap<Long, Item> Items 					= new TreeMap<Long, Item>();
	private static TreeMap<Integer, ItemContainer> Container 	= new TreeMap<Integer, ItemContainer>();
	//private static TreeMap<Integer, Long[]> ContainerItems 		= new TreeMap<Integer, Long[]>();
	private static LinkedList<Integer> ContainerIds 			= new LinkedList<Integer>();
	private static LinkedList<Long> ItemIds						= new LinkedList<Long>();
	// getItemContainerID?
	
	public static void load(){
		// while loading all the items, add the container Ids to the LinkedList
		// or add a special container ids table
	}
	
	public static void init(){
		
	}
	
	public static void saveall(){
		
	}
	
	public static void save(ItemContainer Container){
		
	}
	
	public static boolean loadContainer(ItemContainer cont){
		Container.put(cont.getContainerID(), cont);
		return true;
	}
	
	/**
	 * takes care of itemmoves between containers
	 * 
	 * @param srccont 	source container 
	 * @param srcpos 	the position in the sourcecontainer
	 * @param dstcont 	destination container
	 * @param dstpos	destination position in dstcont
	 * @param flags		special flags describing the movement
	 */
	public static boolean moveItem(ItemContainer srccont, int srcpos, ItemContainer dstcont, int dstpos, int flags){
		//TODO: check first if item at that pos already exists!
		if(flags == 0){
			Item it = srccont.getItem(srcpos, 0);
		
			if(it == null){
				return false;
				}
			
			int pos = it.getPos(Item.CONTAINERPOS);
			
			if(dstcont.addItem(dstpos, it, 0)){ //TODO: doesnt work when moving from qb to f2
				if(!srccont.removeItem(it, pos, 0)){
					dstcont.removeItem(it, dstpos, 0);
					return false;
				}
				return true;
			}
			
			Out.writeln(Out.Info, "could not add Item!");
		}
		else if(flags == ItemContainer.FLAG_DSTPOS_XY){
			Item it = srccont.getItem(srcpos, 0);
			
			if(it == null){
				return false;
				}
			
			int pos = it.getPos(Item.CONTAINERPOS);
			
			if(dstcont.addItem(dstpos, it, ItemContainer.FLAG_DSTPOS_XY)){ //TODO: doesnt work when moving from qb to f2
				if(!srccont.removeItem(it, pos, 0)){
					dstcont.removeItem(it, dstpos, ItemContainer.FLAG_DSTPOS_XY);
					return false;
				}
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * creates a new item and adds it to the specified container
	 * 
	 * @param dstcont	destination container
	 * @param dstpos	destination position in dstcont
	 * @param type		type of the item (may be looked up in items.def)
	 * @param currcond	current condition of the item
	 * @param maxcond	maximum condition
	 * @param tokens	special attributes of the items, e.g. slots, uses left, ...
	 * @param itemflags	tells us more about the special attributes
	 * @param flags		gives information about the positioning of the item
	 * @return			id of the item created
	 */
	public static Item createItem(ItemContainer dstcont, int dstpos, int type, short[] tokens, int itemflags, int flags){
		Item it = null;
		long id = -1;
		
		if(dstcont == null){
			return null;
		}
		else{
			if(dstcont.isContainerFull())
				return null;
			
			if(dstpos == -1){ // no matter where item is added
				
				if(ItemInfoManager.getItemInfo(type) == null)
					return null;
				
				id = getFreeItemId();
				if(id == 0)
					return null;
				
				if(tokens[Item.TOKENS_CURRCOND] >= 256 || tokens[Item.TOKENS_MAXCOND] >= 256)
					return null;
				
				it = new Item(type, id, dstcont, itemflags, tokens);
				if(!dstcont.addItem(dstpos, it, flags)){
					it = null;
					return null;
				}
				
				Items.put(id, it);
			}
			else{
				
			}			
		}
		return it;
	}
	
	public static boolean removeItem(int dstcont, int dstpos, long id, int flags){
		return false;
	}
	
	/**
	 * 
	 * @return	next free containerID
	 */
	public static int getFreeContId(){
		for(int i = 1; i < (int)2147483647; i++){
			if(!ContainerIds.contains(i)){
				ContainerIds.add(Integer.valueOf(i));
				return i;
			}
		}
		return 0;
	}
	
	/**
	 * 
	 * @return	next freeitemID
	 */
	public static long getFreeItemId(){
		for(long i = 1; i < 9223372036854775807L ; i++){
			if(!ItemIds.contains(i)){
				ItemIds.add(Long.valueOf(i));
				return i;
			}
		}
		return 0;
	}
	
	/**
	 * 
	 * @param id	id of the item to be retrieved
	 * @return		Item belonging to the specified id
	 */
	public static Item getItem(long id){
		if(Items.containsKey(id))
			return Items.get(id);
		else
			return null;
	}
	
	/**
	 * 
	 * @param id	id of the item whose size should be determined
	 * @return		SizeX + 10*SizeY
	 */
	public static int getItemSize(long id){
		if(!Items.containsKey(id))
			return 0;
		else{
			int size = 0;
			Item it = Items.get(id);
			size = it.getInvSizeX() + 10*it.getInvSizeY();
			return size;
		}
	}
}
