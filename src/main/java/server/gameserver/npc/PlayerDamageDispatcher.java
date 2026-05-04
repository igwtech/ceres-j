package server.gameserver.npc;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.PlayerManager;
import server.gameserver.WorldMessageBus;
import server.tools.Out;

/**
 * Bus consumer for {@link PlayerDamageIntent}. Resolves the victim
 * UID to a live {@link Player} and forwards to
 * {@link Player#applyDamage}, which already owns the existing damage
 * sequence (DamageTick → PoolUpdate → DamageEvent → optional
 * PlayerDeath + respawn schedule).
 *
 * <p>Pattern matches {@link MobManager} / {@link DroneManager}: a
 * test seam for {@code VictimLookup} lets unit tests verify dispatch
 * without booting {@link PlayerManager}.
 */
public final class PlayerDamageDispatcher {

    /** Strategy for resolving a player UID to a {@link Player}.
     *  Default scans {@link PlayerManager#getOnlinePlayers()}. */
    @FunctionalInterface
    public interface VictimLookup {
        Player findByUid(int uid);
    }

    /** Strategy for actually applying the damage — default calls
     *  {@link Player#applyDamage(float, int)}. Tests inject a fake
     *  to capture the call without going through the real packet
     *  pipeline. */
    @FunctionalInterface
    public interface DamageApplier {
        void apply(Player victim, float amount, int attackerId, int dmgType);
    }

    private static VictimLookup victimLookup =
            PlayerDamageDispatcher::defaultFindByUid;
    private static DamageApplier damageApplier =
            PlayerDamageDispatcher::defaultApply;

    private PlayerDamageDispatcher() {}

    public static void installBusHandlers(WorldMessageBus bus) {
        bus.registerHandler(PlayerDamageIntent.class,
                PlayerDamageDispatcher::dispatch);
    }

    /** Public for tests: dispatch a single damage intent. */
    public static void dispatch(PlayerDamageIntent intent) {
        if (intent == null) return;
        Player victim = victimLookup.findByUid(intent.victimUid);
        if (victim == null) {
            Out.writeln(Out.Warning,
                "PlayerDamageDispatcher: victim uid="
                + Integer.toHexString(intent.victimUid) + " not online");
            return;
        }
        try {
            damageApplier.apply(victim, intent.amount,
                    intent.attackerId, intent.dmgType);
        } catch (Exception e) {
            Out.writeln(Out.Error,
                "PlayerDamageDispatcher: applyDamage threw "
                + e.getMessage());
        }
    }

    // ─── Default strategies ─────────────────────────────────────────

    private static Player defaultFindByUid(int uid) {
        for (Player pl : PlayerManager.getOnlinePlayers()) {
            if (pl == null) continue;
            PlayerCharacter pc = pl.getCharacter();
            if (pc == null) continue;
            if (pc.getMisc(PlayerCharacter.MISC_ID) == uid) return pl;
        }
        return null;
    }

    private static void defaultApply(Player victim, float amount,
                                      int attackerId, int dmgType) {
        // dmgType currently isn't a parameter on Player.applyDamage;
        // it's hardcoded to 0x0a inside applyDamage. Once we wire
        // armour mitigation / damage-type resistance, this dispatcher
        // is the right place to apply it before forwarding.
        victim.applyDamage(amount, attackerId);
    }

    // ─── Test seams ─────────────────────────────────────────────────

    public static void setVictimLookupForTesting(VictimLookup l) {
        victimLookup = (l == null)
                ? PlayerDamageDispatcher::defaultFindByUid : l;
    }

    public static void setDamageApplierForTesting(DamageApplier a) {
        damageApplier = (a == null)
                ? PlayerDamageDispatcher::defaultApply : a;
    }

    public static void resetForTesting() {
        victimLookup  = PlayerDamageDispatcher::defaultFindByUid;
        damageApplier = PlayerDamageDispatcher::defaultApply;
    }
}
