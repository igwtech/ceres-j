package server.database;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link NpcSpawnManager} helpers — specifically the
 * angle-text parser used by the {@code world_npcs} fallback path.
 *
 * <p>The {@code world_npcs.angle} column is TEXT. Across the 3,571
 * retail-import rows, observed values include integers, negative
 * integers, empty strings, and NULLs. The parser must coerce them
 * all to a [0, 360) integer so the NPC's heading is well-defined.
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
    public void negativeInteger_normalisesToPositive() {
        // Retail world_npcs has 286× "-90", 285× "-180", etc.
        assertEquals(270, NpcSpawnManager.parseAngle("-90"));
        assertEquals(180, NpcSpawnManager.parseAngle("-180"));
        assertEquals(181, NpcSpawnManager.parseAngle("-179"));
        // -270 normalises to 90 (since -270 + 360 = 90)
        assertEquals(90, NpcSpawnManager.parseAngle("-270"));
    }

    @Test
    public void overflowAngle_normalises() {
        assertEquals(0, NpcSpawnManager.parseAngle("360"));
        assertEquals(10, NpcSpawnManager.parseAngle("370"));
        assertEquals(0, NpcSpawnManager.parseAngle("720"));
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
