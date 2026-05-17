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
	 * <p>Retail evidence (331 {@code 0x03/0x28} packets across the
	 * AUGUSTO / NORMAN / DRSTONE / PLAZA→PEPPER captures, byte-diffed
	 * 2026-05-16): this field is a per-NPC server-assigned handle —
	 * 231 distinct values, each NPC's value invariant across all of
	 * its packets, and the previously hard-coded constant
	 * {@code 8958887} (0x0088B3A7) appears 0/331 times. Retail's exact
	 * bytes are heap-pointer derived and therefore session-specific
	 * and unreproducible; the client only enforces the uniqueness +
	 * stability invariant, so we synthesise a deterministic handle
	 * from the (zone, mapID) pair. The high bit is set so the handle
	 * can never collide with a low-range player/world object id or
	 * the all-zero sentinel.
	 */
	public int getWorldInstanceHandle() {
		return 0x80000000 | ((zoneId & 0x7FFF) << 16) | (mapID & 0xFFFF);
	}

	public boolean isDead() { return hp <= 0; }

	public void takeDamage(int amount) {
		hp = Math.max(0, hp - amount);
	}

	public void respawn() {
		hp = maxHp;
	}
}
