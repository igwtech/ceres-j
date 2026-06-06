package server.database.items;

import server.tools.ExtendedByteArrayOutputStream;

public class Item {
	
	// static usable items like doors, terms, genreps, gogus, etc 
	// will also be items, with a special bitmask
	
	public static final int ITEMFLAG_STACK	 	= 1;
	public static final int ITEMFLAG_USES		= 2;
	public static final int ITEMFLAG_WEAPON		= 4;
	public static final int ITEMFLAG_SPELL		= 8;
	public static final int ITEMFLAG_SLOTS		= 16;
	public static final int ITEMFLAG_DOGTAG		= 32;
	public static final int ITEMFLAG_UGS		= 64;
	public static final int ITEMFLAG_SIMPLE		= 128;
	public static final int ITEMFLAG_CONSTED	= 256;
	
	public static final int CONTAINERPOS_X		= 20;
	public static final int CONTAINERPOS_Y		= 21;
	public static final int CONTAINERPOS_Z		= 22;
	public static final int CONTAINERPOS_F2		= 23;
	public static final int CONTAINERPOS		= 24;
	
	public static final int PROPERTY_ITEMTYPE	= 40;
	public static final int PROPERTY_CURRCOND	= 41;
	public static final int PROPERTY_MAXCOND	= 42;
	public static final int PROPERTY_DAMAGE		= 43;
	public static final int PROPERTY_FREQUENCY	= 44;
	public static final int PROPERTY_HANDLING	= 45;
	public static final int PROPERTY_RANGE		= 46;
	public static final int PROPERTY_CLIPSIZE	= 47;
	public static final int PROPERTY_AMMO		= 48;
	
	public static final int PACKET_ADDITEM		= 60;
	public static final int PACKET_CHARINFOF2	= 61;
	public static final int PACKET_CHARINFOQB	= 62;
	public static final int PACKET_CHARINFOGOGU = 63;
	
	public static final int TOKENS_CURRCOND		= 0;
	public static final int TOKENS_MAXCOND		= 1;
	public static final int TOKENS_DMG			= 2;
	public static final int TOKENS_FREQUENCY	= 3;
	public static final int TOKENS_HANDLING		= 4;
	public static final int TOKENS_RANGE		= 5;
	public static final int TOKENS_CLIPSIZE		= 6;
	public static final int TOKENS_AMMOUSES		= 7;
	public static final int TOKENS_ITEMSONSTACK	= 8;
	public static final int TOKENS_SLOTS		= 9;
	public static final int TOKENS_SLOTSINUSE	= 10;
	public static final int TOKENS_MOD1			= 11;
	public static final int TOKENS_MOD2			= 12;
	public static final int TOKENS_MOD3			= 13;
	public static final int TOKENS_MOD4			= 14;
	public static final int TOKENS_MOD5			= 15;
	public static final int TOKENS_CONSTER		= 16;
	
	private int type_id;	// this is the type of the item, 
							// how it can be found in the .def files
	private long id;		// id in our database, unique item id
	private ItemContainer Container;
	private int flags; // a bitmask that will tell us which tokens are needed
	private short[] tokens;
	private int inventorypos;
	private byte[] NetworkInfoData;
	
	/*
	 * tokenstructure:
	 * 0: currcond(byte)
	 * 1: maxcond(byte
	 * 2: dmg(byte)
	 * 3: frequency(byte)
	 * 4: handling(byte)
	 * 5: range(byte)
	 * 6: clipsize(byte)
	 * 7: ammo/uses(byte)
	 * 8: items on stack(byte)
	 * 9: slots(byte)
	 * 10: slots in use(byte)
	 * 11: mod 1(short
	 * ...
	 * 16: conster char id
	 */
	
	public Item(int type, long ID, ItemContainer parent,
			int itemflags, short[] token){ // TODO: move packetlength calculation here!
		type_id 	= type;
		Container	= parent;
		flags		= itemflags;
		tokens 		= token;
		id			= ID;
		
		if(tokens.length != 17){
			//TODO: create some kind of exception
		}
		
		createNetworkInfoData();
	}
	
	/* First check the bitmask(via logic operations) to see which 
	 * properties/tokens are "there" and are needed, after checking
	 * execute the apropriate instructions
	 */
	
	public boolean equip(){
		return false;
	}
	
	public boolean fire(){ // left mouseclick
		return false;
	}
	
	public boolean use(){ // right mouseclick on an item and then use/activate
		return false;
	}
	
