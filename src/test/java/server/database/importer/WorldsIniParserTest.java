package server.database.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class WorldsIniParserTest {

    private InputStream loadFixture() {
        InputStream in = getClass().getClassLoader()
            .getResourceAsStream("importer/worlds_sample.ini");
        assertNotNull("fixture importer/worlds_sample.ini missing from classpath", in);
        return in;
    }

    @Test
    public void parsesFiveEntriesFromFixture() {
        List<WorldsIniParser.Entry> entries = WorldsIniParser.parse(loadFixture());
        assertEquals(5, entries.size());

        Map<Integer, WorldsIniParser.Entry> byId = new HashMap<>();
        for (WorldsIniParser.Entry e : entries) {
            byId.put(e.id, e);
        }

        assertEquals("plaza/plaza_p1", byId.get(1).path);
        assertEquals("plaza_p1.bsp", byId.get(1).bspName);

        assertEquals("plaza/plaza_p2", byId.get(2).path);
        assertEquals("plaza_p2.bsp", byId.get(2).bspName);

        assertEquals("pepper/pepper_p3", byId.get(7).path);
        assertEquals("pepper_p3.bsp", byId.get(7).bspName);

        assertEquals("subway", byId.get(100).path);
        assertEquals("subway.bsp", byId.get(100).bspName);

        assertEquals("broken_entry", byId.get(999).path);
        assertEquals("broken_entry.bsp", byId.get(999).bspName);
    }

    @Test
    public void skipsBlockAndLineComments() {
        // The fixture contains both "/* ... */" and "// ..." comments.
        // If the parser consumed them as entries, parsesFiveEntriesFromFixture
        // would blow up, but we also assert explicitly here that no entry was
        // created with id 0 or a non-numeric stray.
        List<WorldsIniParser.Entry> entries = WorldsIniParser.parse(loadFixture());
        for (WorldsIniParser.Entry e : entries) {
            assertTrue("comment leaked into entries: id=" + e.id, e.id > 0);
            assertTrue("empty path for id=" + e.id, e.path != null && !e.path.isEmpty());
            assertTrue("empty bspName for id=" + e.id,
                e.bspName != null && e.bspName.endsWith(".bsp"));
        }
    }

    @Test
    public void handlesMalformedEntryWithoutCrashing() {
        // "set <non-numeric> ..." and "set 5" (too few tokens) should be
        // silently skipped instead of throwing.
        String bad =
            "set abc \".\\worlds\\broken.bsp\";\r\n" +
            "set 5\r\n" +
            "set 42 \".\\worlds\\ok.bsp\";\r\n";

        List<WorldsIniParser.Entry> entries = WorldsIniParser.parse(
            new ByteArrayInputStream(bad.getBytes(StandardCharsets.US_ASCII)));

        // Only the well-formed one should survive.
        assertEquals(1, entries.size());
        assertEquals(42, entries.get(0).id);
        assertEquals("ok", entries.get(0).path);
        assertEquals("ok.bsp", entries.get(0).bspName);
    }
}
