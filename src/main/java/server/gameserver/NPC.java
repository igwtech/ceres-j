package server.gameserver;

/**
 * A zone-resident non-player character (NPC).
 *
 * <p>Fields mirror the TinNS {@code npc_spawns} table schema plus the
 * runtime state needed for position broadcasts, WorldInfo replies, and
 * (future) AI ticks. The {@code mapID} is the world-object id the
 * client uses to reference this NPC in RequestWorldInfo queries; it
 * must live in the retail convention range 0x101–0x1FF (257–511).
 */
public class NPC {

	private int xpos;
	private int ypos;
	private int zpos;
	private int hp;
	private int maxHp;
	private int armor;
	private int type;
	private int mapID;
	private String name;
	private String scriptName;
	private String modelName;
	private int angle;
	private int zoneId;

	public NPC(int x, int y, int z, int hp, int armor, int type, int id) {
		this.xpos       = x;
		this.ypos       = y;
		this.zpos       = z;
		this.hp         = hp;
		this.maxHp      = hp;
		this.armor      = armor;
		this.type       = type;
		this.mapID      = id;
		this.name       = "";
		this.scriptName = "";
		this.modelName  = "";
		this.angle      = 0;
		this.zoneId     = 0;
	}

	public NPC(int id, int zoneId, int type, String name,
			   int x, int y, int z, int angle, int hp, int armor) {
		this.mapID      = id;
		this.zoneId     = zoneId;
		this.type       = type;
		this.name       = name != null ? name : "";
		this.scriptName = "";
		this.modelName  = "";
		this.xpos       = x;
		this.ypos       = y;
		this.zpos       = z;
		this.angle      = angle;
		this.hp         = hp;
		this.maxHp      = hp;
		this.armor      = armor;
	}

	public NPC(int id, int zoneId, int type, String name, String scriptName, String modelName,
			   int x, int y, int z, int angle, int hp, int armor) {
		this.mapID      = id;
		this.zoneId     = zoneId;
		this.type       = type;
		this.name       = name != null ? name : "";
		this.scriptName = scriptName != null ? scriptName : "";
		this.modelName  = modelName != null ? modelName : "";
		this.xpos       = x;
		this.ypos       = y;
		this.zpos       = z;
		this.angle      = angle;
		this.hp         = hp;
		this.maxHp      = hp;
		this.armor      = armor;
	}

	public int getArmor()        { return armor; }
	public int getHP()           { return hp; }
	public int getMaxHP()        { return maxHp; }
	public int getMapID()        { return mapID; }
	public int getType()         { return type; }
	public int getXpos()         { return xpos; }
	public int getYpos()         { return ypos; }
	public int getZpos()         { return zpos; }
	public String getName()      { return name; }
	public String getScriptName(){ return scriptName; }
	public String getModelName() { return modelName; }
	public int getAngle()        { return angle; }
	public int getZoneId()       { return zoneId; }

	public void setMapID(int id)    { this.mapID = id; }
	public void setHP(int hp)       { this.hp = hp; }
	public void setPosition(int x, int y, int z) {
		this.xpos = x; this.ypos = y; this.zpos = z;
	}

	/**
	 * Stable, per-NPC unique 32-bit world-instance handle written at
	 * {@code 0x03/0x28} offset [6..9] and required to be (a) unique
	 * across all NPCs in a zone and (b) constant across every packet
	 * for this NPC.
	 *
	 * <p>Retail evidence (verified decode in
	 * {@code docs/protocol/packets/udp_s2c_03_28.md} + the three raw
	 * samples in {@code docs/protocol/_data/packets.json}, byte-diffed
	 * 2026-05-16): this field is per-NPC — every sample carries a
	 * distinct value ({@code 0x379a516a}, {@code 0x78edef93},
	 * {@code 0x78edeb27}, {@code 0x78ee1d76}) and the previously
	 * hard-coded constant {@code 8958887} (0x0088B3A7) appears in
	 * none of the available evidence. Retail's exact bytes are
	 * heap-pointer derived and therefore session-specific and
	 * unreproducible; the client only needs a per-NPC handle that is
	 * unique within the zone and stable across that NPC's packets, so
	 * we synthesise a deterministic handle from the (zone, mapID)
	 * pair. The high bit is set so the handle can never collide with
	 * a low-range player/world object id or the all-zero sentinel.
	 */
	public int getWorldInstanceHandle() {
		return 0x80000000 | ((zoneId & 0x7FFF) << 16) | (mapID & 0xFFFF);
	}

