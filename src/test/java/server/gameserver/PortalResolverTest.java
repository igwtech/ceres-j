package server.gameserver;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.SqliteDatabase;

/**
 * Functional tests for {@link PortalResolver} — the
 * {@code world_objects → worldmodel.def → appplaces.def} 2-table
 * indirection that resolves a furniture/portal world-change actor to
 * its destination zone (doc §2 / TinNS UdpUseObject.cxx:341-475).
 *
 * <p>Boots an in-memory SQLite seeded with the {@code world_objects}
 * and {@code client_defs} schema (the latter mirrors
 * {@link server.database.importer.DefImporter}'s JSON layout: f0=name,
 * worldmodel f2=functionType f3=functionValue, appplaces f1=ExitWorldID
 * f2=ExitWorldEntity f3=SewerLevel) and the concrete §5 rows.
 */
public class PortalResolverTest {

    private Connection conn;

    @Before
    public void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        SqliteDatabase.initWithConnection(conn);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE world_objects ("
                    + " world_path TEXT NOT NULL,"
                    + " object_id BIGINT,"
                    + " worldmodel_id INTEGER)");
            st.execute("CREATE TABLE client_defs ("
                    + " def_name TEXT NOT NULL,"
                    + " entry_id INTEGER NOT NULL,"
                    + " fields TEXT NOT NULL,"
                    + " PRIMARY KEY (def_name, entry_id))");

            // §5: pepper_p3 (worldId 7) object_id 95 → worldmodel
            // 380 (ft 18 sewer entrance, fval 130) → appplaces 130
            // → destWorld 946, Entity 1, appplaces SewerLevel 4.
            st.execute("INSERT INTO world_objects VALUES "
                    + "('worlds/pepper/pak_pepper_p3.dat', 95, 380)");
            // §5: plaza_p1 object 2018 → wm 2018 (ft 20, fval 818)
            // → appplaces 818 → destWorld 1573, Entity 1.
            st.execute("INSERT INTO world_objects VALUES "
                    + "('worlds/plaza/pak_plaza_p1.dat', 818, 2018)");
            // Non-portal furniture (a DOOR, ft 0): must NOT resolve.
            st.execute("INSERT INTO world_objects VALUES "
                    + "('worlds/pepper/pak_pepper_p3.dat', 70, 1)");
            // A real chair: object 529 → worldmodel 10 ("CHAIR",
            // UseFlags 10, ufChair bit set). objectId 529 ⇔ the
            // rawObjectId 0x00084800 byte-pinned from the retail sit
            // pcap ((529+1)*1024 = 542720 = 0x00084800).
            st.execute("INSERT INTO world_objects VALUES "
                    + "('worlds/plaza/pak_plaza_p1.dat', 529, 10)");