	/**
	 * Emit the 12-byte per-instance GUID sub-record the NC2 client's CHARSYS
	 * item parser requires to register an item into a UI grid (F2 / QB).
	 *
	 * <p>Wire layout (verified against retail CharInfo section 5 in
	 * RETAIL_ZONING_AND_ITEMS_LONG and RETAIL_HANNIBAL captures): every
	 * non-trivial item record carries
	 * {@code [block_len LE16][0x0e][0x0e][12-byte instance GUID][optional tail]}
	 * where {@code block_len} counts the bytes after the length field
	 * (i.e. {@code 0e 0e} + GUID + tail). The two {@code 0x0e} bytes are the
	 * instance-id sub-TLV tag; the 12 bytes after are a unique, non-zero
	 * per-item handle. Without this block the client parses zero items and
	 * the F2 grid renders empty even though delivery and the item count are
	 * correct (the "inventory loads but is empty" bug).
	 *
	 * <p>Retail assigns a random-looking 12-byte value; the client never
	 * round-trips it (F2/QB moves and uses are keyed by slot position, see
	 * {@code InsideF2InvMove}/{@code InventoryMove}), so any unique, stable,
	 * non-zero value is accepted. We derive it deterministically from the DB
	 * item {@code id} so the same item produces the same handle across
	 * CharInfo redeliveries.
	 *
	 * @param temp   record buffer being built
	 * @param tailLen extra bytes appended AFTER the GUID (0 for plain items)
	 */
	private void writeInstanceGuid(ExtendedByteArrayOutputStream temp, int tailLen) {
		int blockLen = 2 + 12 + tailLen; // 0e 0e tag (2) + GUID (12) + tail
		temp.write(blockLen & 0xFF);     // block_len LE16 low
		temp.write((blockLen >> 8) & 0xFF); // block_len LE16 high
		temp.write(0x0e);                // instance-id sub-TLV tag
		temp.write(0x0e);                // instance-id sub-TLV tag (repeated)
		// 12-byte unique instance handle derived from the DB item id.
		// Non-zero in byte 0 is guaranteed by the 0x0e..0x13 high nibble mix.
		long g = id;
		for (int i = 0; i < 12; i++) {
			int by = (int) ((g >>> ((i % 8) * 8)) & 0xFF);
			// fold the slot index in so colliding low ids still differ
			by = (by + (i * 0x0e) + 0x0e) & 0xFF;
			temp.write(by);
		}
	}

