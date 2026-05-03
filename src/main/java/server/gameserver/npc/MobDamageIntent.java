package server.gameserver.npc;

import server.gameserver.WorldMessageBus;

/**
 * Cross-player intent: a mob took damage from a specific attacker.
 *
 * <p>Producer: weapon-fire handler / dot-tick / ability impact
 * resolver — anything that mutates an NPC's HP. The producer
 * applies the damage and posts the intent; this manager handles
 * the AI consequences (forced aggro, death transition, broadcast).
 *
 * <p>Consumer: {@link MobManager#dispatchDamage}.
 *
 * <p>Bus scope is global ({@code zoneId == -1}) so the existing
 * {@link server.gameserver.WorldTickScheduler} drains it. Once
 * per-zone ticks land in a future phase, the {@code zoneId}
 * field will route the intent to the right zone tick.
 */
public final class MobDamageIntent implements WorldMessageBus.Intent {

    public final int npcId;
    /** Zone the mob lives in. Currently informational; once
     *  per-zone ticks land, the bus will route on this. */
    public final int zoneIdField;
    public final int attackerUid;
    public final int amount;

    public MobDamageIntent(int npcId, int zoneIdField,
                            int attackerUid, int amount) {
        this.npcId       = npcId;
        this.zoneIdField = zoneIdField;
        this.attackerUid = attackerUid;
        this.amount      = amount;
    }

    @Override
    public int zoneId() { return -1; }
}
