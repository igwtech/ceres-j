package server.gameserver.team;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.WorldMessageBus;
import server.gameserver.packets.server_tcp.TeamEvent8388;

/**
 * Functional tests for {@link GroupManager}.
 *
 * <p>Captures every (recipient, eventType, payload) tuple sent
 * through the test sink so each test can assert exact wire-shape
 * expectations.
 */
public class GroupManagerTest {

    static final class CapturedSend {
        final Player recipient;
        final GroupStateChangeIntent intent;
        final int eventType;
        final byte[] payload;
        CapturedSend(Player r, GroupStateChangeIntent i, int e, byte[] p) {
            this.recipient = r;
            this.intent = i;
            this.eventType = e;
            this.payload = p;
        }
    }

    private List<CapturedSend> captured;

    private static Player makePlayer(int uid) {
        Account acc = new Account(uid);
        acc.setUsername("u" + uid);
        Player pl = new Player(acc);
        PlayerCharacter pc = new PlayerCharacter();
        pc.setName("p" + uid);
        pc.setMisc(PlayerCharacter.MISC_ID, uid);
        try {
            Field f = Player.class.getDeclaredField("pc");
            f.setAccessible(true);
            f.set(pl, pc);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return pl;
    }

    @Before
    public void setUp() {
        GroupManager.resetForTesting();
        captured = new ArrayList<>();
        GroupManager.setPacketSinkForTesting(
                (r, i, et, p) -> captured.add(new CapturedSend(r, i, et, p)));
    }

    @After
    public void tearDown() {
        GroupManager.resetForTesting();
    }

    // ─── Mutator semantics ─────────────────────────────────────────

    @Test
    public void createGroupAssignsIdAndIndexesLeader() {
        WorldMessageBus bus = new WorldMessageBus();
        int gid = GroupManager.createGroup(bus, 0x100);
        assertTrue(gid > 0);
        assertEquals(Integer.valueOf(gid), GroupManager.groupIdOf(0x100));
        Group g = GroupManager.getGroup(gid);
        assertNotNull(g);
        assertEquals(0x100, g.leaderUid());
        assertEquals(1, g.size());
        // CREATED intent posted.
        assertEquals(1, bus.pending(-1));
    }

    @Test
    public void createGroupRejectsLeaderAlreadyInGroup() {
        WorldMessageBus bus = new WorldMessageBus();
        GroupManager.createGroup(bus, 0x100);
        // Same leader can't create a second group.
        assertEquals(-1, GroupManager.createGroup(bus, 0x100));
    }

    @Test
    public void addMemberSucceedsOnExistingGroup() {
        WorldMessageBus bus = new WorldMessageBus();
        int gid = GroupManager.createGroup(bus, 0x100);
        bus.drain(-1);
        assertTrue(GroupManager.addMember(bus, gid, 0x200));
        Group g = GroupManager.getGroup(gid);
        assertTrue(g.contains(0x200));
        assertEquals(Integer.valueOf(gid), GroupManager.groupIdOf(0x200));
    }

    @Test
    public void addMemberRejectsExistingMembership() {
        WorldMessageBus bus = new WorldMessageBus();
        int gid1 = GroupManager.createGroup(bus, 0x100);
        int gid2 = GroupManager.createGroup(bus, 0x300);
        bus.drain(-1);
        // 0x300 is already in gid2; can't be added to gid1.
        assertFalse(GroupManager.addMember(bus, gid1, 0x300));
    }

    @Test
    public void addMemberRejectsMissingGroup() {
        WorldMessageBus bus = new WorldMessageBus();
        assertFalse(GroupManager.addMember(bus, 9999, 0x200));
    }

    @Test
    public void removeMemberDisbandsEmptyGroup() {
        WorldMessageBus bus = new WorldMessageBus();
        int gid = GroupManager.createGroup(bus, 0x100);
        bus.drain(-1);
        assertTrue(GroupManager.removeMember(bus, 0x100));
        assertNull(GroupManager.getGroup(gid));
        assertNull(GroupManager.groupIdOf(0x100));
    }

    @Test
    public void removeMemberKeepsGroupAliveWhenOthersRemain() {
        WorldMessageBus bus = new WorldMessageBus();
        int gid = GroupManager.createGroup(bus, 0x100);
        GroupManager.addMember(bus, gid, 0x200);
        bus.drain(-1);
        assertTrue(GroupManager.removeMember(bus, 0x200));
        assertNotNull(GroupManager.getGroup(gid));
        assertEquals(1, GroupManager.getGroup(gid).size());
    }

    @Test
    public void disbandRemovesAllMembersAndPostsIntent() {
        WorldMessageBus bus = new WorldMessageBus();
        int gid = GroupManager.createGroup(bus, 0x100);
        GroupManager.addMember(bus, gid, 0x200);
        GroupManager.addMember(bus, gid, 0x300);
        bus.drain(-1);

        assertTrue(GroupManager.disband(bus, gid));
        assertNull(GroupManager.getGroup(gid));
        assertNull(GroupManager.groupIdOf(0x100));
        assertNull(GroupManager.groupIdOf(0x200));
        assertNull(GroupManager.groupIdOf(0x300));
        assertEquals(1, bus.pending(-1));
    }

    // ─── Bus dispatch / fan-out ────────────────────────────────────

    @Test
    public void createIntentBroadcastsToLeader() {
        Player p = makePlayer(0x100);
        GroupManager.setUidLookupForTesting(uid -> uid == 0x100 ? p : null);

        WorldMessageBus bus = new WorldMessageBus();
        GroupManager.installBusHandlers(bus);
        GroupManager.createGroup(bus, 0x100);
        bus.drain(-1);

        assertEquals(1, captured.size());
        assertEquals(TeamEvent8388.EVENT_TYPE_41, captured.get(0).eventType);
        assertEquals(4, captured.get(0).payload.length);
    }

    @Test
    public void memberAddedIntentBroadcastsTo43EachMember() {
        Player leader = makePlayer(0x100);
        Player member = makePlayer(0x200);
        GroupManager.setUidLookupForTesting(uid -> {
            if (uid == 0x100) return leader;
            if (uid == 0x200) return member;
            return null;
        });

        WorldMessageBus bus = new WorldMessageBus();
        GroupManager.installBusHandlers(bus);
        int gid = GroupManager.createGroup(bus, 0x100);
        bus.drain(-1);
        captured.clear();

        GroupManager.addMember(bus, gid, 0x200);
        bus.drain(-1);

        assertEquals(2, captured.size());
        for (CapturedSend c : captured) {
            assertEquals(TeamEvent8388.EVENT_TYPE_43, c.eventType);
            assertEquals(9, c.payload.length); // member-add 9-byte payload
        }
    }

    @Test
    public void memberAddedPayloadCarriesRecipientThenMemberUid() {
        Player leader = makePlayer(0x100);
        Player member = makePlayer(0x200);
        GroupManager.setUidLookupForTesting(uid -> {
            if (uid == 0x100) return leader;
            if (uid == 0x200) return member;
            return null;
        });

        WorldMessageBus bus = new WorldMessageBus();
        GroupManager.installBusHandlers(bus);
        int gid = GroupManager.createGroup(bus, 0x100);
        bus.drain(-1);
        captured.clear();

        GroupManager.addMember(bus, gid, 0x200);
        bus.drain(-1);

        // Find the leader's send and verify payload structure.
        CapturedSend leaderSend = captured.stream()
                .filter(c -> c.recipient == leader)
                .findFirst().orElse(null);
        assertNotNull(leaderSend);
        byte[] p = leaderSend.payload;
        // [recipient_uid LE32][role][member_uid LE32]
        assertEquals(0x00, p[0]); // 0x100 LE
        assertEquals(0x01, p[1]);
        assertEquals(0x00, p[2]);
        assertEquals(0x00, p[3]);
        // role byte for leader = ROLE_LEADER (0x00)
        assertEquals(Group.ROLE_LEADER, p[4] & 0xff);
        // member uid 0x200 LE
        assertEquals(0x00, p[5]);
        assertEquals(0x02, p[6]);
        assertEquals(0x00, p[7]);
        assertEquals(0x00, p[8]);

        CapturedSend memberSend = captured.stream()
                .filter(c -> c.recipient == member)
                .findFirst().orElse(null);
        assertNotNull(memberSend);
        // For the new member: role = ROLE_MEMBER
        assertEquals(Group.ROLE_MEMBER, memberSend.payload[4] & 0xff);
    }

    @Test
    public void disbandIntentBroadcastsToAllMembers() {
        Player a = makePlayer(0x100);
        Player b = makePlayer(0x200);
        Player c = makePlayer(0x300);
        GroupManager.setUidLookupForTesting(uid -> {
            if (uid == 0x100) return a;
            if (uid == 0x200) return b;
            if (uid == 0x300) return c;
            return null;
        });

        WorldMessageBus bus = new WorldMessageBus();
        GroupManager.installBusHandlers(bus);
        int gid = GroupManager.createGroup(bus, 0x100);
        GroupManager.addMember(bus, gid, 0x200);
        GroupManager.addMember(bus, gid, 0x300);
        bus.drain(-1);
        captured.clear();

        GroupManager.disband(bus, gid);
        bus.drain(-1);

        assertEquals(3, captured.size());
        for (CapturedSend cs : captured) {
            // disband uses 0x42 (self-uid 4-byte payload)
            assertEquals(TeamEvent8388.EVENT_TYPE_42, cs.eventType);
            assertEquals(4, cs.payload.length);
        }
    }

    @Test
    public void busDispatchSkipsRecipientsNotOnline() {
        // Leader online, member NOT online.
        Player leader = makePlayer(0x100);
        GroupManager.setUidLookupForTesting(uid ->
                uid == 0x100 ? leader : null);

        WorldMessageBus bus = new WorldMessageBus();
        GroupManager.installBusHandlers(bus);
        int gid = GroupManager.createGroup(bus, 0x100);
        bus.drain(-1);
        captured.clear();

        GroupManager.addMember(bus, gid, 0x200);
        bus.drain(-1);

        // Only the leader's send was captured (member 0x200 lookup returned null).
        assertEquals(1, captured.size());
        assertEquals(leader, captured.get(0).recipient);
    }

    @Test
    public void sinkExceptionDoesNotBreakOtherRecipients() {
        Player a = makePlayer(0x100);
        Player b = makePlayer(0x200);
        GroupManager.setUidLookupForTesting(uid -> {
            if (uid == 0x100) return a;
            if (uid == 0x200) return b;
            return null;
        });
        // Sink throws on 'a', should still deliver to 'b'.
        List<Player> reached = new ArrayList<>();
        GroupManager.setPacketSinkForTesting((r, i, et, p) -> {
            if (r == a) throw new RuntimeException("simulated");
            reached.add(r);
        });

        WorldMessageBus bus = new WorldMessageBus();
        GroupManager.installBusHandlers(bus);
        int gid = GroupManager.createGroup(bus, 0x100);
        bus.drain(-1);
        GroupManager.addMember(bus, gid, 0x200);
        bus.drain(-1);

        // 'b' was reached during the create AND add events.
        assertTrue(reached.contains(b));
    }

    // ─── eventTypeFor / payloadFor static helpers ──────────────────

    @Test
    public void eventTypeMappingMatchesProtocol() {
        assertEquals(TeamEvent8388.EVENT_TYPE_41,
                GroupManager.eventTypeFor(GroupStateChangeIntent.Kind.CREATED));
        assertEquals(TeamEvent8388.EVENT_TYPE_43,
                GroupManager.eventTypeFor(GroupStateChangeIntent.Kind.MEMBER_ADDED));
        assertEquals(TeamEvent8388.EVENT_TYPE_42,
                GroupManager.eventTypeFor(GroupStateChangeIntent.Kind.MEMBER_REMOVED));
        assertEquals(TeamEvent8388.EVENT_TYPE_42,
                GroupManager.eventTypeFor(GroupStateChangeIntent.Kind.DISBANDED));
    }

    @Test
    public void selfEventPayloadIsRecipientUidLE() {
        GroupStateChangeIntent intent = new GroupStateChangeIntent(
                1, GroupStateChangeIntent.Kind.CREATED, 0x12345678,
                java.util.Arrays.asList(0x12345678));
        byte[] p = GroupManager.payloadFor(intent, 0x12345678);
        assertEquals(4, p.length);
        assertEquals(0x78, p[0] & 0xff);
        assertEquals(0x56, p[1] & 0xff);
        assertEquals(0x34, p[2] & 0xff);
        assertEquals(0x12, p[3] & 0xff);
    }
}