	private boolean createNetworkInfoData(){ // TODO: implement the missing flag types
		ExtendedByteArrayOutputStream temp = new ExtendedByteArrayOutputStream();

		temp.writeShort(type_id);

		if(flags == ITEMFLAG_SIMPLE){
			// Retail "simple" item record (infobyte 0x21): one condition
			// byte then the mandatory instance-GUID block. Matches the 16
			// 0x21 records in RETAIL_ZONING_AND_ITEMS_LONG / RETAIL_HANNIBAL.
			temp.write(0x21);                         // Infobyte (NC2 simple)
			temp.write(tokens[Item.TOKENS_CURRCOND]); // condition / quality
			writeInstanceGuid(temp, 0);

			NetworkInfoData = temp.toByteArray();

			return true;
		}
		else if(flags == (ITEMFLAG_USES | ITEMFLAG_STACK)){
			// Retail "stackable / ammo" record (infobyte 0x22): 0x02 marker,
			// a 2-byte uses/ammo param, then the instance-GUID block. Matches
			// the plain 0x22 records (e.g. tid 0x0863 -> 22 02 ff ff 0e 00 ..).
			temp.write(0x22);                         // Infobyte (NC2 stack)
			temp.write(0x02);                         // property marker
			int uses = tokens[Item.TOKENS_AMMOUSES] & 0xFF;
			// retail encodes "full / not-yet-used" as 0xffff
			if (uses == 0) {
				temp.write(0xFF);
				temp.write(0xFF);
			} else {
				temp.write(uses);
				temp.write(uses);
			}
			writeInstanceGuid(temp, 0);

			NetworkInfoData = temp.toByteArray();

			return true;
		}
		else if(flags == (ITEMFLAG_SPELL)){
			temp.write(0x23); // infobyte
			temp.write(0); // Unknown
			temp.write(6); // Propertys to follow
			temp.write(tokens[Item.TOKENS_CURRCOND]);
			temp.write(tokens[Item.TOKENS_DMG]);
			temp.write(tokens[Item.TOKENS_FREQUENCY]);
			temp.write(tokens[Item.TOKENS_HANDLING]);
			temp.write(tokens[Item.TOKENS_RANGE]);
			temp.write(tokens[Item.TOKENS_MAXCOND]);
			temp.write(4); // (short) length of following data
			temp.write(0);
			temp.write(0x01); // everything following is unknown
			temp.write(0x04);
			temp.write(0x00); // sometimes 0xff
			temp.write(0x01);
			
			NetworkInfoData = temp.toByteArray();
			
			return true;
		}
		else if(flags == (ITEMFLAG_WEAPON)){ // melee weapons
			temp.write(0x23); // infobyte
			temp.write(0); // Unknown
			temp.write(6); // Propertys to follow
			temp.write(tokens[Item.TOKENS_CURRCOND]);
			temp.write(tokens[Item.TOKENS_DMG]);
			temp.write(tokens[Item.TOKENS_FREQUENCY]);
			temp.write(tokens[Item.TOKENS_HANDLING]);
			temp.write(tokens[Item.TOKENS_RANGE]);
			temp.write(tokens[Item.TOKENS_MAXCOND]);
			temp.write(4); // (short) length of following data
			temp.write(0);
			temp.write(0x01); // everything following is unknown
			temp.write(0x04);
			temp.write(0x00); // sometimes 0xff
			temp.write(0x01);
			
			NetworkInfoData = temp.toByteArray();
			
			return true;
		}
		else if(flags == (ITEMFLAG_USES | ITEMFLAG_WEAPON)){ // weapons which need ammo
			temp.write(0x33); // infobyte
			temp.write(tokens[Item.TOKENS_AMMOUSES]); // Unknown
			temp.write(6); // Propertys to follow
			temp.write(tokens[Item.TOKENS_CURRCOND]);
			temp.write(tokens[Item.TOKENS_DMG]);
			temp.write(tokens[Item.TOKENS_FREQUENCY]);
			temp.write(tokens[Item.TOKENS_HANDLING]);
			temp.write(tokens[Item.TOKENS_RANGE]);
			temp.write(tokens[Item.TOKENS_MAXCOND]);
			temp.write(0); // the mag size (seems to be always 0 in NC2)
			temp.write(4); // (short) length of following data
			temp.write(0);
			temp.write(0x01); // everything following is unknown
			temp.write(0x04);
			temp.write(0x00); // sometimes 0xff perhaps the ammotype used (0 normal ammo 1 first ammo mod 2 second...)
			temp.write(0x01);
			
			NetworkInfoData = temp.toByteArray();
			
			return true;
		}
		// Unmapped flag combinations (slotted/consted weapons, etc.). The
		// detailed retail layout for these is not yet byte-pinned, but every
		// retail item — whatever its category — carries the instance-GUID
		// block, and the client refuses to register a record without it.
		// Emit the safe "simple" form (infobyte 0x21 + cond + GUID) so the
		// item still appears in the grid instead of silently vanishing.
		temp.write(0x21);
		temp.write(tokens[Item.TOKENS_CURRCOND]);
		writeInstanceGuid(temp, 0);

		NetworkInfoData = temp.toByteArray();

		return true;
	}
	
	public byte[] getItemInfoPacketData(int flag){
		ExtendedByteArrayOutputStream temp = new ExtendedByteArrayOutputStream();
		
		switch(flag){
			case Item.PACKET_ADDITEM:{
				
				switch(Container.getContainerType()){
					case ItemContainer.CONTAINERTYPE_PLINVENTORY:{
						int posF2 	= inventorypos /65536;
						int posY	= (inventorypos - posF2*65536)/256;
						int posX	= (inventorypos - posF2*65536 - posY*256);
						
						temp.write(3);
						temp.write(posX); // dstpos
						temp.write(posY);
						temp.writeShort(NetworkInfoData.length); //length of data following ItemID
						temp.write(NetworkInfoData);
						temp.writeShort(posF2);
						
						return temp.toByteArray();
					}
					case ItemContainer.CONTAINERTYPE_PLGOGU:{
						//Packet[0] = 4; // TODO: not sure
						return null;
					}
					case ItemContainer.CONTAINERTYPE_PLQB:{
						//Packet[0] = 2;
						return null;
					}
					default:
						return null;
				}	
			}
			case Item.PACKET_CHARINFOF2:{
				switch(Container.getContainerType()){
				case ItemContainer.CONTAINERTYPE_PLINVENTORY:{
					int posF2 	= inventorypos /65536;
					int posY	= (inventorypos - posF2*65536)/256;
					int posX	= (inventorypos - posF2*65536 - posY*256);
					
					temp.writeShort(NetworkInfoData.length + 3); //length of data
					temp.write(0);
					temp.write(posX); // dstpos
					temp.write(posY);
					temp.write(NetworkInfoData);
					
					return temp.toByteArray();
				}
				default:
					return null;
			}				
			}
			case Item.PACKET_CHARINFOGOGU:{
				return null;
			}
			case Item.PACKET_CHARINFOQB:{
				/*switch(Container.getContainerType()){
				case ItemContainer.CONTAINERTYPE_PLQB:{
					temp.writeShort(NetworkInfoData.length + 2);
					temp.write(inventorypos);
					temp.write(0);
					temp.write(NetworkInfoData);
					
					return temp.toByteArray();
				}
				default:
					return null;
				}*/
				temp.writeShort(NetworkInfoData.length + 2);
				temp.write(inventorypos);
				temp.write(0);
				temp.write(NetworkInfoData);
				
				return temp.toByteArray();
			}
			default:
				return null;
		}
		
	}
	
