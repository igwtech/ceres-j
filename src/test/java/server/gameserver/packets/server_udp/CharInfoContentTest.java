package server.gameserver.packets.server_udp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.database.SqliteDatabase;
import server.database.playerCharacters.PlayerCharacter;
import server.database.playerCharacters.PlayerCharacterManager;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP130307;

/**
 * Content-level tests for the CharInfo UDP packet. Asserts that fields
 * previously hard-coded as literals are now sourced from PlayerCharacter
 * state without disturbing section offsets or wire bytes.
 *
 * <p>Reflection is used to read the package-private {@code complete} field
 * on {@link PacketBuilderUDP130307} and to inject a {@link PlayerCharacter}
 * into a {@link Player} without invoking the zone-registration codepath in
 * {@code setCharacter}.
 */
public class CharInfoContentTest {

    private Connection conn;

    @Before
    public void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        SqliteDatabase.initWithConnection(conn);
    }

    @After
    public void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    // ---------- helpers ----------

    private static PlayerCharacter newCharacter() {
        PlayerCharacter pc = new PlayerCharacter();
        pc.setName("TestChar");
        pc.setMisc(PlayerCharacter.MISC_ID, 1);
        pc.setMisc(PlayerCharacter.MISC_CLASS, 2);       // spy (class/2 = 1)
        pc.setMisc(PlayerCharacter.MISC_PROFESSION, 1);
        pc.setMisc(PlayerCharacter.MISC_FACTION, 1);
        // ItemManager.loadContainer is a simple static-map put; safe for unit tests.
        pc.initContainer(new int[] { 90000, 90001, 90002 });
        return pc;
    }

    private static Player buildPlayer(PlayerCharacter pc) throws Exception {
        Player p = new Player(null);
        Field f = Player.class.getDeclaredField("pc");
        f.setAccessible(true);
        f.set(p, pc);
        return p;
    }

    /**
     * Walk the bytes accumulated in PacketBuilderUDP130307.complete (after a
     * final flush via newSection(0)) and split them into a map keyed by
     * section id. Section 0 is the unheadered prelude and is skipped.
     */
    private static Map<Integer, byte[]> extractSections(CharInfo ci) throws Exception {
        ci.newSection(0); // flush the final (trailing) section header + payload
        Field cf = PacketBuilderUDP130307.class.getDeclaredField("complete");
        cf.setAccessible(true);
        ByteArrayOutputStream baos = (ByteArrayOutputStream) cf.get(ci);
        byte[] data = baos.toByteArray();

        Map<Integer, byte[]> out = new HashMap<>();
        int pos = 3; // skip prelude 0x22 0x02 0x01
        while (pos < data.length) {
            int id = data[pos] & 0xff;
            int len = (data[pos + 1] & 0xff) | ((data[pos + 2] & 0xff) << 8);
            byte[] payload = new byte[len];
            System.arraycopy(data, pos + 3, payload, 0, len);
            out.put(id, payload);
            pos += 3 + len;
        }
        return out;
    }

    private static int readShortLE(byte[] arr, int off) {
        return (arr[off] & 0xff) | ((arr[off + 1] & 0xff) << 8);
    }

    private static int readIntLE(byte[] arr, int off) {
        return (arr[off] & 0xff)
            | ((arr[off + 1] & 0xff) << 8)
            | ((arr[off + 2] & 0xff) << 16)
            | ((arr[off + 3] & 0xff) << 24);
    }

    private static float readFloatLE(byte[] arr, int off) {
        return Float.intBitsToFloat(readIntLE(arr, off));
    }

    // ---------- tests ----------

    @Test
    public void testPoolValuesReflected() throws Exception {
        PlayerCharacter pc = newCharacter();
        pc.setHealth(250);
        pc.setMaxHealth(300);
        pc.setPsi(150);
        pc.setMaxPsi(200);
        pc.setStamina(80);
        pc.setMaxStamina(120);
        pc.setSynaptic(42);

        Player p = buildPlayer(pc);
        CharInfo ci = new CharInfo(p);
        Map<Integer, byte[]> sections = extractSections(ci);

        byte[] sec2 = sections.get(2);
        assertNotNull("section 2 pools must exist", sec2);

        // Layout: [write(4), write(4), 6x u16 pools, 5x u16 padding, u8 synaptic, u8 128, u8 0, u8 0]
        assertEquals(250, readShortLE(sec2, 2));   // cur health
        assertEquals(300, readShortLE(sec2, 4));   // max health
        assertEquals(150, readShortLE(sec2, 6));   // cur psi
        assertEquals(200, readShortLE(sec2, 8));   // max psi
        assertEquals(80,  readShortLE(sec2, 10));  // cur stamina
        assertEquals(120, readShortLE(sec2, 12));  // max stamina

        // 5 out-of-scope padding shorts: 255, 255, 101, 101, 101 (offsets 14..23)
        assertEquals(255, readShortLE(sec2, 14));
        assertEquals(255, readShortLE(sec2, 16));
        assertEquals(101, readShortLE(sec2, 18));
        assertEquals(101, readShortLE(sec2, 20));
        assertEquals(101, readShortLE(sec2, 22));

        // Synaptic byte at offset 24
        assertEquals(42, sec2[24] & 0xff);
    }

    @Test
    public void testCashReflected() throws Exception {
        PlayerCharacter pc = newCharacter();
        pc.setCash(12345);

        Player p = buildPlayer(pc);
        CharInfo ci = new CharInfo(p);
        Map<Integer, byte[]> sections = extractSections(ci);

        byte[] sec8 = sections.get(8);
        assertNotNull("section 8 must exist", sec8);
        // Layout: [write(0x0a), u32 cash, ...]
        assertEquals(0x0a, sec8[0] & 0xff);
        assertEquals(12345, readIntLE(sec8, 1));
    }

    @Test
    public void testRankReflected() throws Exception {
        PlayerCharacter pc = newCharacter();
        pc.setRank(17);

        Player p = buildPlayer(pc);
        CharInfo ci = new CharInfo(p);
        Map<Integer, byte[]> sections = extractSections(ci);

        byte[] sec8 = sections.get(8);
        assertNotNull(sec8);
        // Walk section 8 to the rank byte:
        // 1 (0x0a) + 4 (cash) + 9 (grs/padding) + 2 (tid) + 8 (epic) + 1 (class*2) + 1 (0) + 3 (textures)
        // = 29 bytes before rank
        int rankOffset = 1 + 4 + 9 + 2 + 8 + 1 + 1 + 3;
        assertEquals(17, sec8[rankOffset] & 0xff);
    }

    @Test
    public void testFactionSympathiesReflected() throws Exception {
        PlayerCharacter pc = newCharacter();
        pc.setFactionSympathy(3, 5000.0f);
        pc.setFactionSympathy(10, 7777.5f);

        Player p = buildPlayer(pc);
        CharInfo ci = new CharInfo(p);
        Map<Integer, byte[]> sections = extractSections(ci);

        byte[] sec9 = sections.get(9);
        assertNotNull("section 9 must exist", sec9);
        // Layout: [u16(21), u8(current faction), u8(0), u8(4), 20x f32 sympathies, f32 lowsl, f32 sl pad, f32 unknown pad, u8(current faction)]
        // First 20 floats start at offset 5.
        for (int i = 0; i < 20; i++) {
            float v = readFloatLE(sec9, 5 + i * 4);
            float expected;
            if (i == 3) expected = 5000.0f;
            else if (i == 10) expected = 7777.5f;
            else expected = 10000.0f;
            assertEquals("sympathy[" + i + "]", expected, v, 0.0f);
        }
    }

    @Test
    public void testCurrentFactionReflected() throws Exception {
        PlayerCharacter pc = newCharacter();
        pc.setMisc(PlayerCharacter.MISC_FACTION, 7);

        Player p = buildPlayer(pc);
        CharInfo ci = new CharInfo(p);
        Map<Integer, byte[]> sections = extractSections(ci);

        byte[] sec9 = sections.get(9);
        assertNotNull(sec9);
        // Header byte at offset 2 (after writeShort(21))
        assertEquals(7, sec9[2] & 0xff);
        // Tail byte at the very last position of the section payload
        assertEquals(7, sec9[sec9.length - 1] & 0xff);
    }

    @Test
    public void testLegacyDefaultsProduceOriginalLiterals() throws Exception {
        PlayerCharacter pc = newCharacter();
        Player p = buildPlayer(pc);
        CharInfo ci = new CharInfo(p);
        Map<Integer, byte[]> sections = extractSections(ci);

        byte[] sec2 = sections.get(2);
        assertNotNull(sec2);
        assertEquals(100, readShortLE(sec2, 2));   // health
        assertEquals(100, readShortLE(sec2, 4));   // max health
        assertEquals(100, readShortLE(sec2, 6));   // psi
        assertEquals(100, readShortLE(sec2, 8));   // max psi
        assertEquals(100, readShortLE(sec2, 10));  // stamina
        assertEquals(100, readShortLE(sec2, 12));  // max stamina
        assertEquals(100, sec2[24] & 0xff);        // synaptic

        byte[] sec8 = sections.get(8);
        assertNotNull(sec8);
        assertEquals(1001, readIntLE(sec8, 1));    // cash default
        int rankOffset = 1 + 4 + 9 + 2 + 8 + 1 + 1 + 3;
        assertEquals(0, sec8[rankOffset] & 0xff);  // rank default

        byte[] sec9 = sections.get(9);
        assertNotNull(sec9);
        for (int i = 0; i < 20; i++) {
            assertEquals("default sympathy[" + i + "]",
                10000.0f, readFloatLE(sec9, 5 + i * 4), 0.0f);
        }
        // lowsl slot default
        assertEquals(0.0f, readFloatLE(sec9, 5 + 20 * 4), 0.0f);
    }

    @Test
    public void testSqliteRoundTrip() throws Exception {
        // Build a character with non-default state, persist it, reload it
        // from the DB, then assert the CharInfo packet reflects the round-
        // tripped state byte-for-byte.
        PlayerCharacter pc = newCharacter();
        pc.setHealth(321);
        pc.setMaxHealth(456);
        pc.setCash(98765);
        pc.setRank(5);
        pc.setFactionSympathy(7, 1234.5f);

        PlayerCharacterManager.saveCharacter(pc);
        PlayerCharacterManager.load();
        PlayerCharacter reloaded = PlayerCharacterManager.getCharacter(1);
        assertNotNull("reloaded character", reloaded);

        Player p = buildPlayer(reloaded);
        CharInfo ci = new CharInfo(p);
        Map<Integer, byte[]> sections = extractSections(ci);

        byte[] sec2 = sections.get(2);
        assertEquals(321, readShortLE(sec2, 2));
        assertEquals(456, readShortLE(sec2, 4));

        byte[] sec8 = sections.get(8);
        assertEquals(98765, readIntLE(sec8, 1));
        int rankOffset = 1 + 4 + 9 + 2 + 8 + 1 + 1 + 3;
        assertEquals(5, sec8[rankOffset] & 0xff);

        byte[] sec9 = sections.get(9);
        assertEquals(1234.5f, readFloatLE(sec9, 5 + 7 * 4), 0.0f);
    }
}
