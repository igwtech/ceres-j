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

	/**
	 * Restore an item at its EXACT persisted position
	 * ({@code packedPos} = the value previously read from
	 * {@link Item#getInventoryPos()} and stored in the
	 * {@code items.slot} column). Unlike {@link #addItem} this never
	 * recomputes a free slot and never consults {@code ItemInfoManager}
	 * (so it works on a headless server with no client mounted) — it
	 * places the item byte-for-byte where it was, so a player's
	 * inventory layout is identical after a restart.
	 *
	 * <p>Returns {@code false} if the target position is already
	 * occupied or out of range; the caller (ItemManager) then falls
	 * back to {@link #addItem(int, Item, int) addItem(-1, …)} so the
	 * item still survives even if its slot collides.
	 *
	 * <p>Default implementation defers to auto-placement for container
	 * types that have no meaningful fixed layout.
	 */
	default boolean restoreItemAtPos(int packedPos, Item it) {
		return addItem(-1, it, 0);
	}

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