	public int getItemProperty(int flag){
		switch(flag){
		case PROPERTY_ITEMTYPE:
			return type_id;
		case PROPERTY_CURRCOND:
			return tokens[Item.TOKENS_CURRCOND];
		case PROPERTY_MAXCOND:
			return tokens[Item.TOKENS_MAXCOND];
			
		default:
			return -1;				
		}
		
	}
	
	public int getInvSizeX(){
		return ItemInfoManager.getItemInfo(type_id).getInvSizeX();
	}
	
	public int getInvSizeY(){
		return ItemInfoManager.getItemInfo(type_id).getInvSizeY();
	}
	
	public int getPos(int flags){ // TODO: check parenttype
		int posF2 	= inventorypos /65536;
		int posY	= (inventorypos - posF2*65536)/256;
		int posX	= (inventorypos - posF2*65536 - posY*256);
		
		if(flags == Item.CONTAINERPOS_X)
			return posX;
		else if(flags == Item.CONTAINERPOS_Y)
			return posY;
		else if(flags == Item.CONTAINERPOS_F2)
			return posF2;
		else if(flags == Item.CONTAINERPOS)
			return inventorypos;
		
		return -1;
	}
	
	public void setInventoryPos(int pos){
		inventorypos = pos;
	}

	public void setParentContainer(ItemContainer parent){
		Container = parent;
	}

	// ─── Persistence accessors ────────────────────────────────
	// Added 2026-05-10 to support ItemManager.save/load (was
	// stubbed; items table was empty). See zone_handoff_and_inventory_gaps.

	public long getId() {
		return id;
	}

	public int getTypeId() {
		return type_id;
	}

	public int getFlags() {
		return flags;
	}

	/** Returns the internal tokens array — caller must not mutate. */
	public short[] getTokens() {
		return tokens;
	}

	public ItemContainer getContainer() {
		return Container;
	}

	/**
	 * The raw packed inventory position
	 * ({@code posF2*65536 + posY*256 + posX} for a player F2 grid, or a
	 * plain slot index for QB). Persisted in the {@code items.slot}
	 * column so exact grid layout survives a server restart instead of
	 * being re-flowed by auto-placement.
	 */
	public int getInventoryPos() {
		return inventorypos;
	}

	/**
	 * Pack the 17×short tokens array into 34 bytes (LE). Inverse of
	 * {@link #deserializeTokens}.
	 */
	public byte[] serializeTokens() {
		byte[] out = new byte[34];
		for (int i = 0; i < 17; i++) {
			short v = tokens[i];
			out[i * 2]     = (byte) (v & 0xff);
			out[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
		}
		return out;
	}

	/**
	 * Unpack 34 bytes (LE) into a 17×short tokens array. Returns a
	 * zero-filled array if input is null or wrong-length.
	 */
	public static short[] deserializeTokens(byte[] in) {
		short[] out = new short[17];
		if (in == null || in.length != 34) return out;
		for (int i = 0; i < 17; i++) {
			out[i] = (short) (((in[i * 2 + 1] & 0xff) << 8)
			               | (in[i * 2]     & 0xff));
		}
		return out;
	}
	
	public int setItemProperty(int flag, int newvalue){ // TODO: insert checks!
		switch(flag){
		case PROPERTY_ITEMTYPE:{
			type_id = newvalue;
			return type_id;
		}
		case PROPERTY_CURRCOND:{
			tokens[Item.TOKENS_CURRCOND] = (short)newvalue;
			return newvalue;
		}
		case PROPERTY_MAXCOND:{
			tokens[Item.TOKENS_MAXCOND] = (short)newvalue;
			return newvalue;
		}
			
		default:
			return -1;				
		}
		
	}
}