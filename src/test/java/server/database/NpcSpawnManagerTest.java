package server.database;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link NpcSpawnManager} helpers — specifically the
 * angle-text parser used by the {@code world_npcs} fallback path.
 *
 * <p>The {@code world_npcs.angle} column is TEXT. Across the 3,571
 * retail-import rows, observed values include integers, negative
 * integers (592), empty strings, and NULLs. The value becomes the
 * 0x28 orientation string, which retail sends exactly as written in
 * the .dat — so the parser keeps the integer as-is.
 */
public class NpcSpawnManagerTest {

    @Test
    public void nullAngle_returnsZero() {
        assertEquals(0, NpcSpawnManager.parseAngle(null));
    }

    @Test
    public void emptyAngle_returnsZero() {
        assertEquals(0, NpcSpawnManager.parseAngle(""));
        assertEquals(0, NpcSpawnManager.parseAngle("   "));
    }

    @Test
    public void positiveInteger_returnsAsIs() {
        assertEquals(0, NpcSpawnManager.parseAngle("0"));
        assertEquals(90, NpcSpawnManager.parseAngle("90"));
        assertEquals(180, NpcSpawnManager.parseAngle("180"));
        assertEquals(270, NpcSpawnManager.parseAngle("270"));
        assertEquals(359, NpcSpawnManager.parseAngle("359"));
    }

    @Test
    public void negativeInteger_keptAsWritten() {
        // Retail sends the .dat text verbatim: pepper_p1 npc 33/38
        // "-90" -> 0x28 orientation "-90"; plaza_p1 WSK npc 11 "-178".
        assertEquals(-90, NpcSpawnManager.parseAngle("-90"));
        assertEquals(-178, NpcSpawnManager.parseAngle("-178"));
        assertEquals(-270, NpcSpawnManager.parseAngle("-270"));
    }

    @Test
    public void largeAngle_keptAsWritten() {
        assertEquals(360, NpcSpawnManager.parseAngle("360"));
        assertEquals(370, NpcSpawnManager.parseAngle("370"));
    }

    @Test
    public void curatedWireRowsDecodeToWorldAndReEncodeIdentically() {
        // npc_spawns stores wire values; loading decodes them to world
        // units (Z +1) so NPC.getWire*() reproduces the stored bytes.
        int wx = 32186, wy = 31551, wz = 32827;
        server.gameserver.NPC npc = new server.gameserver.NPC(257, 1, 191,
                "CityMercs Guard",
                NpcSpawnManager.worldFromWire(wx),
                NpcSpawnManager.worldFromWire(wy),
                NpcSpawnManager.worldFromWire(wz) + 1, 64, 500, 50);
        assertEquals(wx, npc.getWireX());
        assertEquals(wy, npc.getWireY());
        assertEquals(wz, npc.getWireZ());
        assertEquals(-582, npc.getXpos());
    }

    @Test
    public void nonNumericAngle_returnsZero() {
        assertEquals(0, NpcSpawnManager.parseAngle("abc"));
        assertEquals(0, NpcSpawnManager.parseAngle("90.5")); // not an int
        assertEquals(0, NpcSpawnManager.parseAngle("--90"));
    }

    @Test
    public void integerWithWhitespace_isParsed() {
        assertEquals(90, NpcSpawnManager.parseAngle(" 90 "));
        assertEquals(180, NpcSpawnManager.parseAngle("180\t"));
    }
}
