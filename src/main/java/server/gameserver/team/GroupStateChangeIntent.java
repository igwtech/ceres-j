package server.gameserver.team;

import server.gameserver.WorldMessageBus;

/**
 * Cross-player intent: a group's composition changed and every
 * affected member needs a {@code 0x8388} push.
 *
 * <p>Producer: {@link GroupManager#createGroup} /
 * {@link GroupManager#addMember} / {@link GroupManager#removeMember}
 * / {@link GroupManager#disband} — these post the intent right
 * after mutating the in-memory state, then return.
 *
 * <p>Consumer: {@link GroupManager} (registered via
 * {@link GroupManager#installBusHandlers}). Runs on the world tick,
 * pulls the affected members + change kind, and emits one
 * {@link server.gameserver.packets.server_tcp.TeamEvent8388} per
 * affected member.
 *
 * <p>Bus scope is global ({@code zoneId == -1}) for now so the
 * existing {@link server.gameserver.WorldTickScheduler} drains it.
 * Group state lives outside any single zone's tick.
 */
public final class GroupStateChangeIntent implements WorldMessageBus.Intent {

    /** Kind of change that just happened. */
    public enum Kind {
        CREATED,        // group just created with leader (no other members)
        MEMBER_ADDED,   // an extra member was added
        MEMBER_REMOVED, // a member left
        DISBANDED       // group dissolved (last member left or kicked all)
    }

    public final int    groupId;
    public final Kind   kind;
    /** UID directly involved in the change (member added, removed,
     *  or — for CREATED — the leader). */
    public final int    subjectUid;
    /** Snapshot of the group's members AFTER the change. For
     *  DISBANDED, this is the list as it was at disband time so
     *  recipients can be notified. */
    public final java.util.List<Integer> postChangeMembers;

    public GroupStateChangeIntent(int groupId, Kind kind, int subjectUid,
                                   java.util.List<Integer> postChangeMembers) {
        this.groupId   = groupId;
        this.kind      = kind;
        this.subjectUid = subjectUid;
        this.postChangeMembers = (postChangeMembers == null)
                ? java.util.Collections.emptyList()
                : java.util.Collections.unmodifiableList(
                        new java.util.ArrayList<>(postChangeMembers));
    }

    @Override
    public int zoneId() { return -1; }
}
