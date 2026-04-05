package server.ecs;

import server.database.items.ItemContainer;
import server.database.playerCharacters.PlayerCharacter;
import server.ecs.Components.InventoryIds;
import server.ecs.Components.SkillBlock;

/**
 * Bridge between the legacy {@link PlayerCharacter} data model and the new ECS
 * component arrays.
 *
 * <p>Migration is <em>not</em> rip-and-replace: old code keeps talking to
 * {@code PlayerCharacter}, new code (starting with the Movement POC) talks to the
 * ECS. This class provides the two-way copy so both representations stay in sync
 * until every subsystem has been ported.
 *
 * <p>{@link #materialize(Components, int, PlayerCharacter)} copies everything from
 * {@code PlayerCharacter} into the component arrays for a given entity index.
 * {@link #exportTo(Components, int, PlayerCharacter)} copies it back, ready for
 * persistence via the existing {@code PlayerCharacterManager.saveCharacter(pc)}.
 *
 * <p>Inventory containers are <em>referenced</em>, not copied: the container id is
 * stored in the ECS and the PlayerCharacter retains ownership of the concrete
 * container objects, which still house all item state. This keeps the existing
 * item pipeline intact while the ECS is bedded in.
 */
public final class PlayerCharacterBridge {

	private PlayerCharacterBridge() { }

	/**
	 * Copies all fields of {@code pc} into the component arrays at {@code entityIndex}.
	 * Existing values on the entity are overwritten.
	 */
	public static void materialize(Components c, int entityIndex, PlayerCharacter pc) {
		c.name.set(entityIndex, pc.getName());

		c.characterId.set(entityIndex, pc.getMisc(PlayerCharacter.MISC_ID));
		c.zoneId.set(entityIndex, pc.getMisc(PlayerCharacter.MISC_LOCATION));

		c.posX.set(entityIndex, pc.getMisc(PlayerCharacter.MISC_X_COORDINATE));
		c.posY.set(entityIndex, pc.getMisc(PlayerCharacter.MISC_Y_COORDINATE));
		c.posZ.set(entityIndex, pc.getMisc(PlayerCharacter.MISC_Z_COORDINATE));
		c.orientation.set(entityIndex, pc.getMisc(PlayerCharacter.MISC_ORIENTATION));
		c.tilt.set(entityIndex, pc.getMisc(PlayerCharacter.MISC_TILT));
		c.status.set(entityIndex, pc.getMisc(PlayerCharacter.MISC_STATUS));

		c.clazz.set(entityIndex, pc.getMisc(PlayerCharacter.MISC_CLASS));
		c.profession.set(entityIndex, pc.getMisc(PlayerCharacter.MISC_PROFESSION));
		c.faction.set(entityIndex, pc.getMisc(PlayerCharacter.MISC_FACTION));

		c.modelHead.set(entityIndex, pc.getMisc(PlayerCharacter.MODEL_HEAD));
		c.modelTorso.set(entityIndex, pc.getMisc(PlayerCharacter.MODEL_TORSO));
		c.modelLeg.set(entityIndex, pc.getMisc(PlayerCharacter.MODEL_LEG));
		c.modelHair.set(entityIndex, pc.getMisc(PlayerCharacter.MODEL_HAIR));
		c.modelBeard.set(entityIndex, pc.getMisc(PlayerCharacter.MODEL_BEARD));
		c.textureHead.set(entityIndex, pc.getMisc(PlayerCharacter.TEXTURE_HEAD));
		c.textureTorso.set(entityIndex, pc.getMisc(PlayerCharacter.TEXTURE_TORSO));
		c.textureLeg.set(entityIndex, pc.getMisc(PlayerCharacter.TEXTURE_LEG));

		SkillBlock sb = new SkillBlock();
		for (int i = 1; i < PlayerCharacter.SKILLS.length; i++) {
			sb.skillLvl[i] = pc.getSkillLVL(i);
			sb.skillPts[i] = pc.getSkillPtsRaw(i);
		}
		for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
			if (PlayerCharacter.SUBSKILLS[i] != null) {
				// Use raw subskill value, not getSubskillLVL() which has a hardcoded override.
				sb.subskillLvl[i] = pc.getSubskillLVLRaw(i);
			}
		}
		c.skills.set(entityIndex, sb);

