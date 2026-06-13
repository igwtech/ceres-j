package server.gameserver.packets.server_tcp;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.SqliteDatabase;
import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.networktools.PacketBuilderTCP;

/**
 * Byte-identity test for {@link Location} (TCP S→C 0x83 0x0c).
 *
 * <p>Wire format Ceres-J emits (matches retail catalog sample
 * #2: {@code 830c 65000000 0000000000000000 706c617a612f706c617a615f703300}):
 *
 * <pre>
 *   offset 0..1   : opcode 83 0c
 *   offset 2..5   : location LE32
 *   offset 6..9   : reserved LE32  (0; or 1 when location == 9999)
 *   offset 10..13 : worldflags LE32  (per-zone security bitmask)
 *   offset 14..N  : worldname ASCII
 *   offset N+1    : 0x00     null terminator
 * </pre>
 *
 * <p>The 9999 special case substitutes {@code apps/clean/plaza_app_4_c}
 * for the worldname and writes {@code reserved=1, worldflags=0}.
 *
 * <p><b>Field 3 (offset 10..13) = WORLDFLAGS</b> — re-decoded
 * 2026-06-12. Retail sample #1 (location=1 plaza_p1) has byte 10 =
 * {@code 0x10} because plaza is a SECURE sector (bit 0x10); pepper_p1
 * (PvP) has 0x00. The old "pin Ceres at 0x00" stance was the bug that
 * blocked weapon draw. Flags resolve from {@code defs.worldinfo.f3}
 * (+ NC2.5 overrides) via {@link server.gameserver.PortalResolver
 * #lookupWorldFlags(int)}, so the retail-byte tests seed an in-memory
 * {@code client_defs}.
 */
public class LocationByteIdentityTest {

    private Connection conn;

