package server.ecs;

import server.database.playerCharacters.PlayerCharacter;

/**
 * Concrete component arrays for the Ceres-J MVP ECS.
 *
 * <p>This is deliberately a plain container of {@code public final} sparse-set arrays
 * rather than an abstract "registry". To add a component, add a field here and start
 * using it &mdash; no registration step, no interface to implement.
 *
 * <p>Components are chosen to cover the shape of {@link PlayerCharacter} so that the
 * existing save/load path can bridge into the ECS. See {@link PlayerCharacterBridge}.
 *
 * <p>Layout choices:
 * <ul>
 *   <li>Position / orientation / tilt / status are stored as separate {@code int}
 *       components because Movement packets toggle them independently.</li>
 *   <li>Skills and subskills live in single boxed arrays rather than one IntComponentArray
 *       per subskill &mdash; there are 46 subskill slots and exploding that into 46 sparse
 *       sets would be wasteful. The skill block is still cache-friendly because each
 *       entity's skills are contiguous.</li>
 *   <li>The {@code id} / {@code accountId} / {@code zoneId} int components let systems
 *       resolve entities back to legacy data without touching {@link PlayerCharacter}.</li>
 * </ul>
 *
 * <p>Instantiate one {@code Components} bundle alongside one {@link World} &mdash; typically
 * as a static singleton on the game-server side (see {@link EcsRegistry}).
 */
public final class Components {

	/** Display name (character name for players, mob name for NPCs). */
	public final ComponentArray<String> name = new ComponentArray<>();

	/** Persistent character id (matches {@code PlayerCharacter.MISC_ID}). */
	public final IntComponentArray characterId = new IntComponentArray();

	/** Account id, for players. */
	public final IntComponentArray accountId = new IntComponentArray();

	/** Current zone / location id (matches {@code PlayerCharacter.MISC_LOCATION}). */
	public final IntComponentArray zoneId = new IntComponentArray();

	/** Position X, Y, Z in the zone (matches PlayerCharacter coordinate misc fields). */
	public final IntComponentArray posX = new IntComponentArray();
	public final IntComponentArray posY = new IntComponentArray();
	public final IntComponentArray posZ = new IntComponentArray();

	/** Facing. */
	public final IntComponentArray orientation = new IntComponentArray();
	public final IntComponentArray tilt = new IntComponentArray();

	/** Movement / stance status bitfield (kneeling, walking, etc.). */
	public final IntComponentArray status = new IntComponentArray();

	/** Health. Not yet populated by PlayerCharacter &mdash; placeholder for future systems. */
	public final IntComponentArray health = new IntComponentArray();
	public final IntComponentArray maxHealth = new IntComponentArray();

	/** Character appearance model ids. */
	public final IntComponentArray modelHead = new IntComponentArray();
	public final IntComponentArray modelTorso = new IntComponentArray();
	public final IntComponentArray modelLeg = new IntComponentArray();
	public final IntComponentArray modelHair = new IntComponentArray();
	public final IntComponentArray modelBeard = new IntComponentArray();
	public final IntComponentArray textureHead = new IntComponentArray();
	public final IntComponentArray textureTorso = new IntComponentArray();
	public final IntComponentArray textureLeg = new IntComponentArray();

	/** Class + profession + faction. */
	public final IntComponentArray clazz = new IntComponentArray();
	public final IntComponentArray profession = new IntComponentArray();
	public final IntComponentArray faction = new IntComponentArray();

	/**
	 * Skill block. One instance per entity holds all 5 skill levels / points and all
	 * 46 subskill levels. Cheaper than 101 separate sparse sets.
	 */
	public final ComponentArray<SkillBlock> skills = new ComponentArray<>();

	/**
	 * Inventory container ids. One instance per entity: F2 inventory, gogu, QB.
	 */
	public final ComponentArray<InventoryIds> inventory = new ComponentArray<>();

	/**
	 * Skill block data. Plain POJO &mdash; no behavior, no inheritance.
	 */
	public static final class SkillBlock {
		public final int[] skillLvl = new int[PlayerCharacter.SKILLS.length];
		public final int[] skillPts = new int[PlayerCharacter.SKILLS.length];
		public final int[] subskillLvl = new int[PlayerCharacter.SUBSKILLS.length];
	}

	/**
	 * Inventory container handles. Plain POJO.
	 */
	public static final class InventoryIds {
		public int f2ContainerId;
		public int goguContainerId;
		public int qbContainerId;

		public InventoryIds() { }

		public InventoryIds(int f2, int gogu, int qb) {
			this.f2ContainerId = f2;
			this.goguContainerId = gogu;
			this.qbContainerId = qb;
		}
	}
}
