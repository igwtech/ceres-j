package server.gameserver.npc;

import server.gameserver.WorldMessageBus;

/**
 * Cross-player intent: a player took damage from a specific attacker
 * (typically a mob, but can also be another player or environmental
 * source).
 *
 * <p>Producer: mob attack resolver, weapon-fire handler that hits a
 * player, fall-damage tick — anything that wants to damage a player
 * via the bus rather than calling
 * {@link server.gameserver.Player#applyDamage} directly. Going through
 * the bus keeps the damage path serialised on the world tick thread
 * (no concurrent mutations of player HP) and lets future
 * subsystems — armour mitigation, PvP-range checks, friendly-fire
 * filtering — register as bus middleware without touching every
 * producer.
 *
 * <p>Consumer: {@link PlayerDamageDispatcher} (registered at
 * {@link PlayerDamageDispatcher#installBusHandlers(WorldMessageBus)}).
 *
 * <p>Bus scope is global ({@code zoneId == -1}) so the existing
 * {@link server.gameserver.WorldTickScheduler} drains it. Once
 * per-zone ticks land, the scope can narrow.
 */
public final class PlayerDamageIntent implements WorldMessageBus.Intent {

    public final int   victimUid;
    /** Attacker entity id — for a mob attack this is the mob's
     *  mapID; for PvP this is the player UID. The receiving handler
     *  passes it through to {@link server.gameserver.Player#applyDamage}
     *  so the death packet's {@code attackerId} field matches retail. */
    public final int   attackerId;
    public final float amount;
    /** Damage type byte — {@code 0x0a} energy / {@code 0x00} physical
     *  per the existing {@code DamageEvent} packet shape. */
    public final int   dmgType;

    public PlayerDamageIntent(int victimUid, int attackerId,
                                float amount, int dmgType) {
        this.victimUid  = victimUid;
        this.attackerId = attackerId;
        this.amount     = amount;
        this.dmgType    = dmgType;
    }

    @Override
    public int zoneId() { return -1; }
}
