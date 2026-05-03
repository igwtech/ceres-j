package server.gameserver.team;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

/** Unit tests for {@link Group} state mutations. */
public class GroupTest {

    @Test
    public void newGroupHasLeaderAsFirstMember() {
        Group g = new Group(1, 0x100);
        assertEquals(0x100, g.leaderUid());
        assertEquals(1, g.size());
        assertTrue(g.contains(0x100));
    }

    @Test
    public void addMemberAppendsAndIsIdempotent() {
        Group g = new Group(1, 0x100);
        assertTrue(g.addMember(0x200));
        // re-add returns false
        assertFalse(g.addMember(0x200));
        assertEquals(2, g.size());
    }

    @Test
    public void addMemberRespectsMaxMembers() {
        Group g = new Group(1, 1);
        for (int i = 2; i <= Group.MAX_MEMBERS; i++) {
            assertTrue("add member " + i, g.addMember(i));
        }
        // 9th member should be rejected.
        assertFalse(g.addMember(99));
        assertEquals(Group.MAX_MEMBERS, g.size());
    }

    @Test
    public void removeMemberReturnsFalseForUnknown() {
        Group g = new Group(1, 0x100);
        assertFalse(g.removeMember(0x999));
    }

    @Test
    public void removeMemberClearsLeaderRoleAndPromotesNext() {
        Group g = new Group(1, 0x100);
        g.addMember(0x200);
        g.addMember(0x300);
        // leader (0x100) leaves — 0x200 should become leader.
        assertTrue(g.removeMember(0x100));
        assertEquals(0x200, g.leaderUid());
        assertEquals(2, g.size());
    }

    @Test
    public void removeOnlyMemberLeavesGroupEmpty() {
        Group g = new Group(1, 0x100);
        assertTrue(g.removeMember(0x100));
        assertTrue(g.isEmpty());
        assertEquals(-1, g.leaderUid());
    }

    @Test
    public void snapshotPreservesInsertionOrderAndRoles() {
        Group g = new Group(1, 0x100);
        g.addMember(0x200);
        g.addMember(0x300);
        List<int[]> snap = g.snapshot();
        assertEquals(3, snap.size());
        assertEquals(0x100, snap.get(0)[0]);
        assertEquals(Group.ROLE_LEADER, snap.get(0)[1]);
        assertEquals(0x200, snap.get(1)[0]);
        assertEquals(Group.ROLE_MEMBER, snap.get(1)[1]);
        assertEquals(0x300, snap.get(2)[0]);
        assertEquals(Group.ROLE_MEMBER, snap.get(2)[1]);
    }

    @Test
    public void memberUidsListExcludesNothing() {
        Group g = new Group(1, 0x100);
        g.addMember(0x200);
        List<Integer> uids = g.memberUids();
        assertEquals(2, uids.size());
        assertTrue(uids.contains(0x100));
        assertTrue(uids.contains(0x200));
    }

    @Test
    public void containsAndSize() {
        Group g = new Group(1, 0x100);
        assertTrue(g.contains(0x100));
        assertFalse(g.contains(0x200));
        assertEquals(1, g.size());
        g.addMember(0x200);
        assertTrue(g.contains(0x200));
        assertEquals(2, g.size());
    }
}
