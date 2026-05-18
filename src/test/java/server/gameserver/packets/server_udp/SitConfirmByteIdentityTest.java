package server.gameserver.packets.server_udp;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.SqliteDatabase;
import server.gameserver.Player;
import server.gameserver.Zone;
import server.gameserver.ZoneManager;
import server.gameserver.packets.client_udp.UseItem;
import server.interfaces.ServerUDPPacket;
import server.testtools.CapturingUDPConnection;

/**
 * Byte-identity + functional tests for the first-time chair-sit
 * confirmation echo ({@link SitConfirm}) — the per-actor
 * {@code 0x03/0x1f} sub-action {@code 0x17} that the retail server
 * sends back to the acting player so its <em>local</em> client
 * actually sits.
 *
 * <h3>Ground truth</h3>
 *
 * <p>Byte-pinned from
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} (server
 * 157.90.195.74), decoded with {@code tools/pcap-decode.py}
 * (RE spec {@code docs/protocol/RE_tcp_confirm.md} §3.2(a), §3.4):
 *
 * <pre>
 *   t=199.386941  C→S  reliable 03 &lt;seq2&gt; 1f 03 00 17 00 c8 0c 00
 *   t=199.833702  S→C  reliable 03 &lt;seq2&gt; 1f 03 00 17 00 c8 0c 00
 * </pre>
 *
 * <p>The S→C inner body is byte-identical to the C→S request:
 * {@code 1f <localId LE2> 17 <rawObjectId LE32>} (8 bytes, no
 * seatId — unlike the {@code 0x21} {@link SitOnChair} broadcast).
 */
public class SitConfirmByteIdentityTest {

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
            st.execute("INSERT INTO world_objects VALUES "
                    + "('worlds/plaza/pak_plaza_p1.dat', 529, 10)");
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

    /** Plaza_p1 zone with the player at mapID 3 (retail localId). */
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

    /** Inner game body (post {@code 03 seq2}): starts at offset 10. */
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

    /**
     * Direct builder byte-identity: {@link SitConfirm} must emit the
     * exact retail {@code 0x17} echo inner bytes for the pcap chair
     * id 0x000cc800 (localId 3).
     */
    @Test
    public void sitConfirmEqualsRetailEchoBytes() throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        // pcap chair: C→S 1f 03 00 17 00 c8 0c 00 → id LE32 0x000cc800
        SitConfirm sc = new SitConfirm(pl, 3, 0x000cc800);
        byte[] body = innerBody(sc, 8);
        byte[] expected = {
                0x1f,
                0x03, 0x00,                    // localId LE16 = 3
                0x17,                          // first-sit echo tag
                0x00, (byte) 0xc8, 0x0c, 0x00  // rawObjectId LE32
        };
        assertArrayEquals(
                "SitConfirm bytes must equal the retail 0x17 echo "
                + "(pcap t=199.833702: 1f 03 00 17 00 c8 0c 00)",
                expected, body);
        // No seatId byte (the 0x17 echo is 8 bytes; the 0x21
        // broadcast is 9).
        assertEquals(8, innerBody(sc, 8).length);
    }

    /**
     * First sit on a chair → the acting player receives BOTH the
     * {@code 0x17} echo ({@link SitConfirm}) AND the {@code 0x21}
     * posture broadcast ({@link SitOnChair}). The {@code 0x17} echo
     * is byte-identical to the client's {@code 0x17} use request.
     */
    @Test
    public void firstSitEmitsBoth0x17EchoAnd0x21Broadcast()
            throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installPlazaP1Zone(pl);
        CapturingUDPConnection cap =
                CapturingUDPConnection.replaceOn(pl);

        new UseItem(useBody(CHAIR_RAW_ID)).execute(pl);

        assertTrue(pl.isSeated());

        SitConfirm echo = null;
        SitOnChair bcast = null;
        for (ServerUDPPacket p : cap.received()) {
            if (p instanceof SitConfirm)  echo  = (SitConfirm) p;
            if (p instanceof SitOnChair)  bcast = (SitOnChair) p;
        }
        assertNotNull("first sit must emit the 0x17 echo to the "
                + "acting player (SitConfirm)", echo);
        assertNotNull("first sit must still emit the 0x21 posture "
                + "broadcast (SitOnChair)", bcast);

        // 0x17 echo byte-identical to the C→S request inner:
        //   1f 03 00 17 00 48 08 00
        byte[] e = innerBody(echo, 8);
        byte[] expectedEcho = {
                0x1f, 0x03, 0x00, 0x17,
                0x00, 0x48, 0x08, 0x00
        };
        assertArrayEquals("0x17 echo must be byte-identical to the "
                + "client's 0x17 use request", expectedEcho, e);

        // 0x21 broadcast unchanged: 1f 03 00 21 00 48 08 00 00.
        byte[] b = innerBody(bcast, 9);
        byte[] expectedBcast = {
                0x1f, 0x03, 0x00, 0x21,
                0x00, 0x48, 0x08, 0x00, 0x00
        };
        assertArrayEquals("0x21 posture broadcast must be unchanged",
                expectedBcast, b);

        // The two carriers are distinct sub-actions on the same
        // 0x03/0x1f channel — pin the difference so a future
        // "merge into one class" refactor can't regress the fix.
        assertEquals(0x17, e[3] & 0xFF);
        assertEquals(0x21, b[3] & 0xFF);
    }

    /**
     * Retail sends the {@code 0x17} echo once per NEW object, then
     * {@code 0x21} for refresh/observers (RE spec §3.4). Re-using the
     * same chair (the retail keepalive) must NOT re-emit a
     * {@code 0x17} echo — only the {@code 0x21} broadcast repeats.
     */
    @Test
    public void reUsingSameChairDoesNotReEmit0x17Echo()
            throws Exception {
        Player pl = PacketTestFixture
                .newPlayerWithFixedSessionKey((short) 0);
        installPlazaP1Zone(pl);
        CapturingUDPConnection cap =
                CapturingUDPConnection.replaceOn(pl);

        new UseItem(useBody(CHAIR_RAW_ID)).execute(pl); // first sit
        new UseItem(useBody(CHAIR_RAW_ID)).execute(pl); // keepalive

        int echoes = 0, bcasts = 0;
        for (ServerUDPPacket p : cap.received()) {
            if (p instanceof SitConfirm) echoes++;
            if (p instanceof SitOnChair) bcasts++;
        }
        assertEquals("0x17 echo must be emitted ONCE per new object",
                1, echoes);
        assertEquals("0x21 broadcast repeats on every chair use",
                2, bcasts);
    }
}
