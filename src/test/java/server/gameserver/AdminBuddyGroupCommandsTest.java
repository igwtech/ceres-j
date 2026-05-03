package server.gameserver;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.team.BuddyManager;
import server.gameserver.team.Group;
import server.gameserver.team.GroupManager;
import server.gameserver.team.GroupStateChangeIntent;

/**
 * Tests for the buddy + group admin commands ({@code !buddyadd},
 * {@code !buddyrm}, {@code !buddylist}, {@code !groupcreate},
 * {@code !groupinvite}, {@code !groupleave}, {@code !groupinfo})
 * — Phase 4 part 4.
 *
 * <p>Verifies that the in-memory state changes occur and that bad
 * inputs / null-bus paths don't throw. Reply packets aren't checked
 * (UDP-emitted, drops without a real socket).
 */
public class AdminBuddyGroupCommandsTest {

    private List<GroupStateChangeIntent> groupIntents;
    private Player player;
    private WorldMessageBus bus;

    private static Player makePlayer(int uid, String name) {
        Player pl = server.gameserver.packets.server_udp.PacketTestFixture
                .newPlayer();
        PlayerCharacter pc = pl.getCharacter();
        pc.setName(name);
        pc.setMisc(PlayerCharacter.MISC_ID, uid);
        return pl;
    }

    @Before
    public void setUp() {
        BuddyManager.resetForTesting();
        GroupManager.resetForTesting();
        groupIntents = new ArrayList<>();

        bus = new WorldMessageBus();
        bus.registerHandler(GroupStateChangeIntent.class,
                i -> groupIntents.add((GroupStateChangeIntent) i));
        GameServer.setBusForTesting(bus);

        player = makePlayer(0x1234, "Tester");
    }

    @After
    public void tearDown() {
        GameServer.setBusForTesting(null);
        BuddyManager.resetForTesting();
        GroupManager.resetForTesting();
    }

    // ─── Buddy commands ────────────────────────────────────────────

    @Test
    public void buddyAddRecordsBuddy() {
        AdminCommandHandler.cmdBuddyAdd(player, "0xCAFE");
        assertTrue(BuddyManager.isBuddy(0x1234, 0xCAFE));
    }

    @Test
    public void buddyAddIdempotent() {
        AdminCommandHandler.cmdBuddyAdd(player, "0xCAFE");
        AdminCommandHandler.cmdBuddyAdd(player, "0xCAFE");
        // Still exactly one buddy.
        assertEquals(1, BuddyManager.listBuddies(0x1234).size());
    }

    @Test
    public void buddyAddAcceptsDecimal() {
        AdminCommandHandler.cmdBuddyAdd(player, "42");
        assertTrue(BuddyManager.isBuddy(0x1234, 42));
    }

    @Test
    public void buddyAddBadArgsDoesNotThrow() {
        AdminCommandHandler.cmdBuddyAdd(player, "");
        AdminCommandHandler.cmdBuddyAdd(player, null);
        AdminCommandHandler.cmdBuddyAdd(player, "garbage");
        assertTrue(BuddyManager.listBuddies(0x1234).isEmpty());
    }

    @Test
    public void buddyRmRemovesBuddy() {
        BuddyManager.add(0x1234, 0xCAFE);
        AdminCommandHandler.cmdBuddyRm(player, "0xCAFE");
        assertFalse(BuddyManager.isBuddy(0x1234, 0xCAFE));
    }

    @Test
    public void buddyRmUnknownDoesNotThrow() {
        AdminCommandHandler.cmdBuddyRm(player, "0x999");
        // No exception, list still empty.
        assertTrue(BuddyManager.listBuddies(0x1234).isEmpty());
    }

    @Test
    public void buddyRmBadArgsDoesNotThrow() {
        AdminCommandHandler.cmdBuddyRm(player, "");
        AdminCommandHandler.cmdBuddyRm(player, null);
        AdminCommandHandler.cmdBuddyRm(player, "garbage");
    }

    @Test
    public void buddyListWithEntriesDoesNotThrow() {
        BuddyManager.add(0x1234, 0xCAFE);
        BuddyManager.add(0x1234, 0xBABE);
        AdminCommandHandler.cmdBuddyList(player);
        // Just verify no exception escaped.
    }

