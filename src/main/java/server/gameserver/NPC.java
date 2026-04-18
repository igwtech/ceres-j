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
	private int angle;
	private int zoneId;

	public NPC(int x, int y, int z, int hp, int armor, int type, int id) {
		this.xpos   = x;
		this.ypos   = y;
		this.zpos   = z;
		this.hp     = hp;
		this.maxHp  = hp;
		this.armor  = armor;
		this.type   = type;
		this.mapID  = id;
		this.name   = "";
		this.angle  = 0;
		this.zoneId = 0;
	}

	public NPC(int id, int zoneId, int type, String name,
			   int x, int y, int z, int angle, int hp, int armor) {
		this.mapID  = id;
		this.zoneId = zoneId;
		this.type   = type;
		this.name   = name != null ? name : "";
		this.xpos   = x;
		this.ypos   = y;
		this.zpos   = z;
		this.angle  = angle;
		this.hp     = hp;
		this.maxHp  = hp;
		this.armor  = armor;
	}

	public int getArmor()  { return armor; }
	public int getHP()     { return hp; }
	public int getMaxHP()  { return maxHp; }
	public int getMapID()  { return mapID; }
	public int getType()   { return type; }
	public int getXpos()   { return xpos; }
	public int getYpos()   { return ypos; }
	public int getZpos()   { return zpos; }
	public String getName(){ return name; }
	public int getAngle()  { return angle; }
	public int getZoneId() { return zoneId; }

	public void setMapID(int id)    { this.mapID = id; }
	public void setHP(int hp)       { this.hp = hp; }
	public void setPosition(int x, int y, int z) {
		this.xpos = x; this.ypos = y; this.zpos = z;
	}

	public boolean isDead() { return hp <= 0; }

	public void takeDamage(int amount) {
		hp = Math.max(0, hp - amount);
	}

	public void respawn() {
		hp = maxHp;
	}
}