    @Before
    public void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        SqliteDatabase.initWithConnection(conn);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS client_defs ("
                    + " def_name TEXT NOT NULL,"
                    + " entry_id INTEGER NOT NULL,"
                    + " fields TEXT NOT NULL,"
                    + " PRIMARY KEY (def_name, entry_id))");
        }
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    /** Seed a {@code defs.worldinfo[zoneId]} row carrying {@code f3}
     *  (the worldflags column the retail server reads). */
    private void seedWorldinfoFlags(int zoneId, int f3) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT OR REPLACE INTO client_defs VALUES ("
                    + "'worldinfo', " + zoneId + ", '"
                    + "{\"f0\":\"ZONE" + zoneId + "\",\"f1\":0,"
                    + "\"f2\":\" \",\"f3\":" + f3 + ",\"f4\":0,"
                    + "\"directive\":\"setentry\"}')");
        }
    }

    private static byte[] wireBytes(PacketBuilderTCP pkt) {
        byte[] data = pkt.getData();
        int n = pkt.size();
        byte[] sliced = new byte[n];
        System.arraycopy(data, 0, sliced, 0, n);
        return sliced;
    }

    /** Body starts at offset 3 (after FE-frame header at 0..2). */
    private static byte[] extractBody(byte[] wire, int len) {
        assertEquals((byte) 0xfe, wire[0]);
        byte[] body = new byte[len];
        System.arraycopy(wire, 3, body, 0, len);
        return body;
    }

    @Test
    public void plazaP3LayoutMatchesRetailSample2() {
        // Retail sample #2: location=0x65 (101), worldname=
        // "plaza/plaza_p3". Ceres-J's null-Zone fallback emits an
        // empty worldname, so to byte-equal the retail sample we
        // need to register a Zone with the right worldname or
        // fake it via a player-state setup. The fixture player
        // has currentZone=null, so the path falls through to the
        // empty-string branch — that path is exercised here.
        Player pl = PacketTestFixture.newPlayer();
        pl.getCharacter().setMisc(
                PlayerCharacter.MISC_LOCATION, 0x65);

        byte[] body = extractBody(wireBytes(new Location(pl)), 14 + 0 + 1);
        // [0..1] opcode
        assertEquals(0x83, body[0] & 0xFF);
        assertEquals(0x0c, body[1] & 0xFF);
        // [2..5] location LE32 = 0x65
        assertEquals(0x65, body[2] & 0xFF);
        assertEquals(0x00, body[3] & 0xFF);
        assertEquals(0x00, body[4] & 0xFF);
        assertEquals(0x00, body[5] & 0xFF);
        // [6..9] flag LE32 = 0 (location != 9999)
        for (int i = 6; i <= 9; i++) {
            assertEquals("flag byte " + i,
                    0x00, body[i] & 0xFF);
        }
        // [10..13] zero LE32
        for (int i = 10; i <= 13; i++) {
            assertEquals("zero byte " + i,
                    0x00, body[i] & 0xFF);
        }
        // [14] null terminator (empty worldname)
        assertEquals("null terminator at offset 14",
                0x00, body[14] & 0xFF);
    }

    @Test
    public void location9999SetsFlagToOneAndWritesAppPath() {
        // Special-case: location == 9999 → emit flag=1 and the
        // hardcoded worldname "apps/clean/plaza_app_4_c".
        Player pl = PacketTestFixture.newPlayer();
        pl.getCharacter().setMisc(
                PlayerCharacter.MISC_LOCATION, 9999);

        String expectedName = "apps/clean/plaza_app_4_c";
        byte[] body = extractBody(wireBytes(new Location(pl)),
                14 + expectedName.length() + 1);

        // location LE32 = 9999 = 0x0000270f
        assertEquals(0x0f, body[2] & 0xFF);
        assertEquals(0x27, body[3] & 0xFF);
        assertEquals(0x00, body[4] & 0xFF);
        assertEquals(0x00, body[5] & 0xFF);
        // flag LE32 = 1
        assertEquals(0x01, body[6] & 0xFF);
        assertEquals(0x00, body[7] & 0xFF);
        assertEquals(0x00, body[8] & 0xFF);
        assertEquals(0x00, body[9] & 0xFF);
        // worldname starts at offset 14
        byte[] expectedNameBytes = expectedName
                .getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < expectedNameBytes.length; i++) {
            assertEquals("name byte " + i,
                    expectedNameBytes[i],
                    body[14 + i]);
        }
        // null terminator after the name
        assertEquals(0x00,
                body[14 + expectedNameBytes.length] & 0xFF);
    }

    @Test
    public void locationFieldEncodesLittleEndian() {
        Player pl = PacketTestFixture.newPlayer();
        pl.getCharacter().setMisc(
                PlayerCharacter.MISC_LOCATION, 0x12345678);

        byte[] body = extractBody(wireBytes(new Location(pl)), 15);
        assertEquals(0x78, body[2] & 0xFF);
        assertEquals(0x56, body[3] & 0xFF);
        assertEquals(0x34, body[4] & 0xFF);
        assertEquals(0x12, body[5] & 0xFF);
    }

    @Test
    public void nullZoneFallsThroughToEmptyName() {
        // Fixture player has no Zone → emitter's null-safe path
        // emits empty worldname (just the null terminator).
        // Pin this behaviour so a future refactor doesn't
        // accidentally NPE here.
        Player pl = PacketTestFixture.newPlayer();
        pl.getCharacter().setMisc(
                PlayerCharacter.MISC_LOCATION, 0x65);
        assertNull("fixture player has no zone",
                pl.getZone());

        byte[] body = extractBody(wireBytes(new Location(pl)), 15);
        // 14 bytes header + 1 byte null terminator = 15 bytes body
        assertEquals(0x00, body[14] & 0xFF);
    }

    @Test
    public void totalWireSizeIsHeaderPlusNamePlusFraming() {
        // 3-byte FE frame + 14-byte header + name + 1-byte null
        Player pl = PacketTestFixture.newPlayer();
        pl.getCharacter().setMisc(
                PlayerCharacter.MISC_LOCATION, 0x65);
        // Empty worldname (null Zone): total = 3 + 14 + 0 + 1 = 18
        assertEquals(18, new Location(pl).size());
    }

    @Test
    public void location9999TotalWireSize() {
        // 3 + 14 + 24 ("apps/clean/plaza_app_4_c") + 1 = 42
        Player pl = PacketTestFixture.newPlayer();
        pl.getCharacter().setMisc(
                PlayerCharacter.MISC_LOCATION, 9999);
        assertEquals(42, new Location(pl).size());
    }

    // ──────────────────────────────────────── retail byte-identity
    // assertions. Field 3 = per-zone WORLDFLAGS (re-decoded
    // 2026-06-12; the old #252 "spawnIdx" reading was a coincidence
    // collapse — see Location javadoc). Flags resolve from
    // defs.worldinfo.f3 via PortalResolver.lookupWorldFlags, so
    // these tests seed an in-memory client_defs.

    @Test
    public void retailReaktorCrossInBytesIdentical() throws Exception {
        // Captured retail bytes for cross-IN to "startmissions/reaktor"
        // (zoneId=1573, worldflags=0x01 dungeon). Source: pcap RETRY3
        // frame 312.
        //
        //   fe 24 00 83 0c 25 06 00 00 00 00 00 00 00 01 00 00 00
        //   "startmissions/reaktor\0"
        //
        // Total wire: 3 (frame) + 14 (header) + 21 (path) + 1 (\0) = 39 bytes.
        seedWorldinfoFlags(1573, 1);
        Player pl = buildPlayerWithZone(1573, "startmissions/reaktor");

        byte[] wire = wireBytes(new Location(pl));

        byte[] expected = hex(
                "fe 24 00 83 0c " +
                "25 06 00 00 " +   // destZoneId LE32 = 1573
                "00 00 00 00 " +   // reserved
                "01 00 00 00 " +   // worldflags LE32 = 0x01 (dungeon)
                "73 74 61 72 74 6d 69 73 73 69 6f 6e 73 " + // startmissions
                "2f " +            // '/'
                "72 65 61 6b 74 6f 72 " + // reaktor
                "00");
        assertArrayEquals("retail reaktor cross-in bytes",
                expected, wire);
    }

    @Test
    public void retailPlazaSpawnBytesIdentical() throws Exception {
        // Captured retail bytes for plaza_p1 (zoneId=1,
        // worldflags=0x10 SECURE) — identical for login spawn,
        // cross-OUT from reaktor, AND plain walk-crosses
        // (PLAZA_CROSSZONE pcap ×3). Source: pcap RETRY3 frames 53 + 5906.
        //
        //   fe 1d 00 83 0c 01 00 00 00 00 00 00 00 10 00 00 00
        //   "plaza/plaza_p1\0"
        //
        // Total wire: 3 + 14 + 14 + 1 = 32 bytes.
        seedWorldinfoFlags(1, 16);
        Player pl = buildPlayerWithZone(1, "plaza/plaza_p1");

        byte[] wire = wireBytes(new Location(pl));

        byte[] expected = hex(
                "fe 1d 00 83 0c " +
                "01 00 00 00 " +   // destZoneId LE32 = 1
                "00 00 00 00 " +   // reserved
                "10 00 00 00 " +   // worldflags LE32 = 0x10 (SECURE)
                "70 6c 61 7a 61 2f 70 6c 61 7a 61 5f 70 31 " + // plaza/plaza_p1
                "00");
        assertArrayEquals("retail plaza_p1 spawn bytes",
                expected, wire);
    }

    @Test
    public void retailPepperEntryBytesIdentical() throws Exception {
        // The quickbelt draw/holster fix: pepper_p1 (zoneId=5) is a
        // PvP/outskirt zone — worldflags MUST be 0 (defs f3=0, no
        // override) or the client prints "Secure Sector" and refuses
        // weapon draw. Source: PLAZA_TO_PEPPER_CROSS_DISTRICT +
        // VEHICLE_DRONE + LONG_PARTY_A/B pcaps (5 entries):
        //
        //   fe 1f 00 83 0c 05 00 00 00 00 00 00 00 00 00 00 00
        //   "pepper/pepper_p1\0"
        seedWorldinfoFlags(5, 0);
        Player pl = buildPlayerWithZone(5, "pepper/pepper_p1");

        byte[] wire = wireBytes(new Location(pl));

        byte[] expected = hex(
                "fe 1f 00 83 0c " +
                "05 00 00 00 " +   // destZoneId LE32 = 5
                "00 00 00 00 " +   // reserved
                "00 00 00 00 " +   // worldflags LE32 = 0 (PvP/outskirt)
                "70 65 70 70 65 72 2f 70 65 70 70 65 72 5f 70 31 " + // pepper/pepper_p1
                "00");
        assertArrayEquals("retail pepper_p1 entry bytes",
                expected, wire);
    }

    @Test
    public void nc25OverridesBeatStaleClientDefs() throws Exception {
        // NC2.5 retail overrides where the client-shipped defs are
        // stale: plaza_p2 (id 2) defs say 0 but retail wire says
        // 0x10 (LONG_PARTY_A/B); outzone_station (id 24) defs say
        // 0x10 but retail wire says 0x400 warzone (VEHICLE_DRONE).
        seedWorldinfoFlags(2, 0);
        Player p2 = buildPlayerWithZone(2, "plaza/plaza_p2");
        byte[] body2 = extractBody(wireBytes(new Location(p2)),
                14 + "plaza/plaza_p2".length() + 1);
        assertEquals("plaza_p2 override flags byte", 0x10, body2[10] & 0xFF);
        assertEquals(0x00, body2[11] & 0xFF);

        seedWorldinfoFlags(24, 16);
        Player p24 = buildPlayerWithZone(24, "outzone/outzone_station");
        byte[] body24 = extractBody(wireBytes(new Location(p24)),
                14 + "outzone/outzone_station".length() + 1);
        assertEquals("outzone_station override low byte", 0x00, body24[10] & 0xFF);
        assertEquals("outzone_station override 0x400 high byte",
                0x04, body24[11] & 0xFF);
    }

    @Test
    public void unknownZoneIdEmitsFlagsZero() {
        // No worldinfo row for the zone → flags fall back to 0
        // (non-secure; the client never defaults to secure).
        Player pl = PacketTestFixture.newPlayer();
        pl.getCharacter().setMisc(PlayerCharacter.MISC_LOCATION, 777);

        byte[] body = extractBody(wireBytes(new Location(pl)), 15);
        for (int i = 10; i <= 13; i++) {
            assertEquals("fallback worldflags byte " + i + " must be 0",
                    0x00, body[i] & 0xFF);
        }
    }

    // ──────────────────────────────────────── helpers

    /** Build a Player whose Zone returns the given worldname and
     *  whose MISC_LOCATION is set. Uses reflection to install a
     *  Zone without booting ZoneManager/NPC system. */
    private static Player buildPlayerWithZone(int zoneId,
                                               String worldname)
            throws Exception {
        Player pl = PacketTestFixture.newPlayer();
        pl.getCharacter().setMisc(
                PlayerCharacter.MISC_LOCATION, zoneId);
        server.gameserver.Zone z =
                new server.gameserver.Zone(zoneId, worldname);
        java.lang.reflect.Field f =
                Player.class.getDeclaredField("currentZone");
        f.setAccessible(true);
        f.set(pl, z);
        return pl;
    }

    /** Parse a "fe 24 00 ..." style hex literal into bytes. */
    private static byte[] hex(String s) {
        String c = s.replaceAll("\\s+", "");
        byte[] out = new byte[c.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(
                    c.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
