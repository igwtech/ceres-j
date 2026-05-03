package server.gameserver.npc;

import java.util.Collections;
import java.util.List;

/**
 * Pure-logic mob AI policy. No Zone, no PlayerManager, no bus, no
 * randomness — just: given a snapshot of the world relevant to one
 * mob, return the next state and target.
 *
 * <p>Drives the state transitions broadcast by {@link MobManager}.
 * The mechanism that wires {@link #tick} into the world tick lives
 * in a follow-up commit; this commit keeps the state machine
 * testable in isolation.
 *
 * <h3>State transition table (v1 — minimum viable)</h3>
 *
 * <pre>
 *   IDLE       + player within aggroRange     → COMBAT (target = nearest)
 *   IDLE       + no players                   → IDLE
 *   COMBAT     + target within deaggroRange   → COMBAT (same target)
 *   COMBAT     + target out / gone, other     → COMBAT (target = nearest)
 *                  player in aggroRange
 *   COMBAT     + no players within deaggro    → TRANSITION
 *   TRANSITION + any player in aggroRange     → COMBAT (target = nearest)
 *   TRANSITION + no players                   → IDLE
 * </pre>
 *
 * <p><strong>Why TRANSITION as the deaggro waypoint?</strong>  In
 * the retail evidence, mobs leaving combat broadcast {@code 0x70}
 * for one tick before returning to {@code 0x75}. Modelling this
 * lets mobs match the observed wire trace.
 *
 * <p>Hysteresis: {@code aggroRange} (typically 20 m) is strictly
 * less than {@code deaggroRange} (typically 30 m). A mob that just
 * aggro'd because a player crossed 20 m won't deaggro until the
 * player crosses 30 m, preventing flicker at the edge.
 *
 * <p>v1 explicitly does NOT model: pathfinding, wander, ranged
 * weapon lead, line-of-sight, threat tables, faction, mob-mob
 * cooperation, fleeing, despawn timers, or death. Each of those is
 * a follow-up phase.
 */
public final class MobAI {

    public static final int NO_TARGET = MobDataDecoder.NO_TARGET;

    /** Snapshot of one player as seen by the AI. */
    public static final class PlayerSnapshot {
        public final int   uid;
        public final float posX, posY, posZ;
        public final boolean alive;
        public PlayerSnapshot(int uid, float x, float y, float z) {
            this(uid, x, y, z, true);
        }
        public PlayerSnapshot(int uid, float x, float y, float z, boolean alive) {
            this.uid  = uid;
            this.posX = x;
            this.posY = y;
            this.posZ = z;
            this.alive = alive;
        }
    }

    /** Input to a single AI tick. */
    public static final class Input {
        public final int       npcId;
        public final float     posX, posY, posZ;
        public final MobState  currentState;
        public final int       currentTargetId;
        public final List<PlayerSnapshot> players;
        public final float     aggroRange;
        public final float     deaggroRange;

        public Input(int npcId, float x, float y, float z,
                     MobState currentState, int currentTargetId,
                     List<PlayerSnapshot> players,
                     float aggroRange, float deaggroRange) {
            if (currentState == null) currentState = MobState.IDLE;
            if (deaggroRange < aggroRange) {
                throw new IllegalArgumentException(
                    "deaggroRange (" + deaggroRange + ") must be >= aggroRange ("
                    + aggroRange + ") to avoid flicker");
            }
            this.npcId       = npcId;
            this.posX        = x;
            this.posY        = y;
            this.posZ        = z;
            this.currentState = currentState;
            this.currentTargetId = currentTargetId;
            this.players     = (players == null)
                    ? Collections.emptyList()
                    : players;
            this.aggroRange  = aggroRange;
            this.deaggroRange = deaggroRange;
        }
    }

    /** Result of one AI tick. */
    public static final class Result {
        public final MobState newState;
        public final int      newTargetId;
        Result(MobState s, int t) { this.newState = s; this.newTargetId = t; }

        public boolean stateChanged(MobState prev) { return newState != prev; }
        public boolean targetChanged(int prev)     { return newTargetId != prev; }
    }

    private MobAI() {}

    /** Compute the next state given the current snapshot. Pure
     *  function — same input always produces same output. */
    public static Result tick(Input in) {
        if (in == null) {
            return new Result(MobState.IDLE, NO_TARGET);
        }
        // Quick lookups against the snapshot of nearby players.
        PlayerSnapshot nearestInAggro    = nearestAlive(in, in.aggroRange);
        PlayerSnapshot currentTarget     = findById(in.players, in.currentTargetId);
        boolean targetStillValid = currentTarget != null
                && currentTarget.alive
                && distance(currentTarget, in) <= in.deaggroRange;

        switch (in.currentState) {
        case IDLE:
        case RARE_72:
        case RARE_6F:
            // From any non-combat state, only thing that can happen
            // is "player got close enough to aggro".
            if (nearestInAggro != null) {
                return new Result(MobState.COMBAT, nearestInAggro.uid);
            }
            return new Result(MobState.IDLE, NO_TARGET);

        case COMBAT:
            if (targetStillValid) {
                // Stay locked on current target.
                return new Result(MobState.COMBAT, in.currentTargetId);
            }
            // Current target gone / dead / out of range. Look for
            // another aggro candidate within aggroRange.
            if (nearestInAggro != null) {
                return new Result(MobState.COMBAT, nearestInAggro.uid);
            }
            // No valid target. Drop to TRANSITION as a one-tick
            // waypoint so the wire trace matches retail.
            return new Result(MobState.TRANSITION, NO_TARGET);

        case TRANSITION:
            if (nearestInAggro != null) {
                return new Result(MobState.COMBAT, nearestInAggro.uid);
            }
            return new Result(MobState.IDLE, NO_TARGET);

        default:
            return new Result(MobState.IDLE, NO_TARGET);
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private static PlayerSnapshot nearestAlive(Input in, float maxRange) {
        PlayerSnapshot best = null;
        float bestDistSq = maxRange * maxRange;
        for (PlayerSnapshot p : in.players) {
            if (!p.alive) continue;
            float d2 = distanceSq(p, in);
            if (d2 <= bestDistSq) {
                bestDistSq = d2;
                best = p;
            }
        }
        return best;
    }

    private static PlayerSnapshot findById(List<PlayerSnapshot> players, int uid) {
        if (uid == NO_TARGET) return null;
        for (PlayerSnapshot p : players) {
            if (p.uid == uid) return p;
        }
        return null;
    }

    private static float distance(PlayerSnapshot p, Input in) {
        return (float) Math.sqrt(distanceSq(p, in));
    }

    private static float distanceSq(PlayerSnapshot p, Input in) {
        float dx = p.posX - in.posX;
        float dy = p.posY - in.posY;
        float dz = p.posZ - in.posZ;
        return dx * dx + dy * dy + dz * dz;
    }
}