	/**
	 * Non-zero, per-entity-stable 16-bit class/actor id written at raw
	 * {@code 0x1b} offset [13..14] (the field {@code udp_s2c_1b.md}
	 * calls {@code entity_class_id}).
	 *
	 * <p><strong>Why this must never be 0x0000.</strong> Byte-diffed
	 * 2026-05-17 against three retail captures (AUGUSTO 27 NPCs / NORMAN
	 * / DRSTONE4) decoded with {@code tools/pcap-decode.py}: this field
	 * is <em>100% stable per entity</em> across an entire session
	 * (e.g. AUGUSTO ent 0x0124 = 1739 ×103, ent 0x0102 = 366 ×84, the
	 * NCPD-guard group 0x0125–0x012f all = 29002 ×~60) and is
	 * <em>never 0x0000</em> for any mobile NPC. The raw {@code 0x1b}
	 * for an entity also arrives <em>before</em> its reliable
	 * {@code 0x03/0x28} WorldInfo (AUGUSTO 0x0124: 0x1b @ 277.953 s,
	 * 0x28 @ 278.433 s), so the client uses this id to create the
	 * world actor on first sight; a zero here means "no actor class"
	 * and the NPC is never instantiated — the user-visible
	 * "NPCs don't render" symptom.
	 *
	 * <p><strong>Why a derived value, not the retail bytes.</strong>
	 * The retail values (29002, 29046, 1739, 366, …) appear in
	 * <em>no</em> client def file (verified: grepped every decompressed
	 * {@code defs/pak_*.def}; {@code npc.def} maxes at id 20008 and the
	 * shared 29002/29046 are absent everywhere). They are
	 * server-assigned runtime spawn/world-actor handles — the same
	 * category as the {@code 0x28[7..10]} instance handle — and are
	 * therefore session-specific and unreproducible. There is no
	 * static type→entity_class_id table to derive (no live capture
	 * would yield one either: the value is a heap handle, not class
	 * metadata). The client only requires the field to be (a) non-zero
	 * and (b) stable across that entity's packets, so we emit the
	 * NPC's real {@code npc.def} type id (genuine client data, in the
	 * npc.def id space, non-zero for every curated spawn) and fall
	 * back to the same deterministic (zone, mapID) handle used by
	 * {@link #getWorldInstanceHandle()} for the handful of bulk
	 * {@code world_npcs} rows whose {@code npc_type_id} is 0.
	 */
	public int getEntityClassId() {
		int t = type & 0xFFFF;
		if (t != 0) {
			return t;
		}
		// world_npcs bulk rows can carry npc_type_id 0; synthesise a
		// stable 16-bit id from the same (zone, mapID) identity the
		// 0x28 instance handle uses so the field is unique and constant
		// per entity. Mix the zone into the HIGH byte and mapID across
		// the full 16 bits (no bitmask that would alias adjacent
		// mapIDs), then bump 0x0000 to 0x0001 so it can never collapse
		// to the "no actor class" sentinel without aliasing two NPCs.
		int h = (((zoneId & 0xFF) * 0x9E37) ^ (mapID & 0xFFFF)) & 0xFFFF;
		return h == 0 ? 0x0001 : h;
	}

	public boolean isDead() { return hp <= 0; }

	public void takeDamage(int amount) {
		hp = Math.max(0, hp - amount);
	}

	public void respawn() {
		hp = maxHp;
	}
}