            st.execute("INSERT INTO client_defs VALUES "
                    + "('worldmodel', 380, '"
                    + "{\"f0\":\"PEPPER PARK SEWER 7.1 (NORMAL)\","
                    + "\"f1\":66,\"f2\":18,\"f3\":130,\"f4\":0,"
                    + "\"f5\":0,\"f6\":0,\"directive\":\"setentry\"}')");
            st.execute("INSERT INTO client_defs VALUES "
                    + "('worldmodel', 2018, '"
                    + "{\"f0\":\"Reactor Room entrance\","
                    + "\"f1\":66,\"f2\":20,\"f3\":818,\"f4\":0,"
                    + "\"f5\":0,\"f6\":0,\"directive\":\"setentry\"}')");
            st.execute("INSERT INTO client_defs VALUES "
                    + "('worldmodel', 1, '"
                    + "{\"f0\":\"DOOR\",\"f1\":2,\"f2\":0,\"f3\":0,"
                    + "\"f4\":0,\"f5\":0,\"f6\":0,"
                    + "\"directive\":\"setentry\"}')");
            // worldmodel.def entry 10 "CHAIR": UseFlags (f1) = 10,
            // which has the ufChair bit (8) set (10 & 8 = 8).
            // Mirrors the live worldmodel.def line
            //   setentry 10 "CHAIR" 10 0 0 0 0
            st.execute("INSERT INTO client_defs VALUES "
                    + "('worldmodel', 10, '"
                    + "{\"f0\":\"CHAIR\",\"f1\":10,\"f2\":0,"
                    + "\"f3\":0,\"f4\":0,\"f5\":0,\"f6\":0,"
                    + "\"directive\":\"setentry\"}')");
            st.execute("INSERT INTO client_defs VALUES "
                    + "('appplaces', 130, '"
                    + "{\"f0\":\"sewer entrance\",\"f1\":946,"
                    + "\"f2\":1,\"f3\":4,\"directive\":\"setentry\"}')");
            st.execute("INSERT INTO client_defs VALUES "
                    + "('appplaces', 818, '"
                    + "{\"f0\":\"Reactor Room entrance\",\"f1\":1573,"
                    + "\"f2\":1,\"f3\":0,\"directive\":\"setentry\"}')");
        }
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    @Test
    public void worldnameToObjectPathMapsDottedToDatPath() {
        assertEquals("worlds/pepper/pak_pepper_p3.dat",
                PortalResolver.worldnameToObjectPath(
                        "pepper/pepper_p3"));
        assertEquals("worlds/plaza/pak_plaza_p1.dat",
                PortalResolver.worldnameToObjectPath(
                        "plaza/plaza_p1"));
        assertNull(PortalResolver.worldnameToObjectPath(null));
        assertNull(PortalResolver.worldnameToObjectPath("noslash"));
    }

    @Test
    public void resolvesPepperP3SewerEntrance() {
        // The §5 ground-truth row the byte-identity test pins.
        PortalResolver.Portal p = PortalResolver.resolve(
                "worlds/pepper/pak_pepper_p3.dat", 95);
        assertNotNull("pepper_p3 object 95 must resolve", p);
        assertEquals(380, p.worldmodelId);
        assertEquals(18,  p.functionType);
        assertEquals(130, p.functionValue);
        assertEquals(946, p.exitWorldId);
        assertEquals(1,   p.exitWorldEntity);
        assertEquals(4,   p.sewerLevelField); // appplaces field
        // ft 18 (NOT 20/29) ⇒ TinNS entityType wire byte = 0.
        assertEquals(0,   p.entityTypeByte);
    }

    @Test
    public void datfileWorldchangeActorHasEntityTypeOne() {
        // ft 20 ⇒ TinNS SewerLevel/entityType = 1.
        PortalResolver.Portal p = PortalResolver.resolve(
                "worlds/plaza/pak_plaza_p1.dat", 818);
        assertNotNull(p);
        assertEquals(20,   p.functionType);
        assertEquals(1573, p.exitWorldId);
        assertEquals(1,    p.exitWorldEntity);
        assertEquals(1,    p.entityTypeByte);
    }

    @Test
    public void nonPortalFurnitureDoesNotResolve() {
        // worldmodel 1 (a DOOR, functionType 0) is not a
        // zone-change type → resolve() returns null.
        assertNull(PortalResolver.resolve(
                "worlds/pepper/pak_pepper_p3.dat", 70));
    }

    @Test
    public void unknownObjectDoesNotResolve() {
        assertNull(PortalResolver.resolve(
                "worlds/pepper/pak_pepper_p3.dat", 99999));
        assertNull(PortalResolver.resolve(null, 95));
    }

    @Test
    public void chairWorldmodelIsDetectedByUseFlags() {
        // plaza_p1 object 529 → worldmodel 10 "CHAIR" UseFlags 10
        // (10 & 8 = ufChair). This is the exact object byte-pinned
        // from RETAIL_LIVE_p1p3_sit_npc_20260517.pcap.
        assertTrue("worldmodel 10 (UseFlags 10) is a chair",
                PortalResolver.isChair(
                        "worlds/plaza/pak_plaza_p1.dat", 529));
    }

    @Test
    public void doorWorldmodelIsNotAChair() {
        // worldmodel 1 "DOOR" UseFlags 2 (2 & 8 = 0) — not a chair.
        assertFalse(PortalResolver.isChair(
                "worlds/pepper/pak_pepper_p3.dat", 70));
    }

    @Test
    public void portalWorldmodelIsNotAChair() {
        // worldmodel 380 sewer entrance UseFlags 66 (66 & 8 = 0).
        assertFalse(PortalResolver.isChair(
                "worlds/pepper/pak_pepper_p3.dat", 95));
    }

    @Test
    public void unknownObjectIsNotAChair() {
        assertFalse(PortalResolver.isChair(
                "worlds/pepper/pak_pepper_p3.dat", 99999));
        assertFalse(PortalResolver.isChair(null, 529));
    }

    @Test
    public void zoneChangeFunctionTypePredicate() {
        assertTrue(PortalResolver.isZoneChangeFunctionType(15));
        assertTrue(PortalResolver.isZoneChangeFunctionType(18));
        assertTrue(PortalResolver.isZoneChangeFunctionType(20));
        assertTrue(PortalResolver.isZoneChangeFunctionType(29));
        assertFalse(PortalResolver.isZoneChangeFunctionType(0));
        assertFalse(PortalResolver.isZoneChangeFunctionType(6));
        assertFalse(PortalResolver.isZoneChangeFunctionType(14));
    }
}