		ItemContainer f2 = pc.getContainer(PlayerCharacter.PLAYERCONTAINER_F2);
		ItemContainer gogu = pc.getContainer(PlayerCharacter.PLAYERCONTAINER_GOGU);
		ItemContainer qb = pc.getContainer(PlayerCharacter.PLAYERCONTAINER_QB);
		c.inventory.set(entityIndex, new InventoryIds(
				f2 != null ? f2.getContainerID() : 0,
				gogu != null ? gogu.getContainerID() : 0,
				qb != null ? qb.getContainerID() : 0));
	}

	/**
	 * Copies component values at {@code entityIndex} back into {@code pc}. Skills
	 * and container ids are only written if the respective component is present so
	 * that partial ECS entities (e.g. NPCs that only have position) do not clobber
	 * PlayerCharacter fields.
	 */
	public static void exportTo(Components c, int entityIndex, PlayerCharacter pc) {
		String nm = c.name.get(entityIndex);
		if (nm != null) {
			pc.setName(nm);
		}

		if (c.characterId.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.MISC_ID, c.characterId.get(entityIndex));
		}
		if (c.zoneId.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.MISC_LOCATION, c.zoneId.get(entityIndex));
		}
		if (c.posX.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.MISC_X_COORDINATE, c.posX.get(entityIndex));
		}
		if (c.posY.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.MISC_Y_COORDINATE, c.posY.get(entityIndex));
		}
		if (c.posZ.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.MISC_Z_COORDINATE, c.posZ.get(entityIndex));
		}
		if (c.orientation.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.MISC_ORIENTATION, c.orientation.get(entityIndex));
		}
		if (c.tilt.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.MISC_TILT, c.tilt.get(entityIndex));
		}
		if (c.status.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.MISC_STATUS, c.status.get(entityIndex));
		}

		if (c.clazz.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.MISC_CLASS, c.clazz.get(entityIndex));
		}
		if (c.profession.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.MISC_PROFESSION, c.profession.get(entityIndex));
		}
		if (c.faction.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.MISC_FACTION, c.faction.get(entityIndex));
		}

		if (c.modelHead.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.MODEL_HEAD, c.modelHead.get(entityIndex));
		}
		if (c.modelTorso.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.MODEL_TORSO, c.modelTorso.get(entityIndex));
		}
		if (c.modelLeg.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.MODEL_LEG, c.modelLeg.get(entityIndex));
		}
		if (c.modelHair.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.MODEL_HAIR, c.modelHair.get(entityIndex));
		}
		if (c.modelBeard.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.MODEL_BEARD, c.modelBeard.get(entityIndex));
		}
		if (c.textureHead.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.TEXTURE_HEAD, c.textureHead.get(entityIndex));
		}
		if (c.textureTorso.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.TEXTURE_TORSO, c.textureTorso.get(entityIndex));
		}
		if (c.textureLeg.has(entityIndex)) {
			pc.setMisc(PlayerCharacter.TEXTURE_LEG, c.textureLeg.get(entityIndex));
		}

		SkillBlock sb = c.skills.get(entityIndex);
		if (sb != null) {
			for (int i = 1; i < PlayerCharacter.SKILLS.length; i++) {
				pc.setSkillLVL(i, sb.skillLvl[i]);
				pc.setSkillPTS(i, sb.skillPts[i]);
			}
			for (int i = 1; i < PlayerCharacter.SUBSKILLS.length; i++) {
				if (PlayerCharacter.SUBSKILLS[i] != null) {
					pc.setSubskillLVL(i, sb.subskillLvl[i]);
				}
			}
		}
	}
}
