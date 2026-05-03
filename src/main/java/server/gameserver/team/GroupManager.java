package server.gameserver.team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.PlayerManager;
import server.gameserver.WorldMessageBus;
import server.gameserver.packets.server_tcp.TeamEvent8388;
import server.tools.Out;

/**
 * In-memory team / party state plus the consumer side of
 * {@link GroupStateChangeIntent} fan-out.
 *
 * <p>Two indices are kept synchronised: {@code groupId → Group} and
 * {@code memberUid → groupId}. The first lets us iterate the team
 * for broadcast; the second lets a player who ran {@code !leave} or
 * disconnected resolve "what group was I in?" in O(1) without
 * scanning every group.
 *
 * <p>Mutators always:
 *
 * <ol>
 *   <li>Acquire the {@link Group} synchronisation monitor via the
 *       methods on {@link Group}.</li>
 *   <li>Update both indices atomically (the
 *       {@code memberUid → groupId} map is updated under the same
 *       monitor as the {@link Group} that owns the member).</li>
 *   <li>Post a {@link GroupStateChangeIntent} so the registered
 *       handler can emit {@link TeamEvent8388} broadcasts on the
 *       world tick.</li>
 * </ol>
 *
 * <p>Test seam: {@link PacketSink} can be swapped so unit tests
 * verify dispatch logic without going through real TCP sockets.
 * Same pattern as {@link server.gameserver.npc.MobManager} and
 * {@link server.gameserver.npc.DroneManager}.
 */
public final class GroupManager {

    /** Strategy for emitting one team-event packet to a recipient. */
    @FunctionalInterface
    public interface PacketSink {
        void send(Player recipient, GroupStateChangeIntent intent,
                   int eventType, byte[] payload);
    }

    /** Strategy for finding a Player by UID. Default scans
     *  {@link PlayerManager#getOnlinePlayers()}. */
    @FunctionalInterface
    public interface PlayerByUidLookup {
        Player findByUid(int uid);
    }

    private static final Map<Integer, Group> GROUPS = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> MEMBER_INDEX = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_GROUP_ID = new AtomicInteger(1);

    private static PacketSink       packetSink = GroupManager::defaultSend;
    private static PlayerByUidLookup uidLookup = GroupManager::defaultFindByUid;

    private GroupManager() {}

    public static void installBusHandlers(WorldMessageBus bus) {
        bus.registerHandler(GroupStateChangeIntent.class,
                GroupManager::dispatchStateChange);
    }

    // ─── Public mutators ───────────────────────────────────────────

    /** Create a new group with {@code leaderUid} as its leader.
     *  Returns the new group's id, or -1 if the leader is already
     *  in another group. */
    public static int createGroup(WorldMessageBus bus, int leaderUid) {
        if (MEMBER_INDEX.containsKey(leaderUid)) return -1;
        int gid = NEXT_GROUP_ID.getAndIncrement();
        Group g = new Group(gid, leaderUid);
        GROUPS.put(gid, g);
        MEMBER_INDEX.put(leaderUid, gid);
        post(bus, new GroupStateChangeIntent(gid,
                GroupStateChangeIntent.Kind.CREATED,
                leaderUid, g.memberUids()));
        return gid;
    }

    /** Add {@code memberUid} to {@code groupId}. Returns true on
     *  success. Fails if the group doesn't exist, the member is
     *  already in any group, or the group is at {@link Group#MAX_MEMBERS}. */
    public static boolean addMember(WorldMessageBus bus, int groupId,
                                     int memberUid) {
        Group g = GROUPS.get(groupId);
        if (g == null) return false;
        if (MEMBER_INDEX.containsKey(memberUid)) return false;
        if (!g.addMember(memberUid)) return false;
        MEMBER_INDEX.put(memberUid, groupId);
        post(bus, new GroupStateChangeIntent(groupId,
                GroupStateChangeIntent.Kind.MEMBER_ADDED,
                memberUid, g.memberUids()));
        return true;
    }

    /** Remove {@code memberUid} from whatever group they were in.
     *  If the group becomes empty, it's disbanded. Returns true if
     *  the member was actually removed. */
    public static boolean removeMember(WorldMessageBus bus, int memberUid) {
        Integer gid = MEMBER_INDEX.remove(memberUid);
        if (gid == null) return false;
        Group g = GROUPS.get(gid);
        if (g == null) return false;
        // Capture the group state BEFORE the removal so the intent's
        // postChangeMembers can include the leaving member as a
        // recipient. We separately remove them from the broadcast
        // logic in dispatchStateChange.
        List<Integer> beforeRemoval = g.memberUids();
        g.removeMember(memberUid);
        if (g.isEmpty()) {
            GROUPS.remove(gid);
            post(bus, new GroupStateChangeIntent(gid,
                    GroupStateChangeIntent.Kind.DISBANDED,
                    memberUid, beforeRemoval));
        } else {
            post(bus, new GroupStateChangeIntent(gid,
                    GroupStateChangeIntent.Kind.MEMBER_REMOVED,
                    memberUid, beforeRemoval));
        }
        return true;
    }

