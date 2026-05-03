package server.gameserver.npc;

import server.gameserver.WorldMessageBus;

/**
 * Cross-player intent: a mob's state changed and the new state must
 * be broadcast to nearby clients via {@link MobStateBroadcast}.
 *
 * <p>Producers: {@link MobManager#setState} (player damage events,
 * AI tick transitions, despawn/respawn), and tests that drive a mob
 * deterministically through its state machine.
 *
 * <p>Consumer: {@link MobManager} (registered on the bus by
 * {@link MobManager#installBusHandlers}). Runs on the world tick
 * thread, emits one {@link MobStateBroadcast} to each player in the
 * mob's zone.
 *
 * <p>Phase 3 ships this intent as zone-scoped (not global) so that
 * once per-zone ticks land in a future phase the bus can route mob
 * traffic without revisiting every consumer. For now the world tick
 * scheduler still drains zone -1 only, so producers post with
 * {@code zoneId = -1} until the tick split lands.
 */
public final class MobStateChangeIntent implements WorldMessageBus.Intent {

    public final int     npcId;
    public final int     zoneId;
    public final MobState state;
    public final int     flagsByte;
    public final float   altitude;
    public final int     targetId;

    public MobStateChangeIntent(int npcId, int zoneId, MobState state,
                                 int flagsByte, float altitude,
                                 int targetId) {
        this.npcId    = npcId;
        this.zoneId   = zoneId;
        this.state    = state;
        this.flagsByte = flagsByte;
        this.altitude = altitude;
        this.targetId = targetId;
    }

    @Override
    public int zoneId() {
        // Until per-zone ticks land, run all mob events on the
        // global queue so the existing WorldTickScheduler picks
        // them up. Re-route to the real zoneId once the per-zone
        // tick split happens.
        return -1;
    }
}
