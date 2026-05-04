package server.gameserver.npc;

import server.gameserver.WorldMessageBus;

/**
 * Cross-player intent: a mob's HP just hit 0 and the surrounding
 * subsystems need to react — body removal, loot spawn (Phase 5
 * follow-up), kill credit / XP attribution (Phase 7 missions),
 * faction reputation deltas, etc.
 *
 * <p>Producer: {@link MobManager#dispatchDamage} when the post-
 * damage HP check shows the mob is dead. The same dispatch path
 * also marks the {@link MobManager.Snapshot} as
 * {@link MobState#TRANSITION} so the AI / attack tickers stop
 * doing anything with the corpse.
 *
 * <p>Consumer: {@link MobDeathHandler}. v1 just removes the NPC
 * instance from its {@link server.gameserver.Zone}; loot drops
 * and the {@code 0x03/0x26 RemoveWorldItem} client-side packet
 * land in follow-up commits.
 *
 * <p>{@code zoneIdField} comes verbatim from the original
 * {@link MobDamageIntent} that triggered the kill — let the death
 * handler look up the right zone without scanning every zone.
 */
public final class MobDeathIntent implements WorldMessageBus.Intent {

    public final int npcId;
    /** Zone the mob lived in (informational; bus is global). */
    public final int zoneIdField;
    /** UID of whoever landed the killing blow — propagated to
     *  future XP / kill-credit subsystems. {@code 0} for
     *  environmental kills (fall damage, scripted death). */
    public final int killerUid;

    public MobDeathIntent(int npcId, int zoneIdField, int killerUid) {
        this.npcId       = npcId;
        this.zoneIdField = zoneIdField;
        this.killerUid   = killerUid;
    }

    @Override
    public int zoneId() { return -1; }
}
