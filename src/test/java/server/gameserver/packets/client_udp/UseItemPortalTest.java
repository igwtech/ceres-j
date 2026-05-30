package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.SqliteDatabase;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.ZoneManager;
import server.gameserver.CapturingTCPConnection;
import server.gameserver.packets.server_tcp.Location;
import server.gameserver.packets.server_tcp.Packet830D;
import server.gameserver.packets.server_udp.ChangeLocation;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.ServerTCPPacket;
import server.interfaces.ServerUDPPacket;
import server.testtools.CapturingUDPConnection;

/**
 * Functional test for the use-object → ChangeLocation portal path
 * ({@link UseItem}).
 *
 * <p>When the client uses a furniture world-change actor (rawItemID
 * with low 10 bits clear; .dat object index = rawItemID/1024 - 1),
 * the server must resolve {@code world_objects → worldmodel.def →
 * appplaces.def}, commit {@code MISC_LOCATION = ExitWorldID}, and
 * emit a {@link ChangeLocation} ({@code 0x03/0x1f/<localId>/0x38})
 * carrying {@code (Location, Entity, entityType)} — NO coordinates
 * (doc §4).
 *
 * <p>Ground truth: §5 {@code pepper_p3 (worldId 7) object 95 →
 * worldmodel 380 (ft 18, fval 130) → appplaces 130 "sewer entrance"
 * → destWorld 946, Entity 1}. rawItemID = (95+1)*1024 = 98304.
 */
public class UseItemPortalTest {

    private Connection conn;