    @Test
    public void buddyListEmptyDoesNotThrow() {
        AdminCommandHandler.cmdBuddyList(player);
    }

    // ─── Group commands ────────────────────────────────────────────

    @Test
    public void groupCreatePostsIntentAndRecordsLeader() {
        AdminCommandHandler.cmdGroupCreate(player);
        bus.drain(-1);

        assertEquals(1, groupIntents.size());
        assertEquals(GroupStateChangeIntent.Kind.CREATED,
                groupIntents.get(0).kind);
        assertNotNull(GroupManager.groupIdOf(0x1234));
    }

    @Test
    public void groupCreateTwiceFailsTheSecondTime() {
        AdminCommandHandler.cmdGroupCreate(player);
        AdminCommandHandler.cmdGroupCreate(player);
        bus.drain(-1);
        // Only the first attempt produced an intent.
        assertEquals(1, groupIntents.size());
    }

    @Test
    public void groupCreateWithNullBusDoesNotThrow() {
        GameServer.setBusForTesting(null);
        AdminCommandHandler.cmdGroupCreate(player);
        // No NPE; nothing posted.
        assertNull(GroupManager.groupIdOf(0x1234));
    }

    @Test
    public void groupInviteFlowsThroughManager() {
        AdminCommandHandler.cmdGroupCreate(player);
        bus.drain(-1);
        groupIntents.clear();

        AdminCommandHandler.cmdGroupInvite(player, "0x9999");
        bus.drain(-1);

        // MEMBER_ADDED intent posted.
        assertEquals(1, groupIntents.size());
        assertEquals(GroupStateChangeIntent.Kind.MEMBER_ADDED,
                groupIntents.get(0).kind);
        Integer gid = GroupManager.groupIdOf(0x9999);
        assertNotNull(gid);
        Group g = GroupManager.getGroup(gid);
        assertTrue(g.contains(0x9999));
    }

    @Test
    public void groupInviteWithoutGroupReturnsError() {
        // Caller hasn't created a group — invite should be rejected.
        AdminCommandHandler.cmdGroupInvite(player, "0x9999");
        bus.drain(-1);
        assertTrue(groupIntents.isEmpty());
    }

    @Test
    public void groupInviteBadArgsDoesNotThrow() {
        AdminCommandHandler.cmdGroupCreate(player);
        bus.drain(-1);
        groupIntents.clear();

        AdminCommandHandler.cmdGroupInvite(player, "");
        AdminCommandHandler.cmdGroupInvite(player, null);
        AdminCommandHandler.cmdGroupInvite(player, "garbage");
        assertTrue(groupIntents.isEmpty());
    }

    @Test
    public void groupLeaveDisbandsSoloGroup() {
        AdminCommandHandler.cmdGroupCreate(player);
        bus.drain(-1);
        groupIntents.clear();

        AdminCommandHandler.cmdGroupLeave(player);
        bus.drain(-1);

        // Solo leader leaving disbands the group.
        assertEquals(1, groupIntents.size());
        assertEquals(GroupStateChangeIntent.Kind.DISBANDED,
                groupIntents.get(0).kind);
        assertNull(GroupManager.groupIdOf(0x1234));
    }

    @Test
    public void groupLeaveWhenNotInGroupReturnsCleanly() {
        AdminCommandHandler.cmdGroupLeave(player);
        bus.drain(-1);
        assertTrue(groupIntents.isEmpty());
    }

    @Test
    public void groupInfoForGroupedPlayerDoesNotThrow() {
        AdminCommandHandler.cmdGroupCreate(player);
        bus.drain(-1);
        AdminCommandHandler.cmdGroupInfo(player);
    }

    @Test
    public void groupInfoForUngroupedPlayerDoesNotThrow() {
        AdminCommandHandler.cmdGroupInfo(player);
    }

    // ─── ownerUidOf helper ─────────────────────────────────────────

    @Test
    public void ownerUidOfReturnsCharacterUid() {
        assertEquals(0x1234, AdminCommandHandler.ownerUidOf(player));
    }

    @Test
    public void ownerUidOfReturnsZeroForCharacterlessPlayer() {
        Player anon = new Player(new server.database.accounts.Account(99));
        assertEquals(0, AdminCommandHandler.ownerUidOf(anon));
    }
}
