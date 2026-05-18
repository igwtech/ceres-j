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
import server.gameserver.packets.server_udp.ExitSeat;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.gameserver.packets.server_udp.SitOnChair;
import server.interfaces.ServerUDPPacket;
import server.testtools.CapturingUDPConnection;

/**
 * Byte-identity + functional tests for the chair-sit interaction
 * (task #185).
 *
 * <h3>Ground truth</h3>
 *
 * <p>All wire bytes are pinned from
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} (server
 * 157.90.195.74), reliable-channel deframed:
 *
 * <ul>
 *   <li>t=41.995s C→S {@code 03 <seq> 1f 03 00 17 00 48 08 00}
 *       — UseItem on chair, rawObjectId {@code 0x00084800}.</li>
 *   <li>t=42.368s S→C {@code 03 <seq> 1f 03 00 21 00 48 08 00 00}
 *       — SitOnChair broadcast, localId {@code 0x0003}, tag 0x21,
 *       rawObjectId echoed, seatId 0.</li>
 *   <li>t=44.560s S→C {@code 03 <seq> 1f 03 00 22 ...} — ExitSeat
 *       broadcast (tag 0x22).</li>
 * </ul>
 *
 * <p>UseItem decodes the rawObjectId as {@code id}; objectId =
 * {@code id/1024 - 1} = {@code 542720/1024 - 1 = 529}. worldmodel.def
 * entry 10 "CHAIR" has UseFlags 10 (ufChair bit 8 set).
 */
public class SitOnChairTest {