    @Before
    public void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        SqliteDatabase.initWithConnection(conn);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE world_objects ("
                    + " world_path TEXT NOT NULL,"
                    + " object_id BIGINT, worldmodel_id INTEGER)");
            st.execute("CREATE TABLE client_defs ("
                    + " def_name TEXT NOT NULL, entry_id INTEGER NOT NULL,"
                    + " fields TEXT NOT NULL,"
                    + " PRIMARY KEY (def_name, entry_id))");
            // npc_spawns already exists (SqliteDatabase.createTables
            // is part of the production schema) — the Zone ctor's
            // NpcSpawnManager query returns an empty result.
            st.execute("INSERT INTO world_objects VALUES "
                    + "('worlds/pepper/pak_pepper_p3.dat', 95, 380)");
            st.execute("INSERT INTO client_defs VALUES "
                    + "('worldmodel', 380, '"
                    + "{\"f0\":\"PEPPER PARK SEWER 7.1\",\"f1\":66,"
                    + "\"f2\":18,\"f3\":130,\"f4\":0,\"f5\":0,"
                    + "\"f6\":0,\"directive\":\"setentry\"}')");
            st.execute("INSERT INTO client_defs VALUES "
                    + "('appplaces', 130, '"
                    + "{\"f0\":\"sewer entrance\",\"f1\":946,\"f2\":1,"
                    + "\"f3\":4,\"directive\":\"setentry\"}')");
        }
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null) conn.close();
        // Clear the static WorldManager.weList so this test's
        // seedWorldManager() entries don't bleed into the next
        // test class (SitOnChairTest etc. resolve world paths via
        // WorldManager.getWorldname and assume an empty map).
        java.lang.reflect.Field f =
                server.database.worlds.WorldManager.class
                        .getDeclaredField("weList");
        f.setAccessible(true);
        ((java.util.TreeMap<?, ?>) f.get(null)).clear();
    }

    /** Install a Zone whose worldname is the dotted source path
     *  PortalResolver maps to {@code worlds/pepper/pak_pepper_p3.dat},
     *  and register it in ZoneManager so updateZone() resolves. */
    private static void installPepperP3Zone(Player pl)
            throws Exception {
        Zone z = new Zone(7, "pepper/pepper_p3");
        java.lang.reflect.Field zoneField =
                Player.class.getDeclaredField("currentZone");
        zoneField.setAccessible(true);
        zoneField.set(pl, z);
        java.lang.reflect.Field zlField =
                ZoneManager.class.getDeclaredField("zoneList");
        zlField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.TreeMap<Integer, Zone> zoneList =
                (java.util.TreeMap<Integer, Zone>) zlField.get(null);
        zoneList.put(7, z);
        // Destination zone (worldId 946) — registered so
        // updateZone() lands somewhere non-null. (NC2 runtime sewer
        // worldId mapping is an open item — see PortalResolver doc.)
        zoneList.put(946, new Zone(946, "citysewer/peppersewer_x"));
    }

    /**
     * Drain any PortalCrossCommitEvent the UseItem queued and fire
     * it synchronously — production schedules the commit ~470ms
     * after the click; tests must drive it manually.
     */
    private static void fireCrossCommitIfQueued(Player pl)
            throws Exception {
        java.lang.reflect.Field f =
                Player.class.getDeclaredField("eventList");
        f.setAccessible(true);
        Object queue = f.get(pl);
        // PriorityList is a thin wrapper; cast and pull events.
        java.lang.reflect.Method isEmpty =
                queue.getClass().getMethod("isEmpty");
        java.lang.reflect.Method getFirst =
                queue.getClass().getMethod("getFirst");
        java.lang.reflect.Method removeFirst =
                queue.getClass().getMethod("removeFirst");
        while (!(Boolean) isEmpty.invoke(queue)) {
            Object ev = getFirst.invoke(queue);
            removeFirst.invoke(queue);
            if (ev instanceof server.gameserver.internalEvents
                    .PortalCrossCommitEvent) {
                ((server.gameserver.internalEvents
                        .PortalCrossCommitEvent) ev).execute(pl);
            }
        }
    }

    /** Build the 11-byte UseItem body: 03 [seq2] 1f [sub2] 17
     *  [rawItemID LE32]. */
    private static byte[] buildBody(int rawItemId) {
        byte[] b = new byte[11];
        b[0] = 0x03; b[1] = 0x42; b[2] = 0x00;
        b[3] = 0x1f; b[4] = 0x05; b[5] = 0x00;
        b[6] = 0x17;
        b[7]  = (byte) (rawItemId        & 0xff);
        b[8]  = (byte) ((rawItemId >> 8 ) & 0xff);
        b[9]  = (byte) ((rawItemId >> 16) & 0xff);
        b[10] = (byte) ((rawItemId >> 24) & 0xff);
        return b;
    }

    @Test
    public void useSewerEntranceCommitsZoneAndEmitsChangeLocation()
            throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installPepperP3Zone(pl);
        CapturingUDPConnection cap =
                CapturingUDPConnection.replaceOn(pl);

        // rawItemID for .dat object 95: (95 + 1) * 1024 = 98304.
        new UseItem(buildBody(98304)).execute(pl);
        // Production defers commit + Location + ChangeLocation by
        // 470ms (PortalCrossCommitEvent). Drive it manually.
        fireCrossCommitIfQueued(pl);

        // MISC_LOCATION committed to the appplaces ExitWorldID (946).
        assertEquals(946, pl.getCharacter()
                .getMisc(PlayerCharacter.MISC_LOCATION));

        // A ChangeLocation was emitted.
        List<ServerUDPPacket> sent = cap.received();
        ChangeLocation cl = null;
        for (ServerUDPPacket p : sent) {
            if (p instanceof ChangeLocation) {
                cl = (ChangeLocation) p;
            }
        }
        assertNotNull("UseItem on a world-change actor must emit "
                + "ChangeLocation", cl);

        // Pin the wire bytes (body starts at datagram offset 10).
        DatagramPacket[] dps = cl.getDatagramPackets();
        byte[] d = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, d, 0, d.length);
        assertEquals(0x13, d[0] & 0xFF);
        assertEquals(0x03, d[7] & 0xFF);
        byte[] body = new byte[12];
        System.arraycopy(d, 10, body, 0, 12);
        byte[] expected = {
                0x1f,
                0x01, 0x00,                    // localId LE16 = mapID 1
                0x38,
                0x04,
                0x00,                          // entityType (ft 18 → 0)
                (byte) 0xb2, 0x03, 0x00, 0x00, // Location LE32 = 946
                0x01, 0x00                     // Entity LE16 = 1
        };
        assertArrayEquals(
                "pepper_p3 sewer-entrance ChangeLocation bytes",
                expected, body);
    }

    /**
     * FIX B (RE_tcp_confirm.md §2/§2.1/§7.3): the portal zone-change
     * is a world change and MUST emit the TCP confirm pair
     * {@code 0x83/0x0d} (loading UI begin) THEN {@code 0x83/0x0c}
     * (Location: destination BSP) — in that order — or the client
     * never runs the world-load state machine
     * ({@code FUN_0055aa30 case '\r'/'\f' → FUN_00558950}). The
     * retail ground truth (pcap
     * {@code RETAIL_LIVE_p1p3_sit_npc_20260517.pcap}) is
     * {@code t=238.352 S→C 0x83/0x0d → t=238.847 S→C 0x83/0x0c}.
     */
    @Test
    public void portalZoneChangeEmits830DThen830CInOrder()
            throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installPepperP3Zone(pl);
        CapturingUDPConnection.replaceOn(pl);
        CapturingTCPConnection tcp = new CapturingTCPConnection();
        pl.setTcpConnection(tcp);

        new UseItem(buildBody(98304)).execute(pl); // sewer entrance
        fireCrossCommitIfQueued(pl);

        // Zone committed to the appplaces ExitWorldID (946).
        assertEquals(946, pl.getCharacter()
                .getMisc(PlayerCharacter.MISC_LOCATION));

        // Find the 0x83/0x0d and 0x83/0x0c packets and their order.
        int idx830D = -1, idx830C = -1;
        java.util.List<ServerTCPPacket> tcpSent = tcp.received();
        for (int i = 0; i < tcpSent.size(); i++) {
            ServerTCPPacket p = tcpSent.get(i);
            if (p instanceof Packet830D && idx830D < 0) idx830D = i;
            if (p instanceof Location   && idx830C < 0) idx830C = i;
        }
        assertTrue("portal zone-change must emit 0x83/0x0d "
                + "(Packet830D)", idx830D >= 0);
        assertTrue("portal zone-change must emit 0x83/0x0c "
                + "(Location)", idx830C >= 0);
        assertTrue("0x83/0x0d MUST be sent BEFORE 0x83/0x0c "
                + "(retail t=238.352 → t=238.847)",
                idx830D < idx830C);

        // Byte-pin the 0x83/0x0d frame: fe 04 00 83 0d 00 00
        Packet830D begin = (Packet830D) tcpSent.get(idx830D);
        byte[] bd = begin.getData();
        assertEquals(0xfe, bd[0] & 0xFF);
        assertEquals(0x04, bd[1] & 0xFF);   // len lo = 4
        assertEquals(0x00, bd[2] & 0xFF);   // len hi
        assertEquals(0x83, bd[3] & 0xFF);
        assertEquals(0x0d, bd[4] & 0xFF);
        assertEquals(0x00, bd[5] & 0xFF);
        assertEquals(0x00, bd[6] & 0xFF);

        // Byte-pin the 0x83/0x0c Location header + destination zone:
        //   fe <len> 83 0c [zoneId LE32]=946 [LE32 0] [LE32 0] name…
        Location loc = (Location) tcpSent.get(idx830C);
        byte[] lc = loc.getData();
        assertEquals(0xfe, lc[0] & 0xFF);
        assertEquals(0x83, lc[3] & 0xFF);
        assertEquals(0x0c, lc[4] & 0xFF);
        // zoneId LE32 == 946 (the committed MISC_LOCATION)
        int zoneId = (lc[5] & 0xFF) | ((lc[6] & 0xFF) << 8)
                | ((lc[7] & 0xFF) << 16) | ((lc[8] & 0xFF) << 24);
        assertEquals("0x83/0x0c zoneId must be the committed "
                + "destination worldId", 946, zoneId);
    }

    // ────────────────────────── task #253 — conditional 0x83/0x0d
    //
    // Retail empirically NEVER emits 0x83/0x0d (LoadingBegin) on a
    // cross-OUT to a destination BSP the client already has loaded.
    // Verified in pcap RETRY3 2026-05-24: frames 310+312 (cross-IN
    // to reaktor) carry BOTH 830D+830C; frame 5906 (cross-OUT back
    // to plaza_p1, BSP already loaded from login spawn) carries
    // ONLY 830C. Always-emitting 830D on a cached BSP is the
    // hypothesised root cause of #208 "SYNCHRONIZING INTO DUNGEON
    // ZONE" hang.

    /** Seed WorldManager so PortalResolver can resolve dest worldId
     *  → .dat path without a full DB worldinfo row. */
    private static void seedWorldManager(int wid, String name)
            throws Exception {
        java.lang.reflect.Field f =
                server.database.worlds.WorldManager.class
                        .getDeclaredField("weList");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.TreeMap<Integer, server.database.worlds.World> we =
                (java.util.TreeMap<Integer,
                        server.database.worlds.World>) f.get(null);
        we.put(wid, new server.database.worlds.World(wid, name));
    }

    /** The BSP cache key for citysewer destination — what
     *  PortalResolver.worldIdToObjectPath returns. */
    private static final String DEST_BSP_KEY =
            "worlds/citysewer/pak_peppersewer_x.dat";

    @Test
    public void crossInToNewBspEmits830DAnd830C() throws Exception {
        // FIRST cross of the session — Player.loadedBspPaths does
        // not contain the destination → 830D MUST be emitted, then
        // 830C. (This is the same scenario as
        // portalZoneChangeEmits830DThen830CInOrder above, but
        // explicit about the BSP-cache state.)
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installPepperP3Zone(pl);
        seedWorldManager(7,   "pepper/pepper_p3");
        seedWorldManager(946, "citysewer/peppersewer_x");
        CapturingUDPConnection.replaceOn(pl);
        CapturingTCPConnection tcp = new CapturingTCPConnection();
        pl.setTcpConnection(tcp);

        // Mark the source BSP as loaded (a real Player gets this
        // from WorldEntryEvent at login). The destination is NOT
        // yet in the cache.
        pl.markBspLoaded("worlds/pepper/pak_pepper_p3.dat");
        assertFalse("destination BSP must NOT be cached for cross-IN",
                pl.hasLoadedBsp(DEST_BSP_KEY));

        new UseItem(buildBody(98304)).execute(pl); // sewer entrance
        fireCrossCommitIfQueued(pl);

        boolean saw830D = false, saw830C = false;
        for (ServerTCPPacket p : tcp.received()) {
            if (p instanceof Packet830D) saw830D = true;
            if (p instanceof Location)   saw830C = true;
        }
        assertTrue("cross-IN to a fresh BSP must emit 0x83/0x0d",
                saw830D);
        assertTrue("cross-IN must always emit 0x83/0x0c", saw830C);
        // And the commit must have marked the destination as cached
        // so a subsequent cross-OUT will suppress 830D.
        assertTrue("after a successful cross, the destination BSP "
                + "must be in the loaded set",
                pl.hasLoadedBsp(DEST_BSP_KEY));
    }

    @Test
    public void crossOutToCachedBspSuppresses830DButStillEmits830C()
            throws Exception {
        // SECOND cross back to an already-loaded BSP. Retail emits
        // ONLY 830C; the client uses its cached BSP and skips the
        // loading-state-machine entry. Our pre-#253 code emitted
        // 830D unconditionally — likely tripping the loading UI for
        // a BSP the client already had, hanging on SYNCHRONIZING.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installPepperP3Zone(pl);
        seedWorldManager(7,   "pepper/pepper_p3");
        seedWorldManager(946, "citysewer/peppersewer_x");
        CapturingUDPConnection.replaceOn(pl);
        CapturingTCPConnection tcp = new CapturingTCPConnection();
        pl.setTcpConnection(tcp);

        // Both source AND destination are already loaded — this is
        // the "re-cross" scenario.
        pl.markBspLoaded("worlds/pepper/pak_pepper_p3.dat");
        pl.markBspLoaded(DEST_BSP_KEY);

        new UseItem(buildBody(98304)).execute(pl);
        fireCrossCommitIfQueued(pl);

        boolean saw830D = false, saw830C = false;
        for (ServerTCPPacket p : tcp.received()) {
            if (p instanceof Packet830D) saw830D = true;
            if (p instanceof Location)   saw830C = true;
        }
        assertFalse("cross-OUT to a cached BSP MUST NOT emit "
                + "0x83/0x0d (retail-faithful — root cause of #208)",
                saw830D);
        assertTrue("0x83/0x0c MUST still be emitted on every cross",
                saw830C);
    }

    @Test
    public void nonFurnitureItemDoesNotZone() throws Exception {
        // rawItemID with low 10 bits set is a door/PC/NPC, never a
        // portal. MISC_LOCATION must stay at the fixture default (7).
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installPepperP3Zone(pl);
        CapturingUDPConnection cap =
                CapturingUDPConnection.replaceOn(pl);

        new UseItem(buildBody(0x80 + 33)).execute(pl); // a door id

        assertEquals(7, pl.getCharacter()
                .getMisc(PlayerCharacter.MISC_LOCATION));
        for (ServerUDPPacket p : cap.received()) {
            assertFalse("door use must not emit ChangeLocation",
                    p instanceof ChangeLocation);
        }
    }

    @Test
    public void unknownFurnitureFallsThroughToDoorPath()
            throws Exception {
        // Furniture-shaped rawItemID but no matching world_objects
        // row → PortalResolver returns null → UseItem must NOT zone
        // and must fall through to its existing (door/animation)
        // behaviour without throwing.
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installPepperP3Zone(pl);
        CapturingUDPConnection.replaceOn(pl);

        new UseItem(buildBody(777 * 1024)).execute(pl);

        assertEquals(7, pl.getCharacter()
                .getMisc(PlayerCharacter.MISC_LOCATION));
    }

    /**
     * Task #236 regression guard. Portal-cross via UseItem MUST
     * flush the new {@code MISC_LOCATION} to the database in the
     * same call. Pre-fix the code only updated the in-memory
     * {@link PlayerCharacter} via {@code setMisc} and called
     * {@code pl.updateZone()} (which only re-registers on the
     * in-memory Zone object — no DB write). The client teardown +
     * reconnect that follows a portal cross would then re-load
     * the character from DB with the STALE source location,
     * spawning the user back in the SOURCE zone. Live-reproduced
     * 2026-05-23: plaza1 → sewer 1573 portal click + reconnect
     * landed in plaza1 again, and the dungeon's exit-door
     * lookups failed because PortalResolver was querying
     * {@code worlds/plaza/pak_plaza_p1.dat} instead of the
     * sewer's. Fix flushes via
     * {@code PlayerCharacterManager.saveCharacter(pc)}.
     */
    @Test
    public void portalCrossPersistsNewLocationToDbBeforeReconnect()
            throws Exception {
        // Seed a player_characters row matching the fixture's
        // character id. The table is created by
        // SqliteDatabase.initWithConnection (setUp).
        int charId = 0;  // PacketTestFixture default
        try (Statement st = conn.createStatement()) {
            // uuid was added in task #142 as NOT NULL — supply a
            // deterministic test value so the row inserts.
            st.execute("INSERT INTO player_characters "
                    + "(id, name, location, uuid) VALUES ("
                    + charId + ", 'TestChar', 7,"
                    + " '00000000-0000-0000-0000-000000000236')");
        }

        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installPepperP3Zone(pl);
        CapturingUDPConnection.replaceOn(pl);
        // Make the PlayerCharacter id match the row we seeded so
        // the saveCharacter upsert targets the right row.
        pl.getCharacter().setMisc(PlayerCharacter.MISC_ID, charId);

        // Click the sewer entrance → portal commits dest=946.
        new UseItem(buildBody(98304)).execute(pl);
        fireCrossCommitIfQueued(pl);

        // In-memory state: location committed.
        assertEquals(946, pl.getCharacter()
                .getMisc(PlayerCharacter.MISC_LOCATION));

        // DB state: the row must have been UPDATED to 946 — this
        // is what the post-reconnect WorldEntry will see.
        try (Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                 "SELECT location FROM player_characters WHERE id="
                 + charId)) {
            assertTrue("player_characters row must exist", rs.next());
            int dbLocation = rs.getInt(1);
            assertEquals("portal-cross must flush new location to "
                    + "DB before the client reconnects — the "
                    + "in-memory PlayerCharacter cache cannot be "
                    + "relied on across the post-cross login (task "
                    + "#236)",
                    946, dbLocation);
        }
    }
}
