package server.gameserver.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory state for a single team / party.
 *
 * <p>Members are kept in insertion order (so the leader — added
 * first — comes first) and tagged with a role byte (currently
 * {@link #ROLE_LEADER} or {@link #ROLE_MEMBER}; future PvP / mission
 * roles can extend the byte enum without changing the schema). Role
 * promotion is a single-byte mutation; no full reshuffling needed.
 *
 * <p>Mutation helpers return {@code true} when the call actually
 * changed state — the caller (typically {@link GroupManager}) uses
 * that to decide whether to post a broadcast intent.
 *
 * <p>This class is not thread-safe in isolation; serialise mutations
 * through {@link GroupManager} which holds a single
 * {@code ConcurrentHashMap} and uses {@code synchronized} on
 * individual {@link Group} instances when needed.
 */
public final class Group {

    /** Role byte stored on each member entry. */
    public static final int ROLE_LEADER = 0x00;
    public static final int ROLE_MEMBER = 0x01;
    /** Hard cap on team size — matches retail's 8-player team
     *  (XP-share scaling above this would break). */
    public static final int MAX_MEMBERS = 8;

    public final int groupId;
    /** Insertion-ordered map of memberUid → role byte. */
    private final Map<Integer, Integer> members = new LinkedHashMap<>();

    public Group(int groupId, int leaderUid) {
        this.groupId = groupId;
        this.members.put(leaderUid, ROLE_LEADER);
    }

    public synchronized boolean addMember(int memberUid) {
        if (members.containsKey(memberUid)) return false;
        if (members.size() >= MAX_MEMBERS) return false;
        members.put(memberUid, ROLE_MEMBER);
        return true;
    }

    public synchronized boolean removeMember(int memberUid) {
        Integer role = members.remove(memberUid);
        if (role == null) return false;
        // If the leader leaves and there's at least one member, promote
        // the next-in-order to leader so the team isn't headless.
        if (role == ROLE_LEADER && !members.isEmpty()) {
            Map.Entry<Integer, Integer> first =
                    members.entrySet().iterator().next();
            first.setValue(ROLE_LEADER);
        }
        return true;
    }

    public synchronized int leaderUid() {
        for (Map.Entry<Integer, Integer> e : members.entrySet()) {
            if (e.getValue() == ROLE_LEADER) return e.getKey();
        }
        return -1;
    }

    public synchronized boolean contains(int memberUid) {
        return members.containsKey(memberUid);
    }

    public synchronized int size() { return members.size(); }

    public synchronized boolean isEmpty() { return members.isEmpty(); }

    /** Defensive snapshot of (uid, role) entries in leader-first order. */
    public synchronized List<int[]> snapshot() {
        List<int[]> out = new ArrayList<>(members.size());
        for (Map.Entry<Integer, Integer> e : members.entrySet()) {
            out.add(new int[]{e.getKey(), e.getValue()});
        }
        return Collections.unmodifiableList(out);
    }

    /** Defensive snapshot of just the member UIDs. */
    public synchronized List<Integer> memberUids() {
        return new ArrayList<>(members.keySet());
    }
}
