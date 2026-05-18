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
}
