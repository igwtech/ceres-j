package server.gameserver.npc;

import server.gameserver.WorldMessageBus;

/**
 * Cross-player intent: the pilot of a drone has updated its
 * position. Posted by {@code DroneControlPacket.execute()} on the
 * UDP-receive thread, consumed on the world tick thread (registered
 * by a future {@code DroneManager} in Phase 3).
 *
 * <p>The intent is zone-scoped — drones live in the player's current
 * zone, so consumers should run on the per-zone tick. Until per-zone
 * ticks land in a later phase, this intent uses zone -1 (global) so
 * the existing {@link server.gameserver.WorldTickScheduler} drains it.
 */
public final class DroneControlIntent implements WorldMessageBus.Intent {

    /** UID of the player piloting the drone (sender of the C->S
     *  packet — needed for permission checks and audit). */
    public final int pilotUid;
    public final int droneId;
    public final float posX, posY, posZ;
    /** Opaque 20-byte trailer; preserve so the eventual S->C echo
     *  can mirror the client's bytes exactly. */
    public final byte[] tail;

    public DroneControlIntent(int pilotUid, int droneId,
                               float x, float y, float z, byte[] tail) {
        this.pilotUid = pilotUid;
        this.droneId = droneId;
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.tail = tail;
    }

    @Override
    public int zoneId() { return -1; } // global until per-zone ticks
}