    /** rawObjectId byte-pinned from the retail pcap: 0x00084800. */
    private static final int CHAIR_RAW_ID = 0x00084800;

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
                    + " def_name TEXT NOT NULL,"
                    + " entry_id INTEGER NOT NULL,"
                    + " fields TEXT NOT NULL,"
                    + " PRIMARY KEY (def_name, entry_id))");
            // objectId 529 ⇔ rawObjectId 0x00084800.
            st.execute("INSERT INTO world_objects VALUES "
                    + "('worlds/plaza/pak_plaza_p1.dat', 529, 10)");
            // worldmodel 10 "CHAIR", UseFlags 10 (ufChair set).
            st.execute("INSERT INTO client_defs VALUES "
                    + "('worldmodel', 10, '"
                    + "{\"f0\":\"CHAIR\",\"f1\":10,\"f2\":0,"
                    + "\"f3\":0,\"f4\":0,\"f5\":0,\"f6\":0,"
                    + "\"directive\":\"setentry\"}')");
        }
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    /** Install a plaza_p1 zone whose dotted worldname maps to
     *  {@code worlds/plaza/pak_plaza_p1.dat}, and register the
     *  player in it with localId 0x0003 (the retail capture's
     *  localId). */
    private static Zone installPlazaP1Zone(Player pl) throws Exception {
        Zone z = new Zone(1, "plaza/plaza_p1");
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
        zoneList.put(1, z);
        // Place the player in the zone at mapID 3 (the retail
        // capture's localId 0x0003). Inject directly into the
        // zone's playerList so the byte-pinned localId is exact —
        // Zone.registerPlayer() would auto-assign slot 1.
        pl.setMapID(3);
        java.lang.reflect.Field plField =
                Zone.class.getDeclaredField("playerList");
        plField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.TreeMap<Integer, Player> playerList =
                (java.util.TreeMap<Integer, Player>) plField.get(z);
        playerList.put(3, pl);
        return z;
    }

    /** 11-byte UseItem body: 03 <seq2> 1f <sub2> 17 <rawId LE32>. */
    private static byte[] useBody(int rawItemId) {
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

    /** Extract the inner game body (post {@code 03 seq2}) from a
     *  reliable {@code 0x13} datagram: body starts at offset 10,
     *  {@code d[0]==0x13}, {@code d[7]==0x03}. */
    private static byte[] innerBody(ServerUDPPacket p, int len) {
        DatagramPacket[] dps = p.getDatagramPackets();
        byte[] d = new byte[dps[0].getLength()];
        System.arraycopy(dps[0].getData(), 0, d, 0, d.length);
        assertEquals(0x13, d[0] & 0xFF);
        assertEquals(0x03, d[7] & 0xFF);
        byte[] body = new byte[len];
        System.arraycopy(d, 10, body, 0, len);
        return body;
    }

    @Test
    public void useChairSitsPlayerAndEmitsPinnedSitBytes()
            throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        Zone z = installPlazaP1Zone(pl);
        CapturingUDPConnection cap =
                CapturingUDPConnection.replaceOn(pl);

        new UseItem(useBody(CHAIR_RAW_ID)).execute(pl);

        // Player is now seated on the clicked chair.
        assertTrue("UseItem on a chair must set seated state",
                pl.isSeated());
        assertEquals(CHAIR_RAW_ID, pl.getSeatedChairRawId());

        // A SitOnChair broadcast was emitted.
        SitOnChair sit = null;
        for (ServerUDPPacket p : cap.received()) {
            if (p instanceof SitOnChair) sit = (SitOnChair) p;
        }
        assertNotNull("UseItem on a chair must emit SitOnChair", sit);

        // Byte-identity vs the retail pcap sample (t=42.368s):
        //   1f 03 00 21 00 48 08 00 00
        byte[] body = innerBody(sit, 9);
        byte[] expected = {
                0x1f,
                0x03, 0x00,                    // localId LE16 = 3
                0x21,                          // sit tag
                0x00, 0x48, 0x08, 0x00,        // rawObjectId LE32
                0x00                           // seatId (real chair)
        };
        assertArrayEquals(
                "SitOnChair bytes must equal the retail pcap sample",
                expected, body);
        assertNotNull(z);
    }

    @Test
    public void movingWhileSeatedStandsUpAndEmitsExitSeat()
            throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installPlazaP1Zone(pl);
        CapturingUDPConnection cap =
                CapturingUDPConnection.replaceOn(pl);

        // Sit first.
        new UseItem(useBody(CHAIR_RAW_ID)).execute(pl);
        assertTrue(pl.isSeated());

        // A minimal movement packet: 03 <seq2> 20 <type=0x00>.
        // type 0 = no axes/orientation/status fields follow; the
        // stand-up branch fires before any field decode.
        byte[] move = { 0x03, 0x10, 0x00, 0x20, 0x00 };
        new Movement(move).execute(pl);

        // Player stood up.
        assertFalse("moving while seated must clear seated state",
                pl.isSeated());

        // An ExitSeat broadcast was emitted, structurally pinned to
        // the retail 0x22 frame: 1f <localId LE2> 22 + 6 coord bytes
        // + 3 orientation bytes (13 bytes total).
        ExitSeat exit = null;
        for (ServerUDPPacket p : cap.received()) {
            if (p instanceof ExitSeat) exit = (ExitSeat) p;
        }
        assertNotNull("standing up must emit ExitSeat", exit);
        byte[] body = innerBody(exit, 13);
        assertEquals(0x1f, body[0] & 0xFF);
        assertEquals(0x03, body[1] & 0xFF);   // localId lo = 3
        assertEquals(0x00, body[2] & 0xFF);   // localId hi
        assertEquals(0x22, body[3] & 0xFF);   // exit-seat tag
        // Coords are (worldUnit + 768) & 0xFFFF LE per axis: the
        // fixture sets Y=200, Z=300, X=100.
        assertEquals(200 + 768, (body[4] & 0xFF)
                | ((body[5] & 0xFF) << 8));
        assertEquals(300 + 768, (body[6] & 0xFF)
                | ((body[7] & 0xFF) << 8));
        assertEquals(100 + 768, (body[8] & 0xFF)
                | ((body[9] & 0xFF) << 8));
    }

    /**
     * Regression for the "client never visibly sits" bug.
     *
     * <p>While seated the retail client sends a continuous
     * <em>seated-anchor sync</em> {@code 0x20} (type byte
     * {@code 0x80}, payload = the seated chair's rawObjectId),
     * byte-pinned from
     * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} t=204.619s
     * as {@code 20 03 00 80 00 48 08 00 00}. The old code stood the
     * player up on <em>any</em> {@code 0x20}, so this keepalive
     * cleared the seat within a frame of the {@code 0x21} sit
     * broadcast and the chair-sit was never visible. The seated-anchor
     * sync must be a no-op: player stays seated, no {@link ExitSeat}.
     */
    @Test
    public void seatedAnchorSyncDoesNotStandUp() throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installPlazaP1Zone(pl);
        CapturingUDPConnection cap =
                CapturingUDPConnection.replaceOn(pl);

        new UseItem(useBody(CHAIR_RAW_ID)).execute(pl);
        assertTrue(pl.isSeated());

        // Retail seated-anchor sync, reliable-wrapped:
        //   03 <seq2> 20 <localId LE2> 80 <chair rawObjectId LE4> 00
        // = 03 11 00 | 20 03 00 80 00 48 08 00 00
        byte[] anchor = {
                0x03, 0x11, 0x00,              // reliable 03 + seq LE2
                0x20,                          // 0x20 movement channel
                0x03, 0x00,                    // localId LE2 = 3
                (byte) 0x80,                   // type = seated anchor
                0x00, 0x48, 0x08, 0x00,        // chair rawObjectId LE4
                0x00                           // trailer
        };
        new Movement(anchor).execute(pl);

        // Still seated — the anchor sync is a no-op. No ExitSeat has
        // been emitted yet (only the SitOnChair from the UseItem).
        assertTrue("seated-anchor 0x20 sync must NOT stand the "
                + "player up", pl.isSeated());
        assertEquals(CHAIR_RAW_ID, pl.getSeatedChairRawId());
        for (ServerUDPPacket p : cap.received()) {
            assertFalse("seated-anchor sync must not emit ExitSeat",
                    p instanceof ExitSeat);
        }

        // A subsequent REAL locomotion 0x20 (no 0x80 type) still
        // stands the player up + emits ExitSeat (retail behaviour
        // preserved).
        byte[] move = { 0x03, 0x12, 0x00, 0x20, 0x00 };
        new Movement(move).execute(pl);
        assertFalse("real locomotion 0x20 must still stand up",
                pl.isSeated());
        boolean exited = false;
        for (ServerUDPPacket p : cap.received()) {
            if (p instanceof ExitSeat) exited = true;
        }
        assertTrue("real locomotion after seated must emit ExitSeat",
                exited);
    }

    @Test
    public void explicitExitSeatRequestStandsUp() throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installPlazaP1Zone(pl);
        CapturingUDPConnection cap =
                CapturingUDPConnection.replaceOn(pl);

        new UseItem(useBody(CHAIR_RAW_ID)).execute(pl);
        assertTrue(pl.isSeated());

        // C→S explicit exit-seat: 03 <seq2> 1f 03 00 22 (no body).
        byte[] req = { 0x03, 0x20, 0x00, 0x1f, 0x03, 0x00, 0x22 };
        new ExitSeatRequest(req).execute(pl);

        assertFalse(pl.isSeated());
        boolean exitBroadcast = false;
        for (ServerUDPPacket p : cap.received()) {
            if (p instanceof ExitSeat) exitBroadcast = true;
        }
        assertTrue("explicit exit-seat must emit ExitSeat",
                exitBroadcast);
    }

    @Test
    public void reUsingSameChairKeepsSeatedAndRebroadcasts()
            throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installPlazaP1Zone(pl);
        CapturingUDPConnection cap =
                CapturingUDPConnection.replaceOn(pl);

        new UseItem(useBody(CHAIR_RAW_ID)).execute(pl);
        new UseItem(useBody(CHAIR_RAW_ID)).execute(pl); // retail keepalive

        assertTrue(pl.isSeated());
        assertEquals(CHAIR_RAW_ID, pl.getSeatedChairRawId());
        int sits = 0;
        for (ServerUDPPacket p : cap.received()) {
            if (p instanceof SitOnChair) sits++;
        }
        assertEquals("each chair use re-broadcasts the seated pose",
                2, sits);
    }

    @Test
    public void nonChairFurnitureDoesNotSit() throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installPlazaP1Zone(pl);
        CapturingUDPConnection cap =
                CapturingUDPConnection.replaceOn(pl);

        // A furniture-shaped rawItemID with no chair worldmodel row
        // → not a chair → must NOT sit (falls through to door path).
        new UseItem(useBody(777 * 1024)).execute(pl);

        assertFalse(pl.isSeated());
        for (ServerUDPPacket p : cap.received()) {
            assertFalse("non-chair use must not emit SitOnChair",
                    p instanceof SitOnChair);
        }

        List<ServerUDPPacket> sent = cap.received();
        assertNotNull(sent);
    }
}
