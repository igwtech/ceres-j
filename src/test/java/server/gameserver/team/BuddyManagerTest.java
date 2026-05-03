package server.gameserver.team;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.accounts.Account;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;

/**
 * Unit tests for {@link BuddyManager}. State is reset between
 * tests via {@link BuddyManager#resetForTesting()}.
 */
public class BuddyManagerTest {

    private static Player makePlayer(int uid, String name) {
        Account acc = new Account(uid);
        acc.setUsername("u" + uid);
        Player pl = new Player(acc);
        PlayerCharacter pc = new PlayerCharacter();
        pc.setName(name);
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
    public void setUp()    { BuddyManager.resetForTesting(); }

    @After
    public void tearDown() { BuddyManager.resetForTesting(); }

    // ─── add / remove / isBuddy semantics ──────────────────────────

    @Test
    public void addRecordsBuddyAndIsIdempotent() {
        assertTrue(BuddyManager.add(1, 2));
        assertTrue(BuddyManager.isBuddy(1, 2));
        // re-add returns false (already in set)
        assertFalse(BuddyManager.add(1, 2));
    }

    @Test
    public void cannotBuddySelf() {
        assertFalse(BuddyManager.add(7, 7));
        assertFalse(BuddyManager.isBuddy(7, 7));
    }

    @Test
    public void removeReturnsFalseWhenNotABuddy() {
        assertFalse(BuddyManager.remove(1, 2));
    }

    @Test
    public void removeReturnsTrueAndCleansUpEmptySet() {
        BuddyManager.add(1, 2);
        BuddyManager.add(1, 3);
        assertEquals(1, BuddyManager.trackedOwners());
        assertTrue(BuddyManager.remove(1, 2));
        assertEquals(1, BuddyManager.trackedOwners()); // still has 3
        assertTrue(BuddyManager.remove(1, 3));
        assertEquals(0, BuddyManager.trackedOwners()); // cleared
    }

    @Test
    public void buddyListIsNotShared() {
        BuddyManager.add(1, 10);
        BuddyManager.add(1, 20);
        BuddyManager.add(2, 10);
        assertEquals(2, BuddyManager.listBuddies(1).size());
        assertEquals(1, BuddyManager.listBuddies(2).size());
        assertEquals(0, BuddyManager.listBuddies(3).size());
    }

    @Test
    public void listBuddiesPreservesInsertionOrder() {
        BuddyManager.add(1, 30);
        BuddyManager.add(1, 10);
        BuddyManager.add(1, 20);
        List<Integer> list = BuddyManager.listBuddies(1);
        assertEquals(Integer.valueOf(30), list.get(0));
        assertEquals(Integer.valueOf(10), list.get(1));
        assertEquals(Integer.valueOf(20), list.get(2));
    }

    @Test
    public void listBuddiesReturnsDefensiveCopy() {
        BuddyManager.add(1, 10);
        List<Integer> snap = BuddyManager.listBuddies(1);
        // Mutating the returned list must not affect the manager.
        try {
            snap.add(99);
        } catch (UnsupportedOperationException ignore) {
            // also acceptable — defensive copy may be unmodifiable
            return;
        }
        assertEquals(1, BuddyManager.listBuddies(1).size());
    }

    // ─── name-based lookup ─────────────────────────────────────────

    @Test
    public void findOnlineBuddyByNameResolvesUid() {
        Player p = makePlayer(0x42, "Norman Gates");
        BuddyManager.setNameLookupForTesting(name ->
                "Norman Gates".equalsIgnoreCase(name) ? p : null);
        BuddyManager.add(1, 0x42);

        Integer uid = BuddyManager.findOnlineBuddyByName(1, "Norman Gates");
        assertNotNull(uid);
        assertEquals(Integer.valueOf(0x42), uid);
    }

    @Test
    public void findOnlineBuddyByNameRespectsBuddyListMembership() {
        Player p = makePlayer(0x42, "Stranger");
        BuddyManager.setNameLookupForTesting(name -> p);
        // 1 has not added 0x42 — must return null even though they're online.
        assertNull(BuddyManager.findOnlineBuddyByName(1, "Stranger"));
    }

    @Test
    public void findOnlineBuddyByNameReturnsNullForOfflineBuddy() {
        BuddyManager.add(1, 0x42);
        BuddyManager.setNameLookupForTesting(name -> null);
        assertNull(BuddyManager.findOnlineBuddyByName(1, "Anyone"));
    }

    @Test
    public void findOnlineBuddyByNameRejectsNullAndEmpty() {
        assertNull(BuddyManager.findOnlineBuddyByName(1, null));
        assertNull(BuddyManager.findOnlineBuddyByName(1, ""));
    }

    @Test
    public void findOnlineBuddyByNameIsCaseInsensitive() {
        // The lookup strategy itself does case-insensitive match;
        // verify the manager doesn't lowercase before passing.
        Player p = makePlayer(0x42, "Norman Gates");
        BuddyManager.setNameLookupForTesting(name -> {
            // simulate the default behaviour
            return "norman gates".equalsIgnoreCase(name) ? p : null;
        });
        BuddyManager.add(1, 0x42);
        assertNotNull(BuddyManager.findOnlineBuddyByName(1, "NORMAN gates"));
    }

    @Test
    public void findOnlineBuddyByNameSkipsCharacterlessPlayer() {
        // Player object exists but no PlayerCharacter set yet (e.g.
        // mid-login). Lookup must return null without NPE.
        Account acc = new Account(99);
        acc.setUsername("nochar");
        Player anon = new Player(acc);

        BuddyManager.setNameLookupForTesting(name -> anon);
        BuddyManager.add(1, 0x42);
        assertNull(BuddyManager.findOnlineBuddyByName(1, "Anything"));
    }
}
