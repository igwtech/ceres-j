package server.database.items;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.SqliteDatabase;
import server.database.playerCharacters.inventory.PlayerGogu;
import server.database.playerCharacters.inventory.PlayerInventory;
import server.database.playerCharacters.inventory.PlayerQB;

/**
 * Functional round-trip tests for character inventory persistence.
 *
 * <p>Each test follows the real server lifecycle: items are created in
 * containers, {@link ItemManager#saveall()} writes them to the
 * {@code items} table, the in-memory state is dropped (simulating a
 * server restart), the containers are re-registered, and
 * {@link ItemManager#loadall()} restores them. The assertions verify
 * the item set, exact grid positions and per-item state (tokens,
 * quantities) are identical after the round-trip.
 *
 * <p>Backed by an in-memory SQLite DB, matching the convention of
 * {@code SqlitePlayerCharacterManagerTest}.
 */
public class ItemManagerPersistenceTest {

    private Connection conn;

    private static short[] tokens(int curr, int max, int stack) {
        short[] t = new short[17];
        t[Item.TOKENS_CURRCOND]     = (short) curr;
        t[Item.TOKENS_MAXCOND]      = (short) max;
        t[Item.TOKENS_ITEMSONSTACK] = (short) stack;
        return t;
    }

    @Before
    public void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        SqliteDatabase.initWithConnection(conn);
        ItemManager.resetForTesting();
    }

    @After
    public void tearDown() throws Exception {
        ItemManager.resetForTesting();
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    /** The schema must expose the slot/flags/tokens columns the
     *  persistence layer round-trips. */
    @Test
    public void schemaHasInventoryColumns() throws Exception {
        boolean slot = false, flags = false, tokens = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(items)")) {
            while (rs.next()) {
                String c = rs.getString("name");
                if ("slot".equals(c)) slot = true;
                if ("flags".equals(c)) flags = true;
                if ("tokens".equals(c)) tokens = true;
            }
        }
        assertTrue("items.slot column must exist", slot);
        assertTrue("items.flags column must exist", flags);
        assertTrue("items.tokens column must exist", tokens);
    }

    /** Empty inventory round-trips to zero rows and zero loaded items. */
    @Test
    public void emptyInventory_roundTripsClean() throws Exception {
        PlayerQB qb = new PlayerQB(100);
        ItemManager.save(qb);

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT COUNT(*) FROM items WHERE container_id = 100")) {
            rs.next();
            assertEquals("empty container persists no rows", 0, rs.getInt(1));
        }

        ItemManager.resetForTesting();
        PlayerQB reloaded = new PlayerQB(100);
        ItemManager.loadall();
        assertEquals(0, reloaded.getNumberofItems());
    }

    /**
     * QB items keep their EXACT slot index across a restart (a
     * stacked consumable in slot 5, an equipped weapon in slot 0).
     */
    @Test
    public void quickbelt_exactSlotsAndTokensSurvive() throws Exception {
        PlayerQB qb = new PlayerQB(200);
        ItemManager.loadContainer(qb);

        // Build items directly: ItemManager.createItem() needs
        // ItemInfoManager (client def files) which are absent in unit
        // tests. The persistence path under test is independent of it.
        Item weapon = new Item(/*type*/ 19, ItemManager.getFreeItemId(),
                qb, Item.ITEMFLAG_WEAPON, tokens(255, 255, 1));
        assertTrue(qb.addItem(0, weapon, 0));
        // Put a stacked medkit explicitly in QB slot 5.
        Item medkit = new Item(/*type*/ 77, ItemManager.getFreeItemId(),
                qb, Item.ITEMFLAG_STACK, tokens(0, 0, 23));
        assertTrue(qb.addItem(5, medkit, 0));

        long weaponId = weapon.getId();
        long medkitId = medkit.getId();
        int weaponPos = weapon.getInventoryPos();

        ItemManager.save(qb);

        // Simulate restart.
        ItemManager.resetForTesting();
        PlayerQB qb2 = new PlayerQB(200);
        ItemManager.loadall();

        assertEquals(2, qb2.getNumberofItems());

        Item w = qb2.getItem(weaponPos, 0);
        assertNotNull("weapon restored to its exact slot", w);
        assertEquals(weaponId, w.getId());
        assertEquals(19, w.getTypeId());
        assertEquals(Item.ITEMFLAG_WEAPON, w.getFlags());
        assertArrayEquals(tokens(255, 255, 1), w.getTokens());

        Item m = qb2.getItem(5, 0);
        assertNotNull("medkit restored to slot 5", m);
        assertEquals(medkitId, m.getId());
        assertEquals("stack quantity preserved",
                23, m.getTokens()[Item.TOKENS_ITEMSONSTACK]);
        assertEquals(5, m.getInventoryPos());
    }

    /**
     * F2 grid items keep their packed slot + X/Y origin across a
     * restart instead of being re-flowed by auto-placement.
     */
    @Test
    public void f2Grid_exactPackedPositionSurvives() throws Exception {
        PlayerInventory inv = new PlayerInventory(300);
        ItemManager.loadContainer(inv);

        // Place a 1x1-ish item at a known XY (sizes come from
        // ItemInfoManager which is absent in tests, so restore falls
        // back to a 1x1 footprint — slot + origin still exact).
        Item a = new Item(/*type*/ 5, ItemManager.getFreeItemId(),
                inv, Item.ITEMFLAG_SIMPLE, tokens(120, 200, 1));
        int packed = 2 + 256 * 3 + 65536 * 0; // x=2, y=3, slot=0
        assertTrue(inv.restoreItemAtPos(packed, a));
        long aId = a.getId();

        ItemManager.save(inv);

        ItemManager.resetForTesting();
        PlayerInventory inv2 = new PlayerInventory(300);
        ItemManager.loadall();

        assertEquals(1, inv2.getNumberofItems());
        Item back = ItemManager.getItem(aId);
        assertNotNull(back);
        assertEquals("packed grid position is byte-identical",
                packed, back.getInventoryPos());
        assertEquals(120, back.getTokens()[Item.TOKENS_CURRCOND]);
        // Also reachable via the XY lookup the F2 render uses.
        assertEquals(aId, inv2.getItem((3 << 8) | 2,
                server.database.items.ItemContainer.FLAG_DSTPOS_XY).getId());
    }

    /** Gogu deposit-box items survive with their slot index intact. */
    @Test
    public void gogu_slotSurvives() throws Exception {
        PlayerGogu gogu = new PlayerGogu(400);
        ItemManager.loadContainer(gogu);

        Item it = new Item(/*type*/ 12, ItemManager.getFreeItemId(),
                gogu, Item.ITEMFLAG_SIMPLE, tokens(50, 99, 1));
        int packed = 1 + 256 * 1 + 65536 * 4; // slot 4
        assertTrue(gogu.restoreItemAtPos(packed, it));
        long id = it.getId();

        ItemManager.save(gogu);
        ItemManager.resetForTesting();
        PlayerGogu g2 = new PlayerGogu(400);
        ItemManager.loadall();

        assertEquals(1, g2.getNumberofItems());
        Item back = ItemManager.getItem(id);
        assertNotNull(back);
        assertEquals(packed, back.getInventoryPos());
        assertEquals(50, back.getTokens()[Item.TOKENS_CURRCOND]);
    }

    /**
     * Two save() cycles must not duplicate rows — save() deletes the
     * container's rows then re-inserts. A removed item must not linger.
     */
    @Test
    public void resaveDoesNotDuplicateAndDropsRemoved() throws Exception {
        PlayerQB qb = new PlayerQB(500);
        ItemManager.loadContainer(qb);

        Item keep = new Item(1, ItemManager.getFreeItemId(), qb,
                Item.ITEMFLAG_SIMPLE, tokens(1, 1, 1));
        Item drop = new Item(2, ItemManager.getFreeItemId(), qb,
                Item.ITEMFLAG_SIMPLE, tokens(1, 1, 1));
        assertTrue(qb.addItem(0, keep, 0));
        assertTrue(qb.addItem(1, drop, 0));
        ItemManager.save(qb);

        // Remove one item, save again.
        assertTrue(qb.removeItem(drop, 1, 0));
        ItemManager.save(qb);

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM items WHERE container_id = 500")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals("only the kept item remains persisted",
                        1, rs.getInt(1));
            }
        }

        ItemManager.resetForTesting();
        PlayerQB qb2 = new PlayerQB(500);
        ItemManager.loadall();
        assertEquals(1, qb2.getNumberofItems());
        assertNotNull(qb2.getItem(0, 0));
        assertNull(qb2.getItem(1, 0));
    }

    /**
     * An item whose container is not registered at load time must not
     * crash loadall — it stays in the id-reservation map (so its id is
     * never reissued) but is not attached.
     */
    @Test
    public void orphanedContainer_doesNotCrashAndReservesId() throws Exception {
        PlayerQB qb = new PlayerQB(600);
        ItemManager.loadContainer(qb);
        Item it = new Item(9, ItemManager.getFreeItemId(), qb,
                Item.ITEMFLAG_SIMPLE, tokens(1, 1, 1));
        assertTrue(qb.addItem(0, it, 0));
        long id = it.getId();
        ItemManager.save(qb);

        // Restart but DON'T re-register container 600.
        ItemManager.resetForTesting();
        ItemManager.loadall();

        // Item is tracked (id reserved) even though unattached.
        assertNotNull(ItemManager.getItem(id));
        assertNull("orphan has no container",
                ItemManager.getItem(id).getContainer());
        // A freshly issued id must not collide with the orphan.
        long next = ItemManager.getFreeItemId();
        assertFalse("free id must skip the reserved orphan id",
                next == id);
    }
}
