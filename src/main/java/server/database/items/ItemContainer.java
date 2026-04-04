package server.database.items;

import java.util.LinkedList;

public interface ItemContainer {
	
	public static final int CONTAINERTYPE_PLINVENTORY 	= 0;
	public static final int CONTAINERTYPE_PLGOGU		= 1;
	public static final int CONTAINERTYPE_PLQB			= 2;
	public static final int CONTAINERTYPE_BOX			= 3;
	public static final int CONTAINERTYPE_ZONE			= 4;
	
	public static final int FLAG_DSTPOS_XY						= 1;

	/*public boolean addItem(long id);
	public boolean addItemAtPos(long id, int pos);*/
	public boolean addItem(int pos, Item it, int flags);// flags, add anywhere??!
	public int getContainerID();
	public int getContainerType();
	public Item getItem(int pos, int flags);
	public Item getItembyType(int type);
	public int getNumberofItems();
	public boolean isContainerFull();
	public boolean moveItem(int srcpos, int dstpos, int flags);
	public boolean moveItem(Item it, int dstpos, int flags);
	public boolean removeItem(Item ItemId, int pos, int flags);
	public LinkedList<Item> getallItems(); // or better LL<long>?
	/*public int getItemPosByItemID(long id);
	public long getItemAtPos(int pos);
	public boolean moveItem(int srcpos, int dstpos);
	public boolean removeItemByItemID(long id);
	public boolean removeItemAtPos(int pos);*/
}