    /** Disband {@code groupId}: remove every member and post a
     *  single DISBANDED intent. */
    public static boolean disband(WorldMessageBus bus, int groupId) {
        Group g = GROUPS.remove(groupId);
        if (g == null) return false;
        List<Integer> members = g.memberUids();
        for (Integer uid : members) MEMBER_INDEX.remove(uid);
        post(bus, new GroupStateChangeIntent(groupId,
                GroupStateChangeIntent.Kind.DISBANDED,
                g.leaderUid(), members));
        return true;
    }

    // ─── Read-only accessors ───────────────────────────────────────

    public static Group getGroup(int groupId)  { return GROUPS.get(groupId); }
    public static Integer groupIdOf(int memberUid) {
        return MEMBER_INDEX.get(memberUid);
    }
    public static int trackedGroups() { return GROUPS.size(); }

    // ─── Bus handler ───────────────────────────────────────────────

    /** Public for tests: dispatch a single state-change intent. */
    public static void dispatchStateChange(GroupStateChangeIntent intent) {
        if (intent == null) return;
        for (Integer recipientUid : intent.postChangeMembers) {
            if (recipientUid == null) continue;
            Player recipient = uidLookup.findByUid(recipientUid);
            if (recipient == null) continue;
            int eventType = eventTypeFor(intent.kind);
            byte[] payload = payloadFor(intent, recipientUid);
            try {
                packetSink.send(recipient, intent, eventType, payload);
            } catch (Exception e) {
                Out.writeln(Out.Error,
                    "GroupManager: send failed: " + e.getMessage());
            }
        }
    }

    /** Map a state-change kind to the wire {@code event_type} field
     *  in {@link TeamEvent8388}. The mapping is informed but not
     *  fully verified — Phase 4 will refine when more retail samples
     *  with marker pairs land. */
    static int eventTypeFor(GroupStateChangeIntent.Kind kind) {
        switch (kind) {
        case CREATED:        return TeamEvent8388.EVENT_TYPE_41;
        case MEMBER_ADDED:   return TeamEvent8388.EVENT_TYPE_43;
        case MEMBER_REMOVED: return TeamEvent8388.EVENT_TYPE_42;
        case DISBANDED:      return TeamEvent8388.EVENT_TYPE_42;
        default: throw new IllegalArgumentException("kind " + kind);
        }
    }

    /** Build the payload bytes appropriate for the chosen event type. */
    static byte[] payloadFor(GroupStateChangeIntent intent, int recipientUid) {
        switch (intent.kind) {
        case CREATED:
        case MEMBER_REMOVED:
        case DISBANDED:
            // Self-uid payload (4 bytes) — matches retail 0x41/0x42.
            return leBytes(recipientUid);
        case MEMBER_ADDED:
            // 9-byte (recipient_uid, role, member_uid) — matches retail 0x43.
            byte[] p = new byte[9];
            writeLE32At(p, 0, recipientUid);
            // Role byte — leader if this recipient leads, else member.
            Group g = GROUPS.get(intent.groupId);
            int role = (g != null && g.leaderUid() == recipientUid)
                    ? Group.ROLE_LEADER : Group.ROLE_MEMBER;
            p[4] = (byte) (role & 0xff);
            writeLE32At(p, 5, intent.subjectUid);
            return p;
        default:
            return new byte[0];
        }
    }

    private static byte[] leBytes(int v) {
        return new byte[]{
                (byte) v,
                (byte) (v >> 8),
                (byte) (v >> 16),
                (byte) (v >> 24)};
    }

    private static void writeLE32At(byte[] dst, int off, int v) {
        dst[off]     = (byte) v;
        dst[off + 1] = (byte) (v >> 8);
        dst[off + 2] = (byte) (v >> 16);
        dst[off + 3] = (byte) (v >> 24);
    }

    // ─── Default strategies ────────────────────────────────────────

    private static Player defaultFindByUid(int uid) {
        for (Player p : PlayerManager.getOnlinePlayers()) {
            if (p == null) continue;
            PlayerCharacter pc = p.getCharacter();
            if (pc == null) continue;
            if (pc.getMisc(PlayerCharacter.MISC_ID) == uid) return p;
        }
        return null;
    }

    private static void defaultSend(Player recipient,
                                     GroupStateChangeIntent intent,
                                     int eventType, byte[] payload) {
        PlayerCharacter pc = recipient.getCharacter();
        int targetUid = (pc == null) ? 0
                : pc.getMisc(PlayerCharacter.MISC_ID);
        recipient.send(new TeamEvent8388(targetUid, eventType, payload));
    }

    private static void post(WorldMessageBus bus, GroupStateChangeIntent intent) {
        if (bus == null) return;
        bus.post(intent);
    }

    // ─── Test seams ────────────────────────────────────────────────

    public static void setPacketSinkForTesting(PacketSink s) {
        packetSink = (s == null) ? GroupManager::defaultSend : s;
    }
    public static void setUidLookupForTesting(PlayerByUidLookup l) {
        uidLookup = (l == null) ? GroupManager::defaultFindByUid : l;
    }
    public static void resetForTesting() {
        GROUPS.clear();
        MEMBER_INDEX.clear();
        NEXT_GROUP_ID.set(1);
        packetSink = GroupManager::defaultSend;
        uidLookup  = GroupManager::defaultFindByUid;
    }
}
