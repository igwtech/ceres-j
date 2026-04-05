package server.database.importer;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import server.database.IniReader;
import server.database.worlds.World;
import server.tools.Out;

/**
 * Parses the NC2 client's {@code worlds/worlds.ini} into a list of
 * {@link Entry} triples suitable for insertion into the {@code world_defs}
 * table.
 *
 * <p>Reuses {@link IniReader} for tokenisation and {@link World} for
 * deriving the logical path so we stay bug-for-bug compatible with the
 * existing runtime loader.
 */
public final class WorldsIniParser {

    /** Immutable parse result: a single world_defs row. */
    public static final class Entry {
        public final int id;
        public final String path;
        public final String bspName;

        public Entry(int id, String path, String bspName) {
            this.id = id;
            this.path = path;
            this.bspName = bspName;
        }
    }

    private WorldsIniParser() {
        // static utility
    }

    /**
     * Parse worlds.ini content. Malformed {@code set} entries are skipped
     * with a warning and do not abort parsing of the remainder of the file.
     */
    public static List<Entry> parse(InputStream in) {
        List<Entry> out = new ArrayList<>();
        IniReader ir = new IniReader(in);
        try {
            while (true) {
                String[] tokens = ir.getTokens();
                if (tokens.length > 2 && "set".equals(tokens[0])) {
                    Entry e = tryParseEntry(tokens);
                    if (e != null) {
                        out.add(e);
                    }
                } else if (ir.isEof()) {
                    break;
                }
            }
        } finally {
            ir.close();
        }
        return out;
    }

    private static Entry tryParseEntry(String[] tokens) {
        try {
            World w = new World(tokens);
            String path = w.getName();
            if (path == null || path.isEmpty()) {
                return null;
            }
            String rawBsp = tokens[2];
            // Derive the bsp filename from the last path component of tokens[2].
            // tokens[2] uses Windows backslashes (e.g. ".\worlds\plaza\plaza_p1.bsp").
            String bspName = rawBsp;
            int bs = bspName.lastIndexOf('\\');
            if (bs >= 0) {
                bspName = bspName.substring(bs + 1);
            }
            int fs = bspName.lastIndexOf('/');
            if (fs >= 0) {
                bspName = bspName.substring(fs + 1);
            }
            // Also strip any leading "./" in case the filename alone was quoted.
            int sep = bspName.lastIndexOf(File.separatorChar);
            if (sep >= 0) {
                bspName = bspName.substring(sep + 1);
            }
            return new Entry(w.getID(), path, bspName);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            Out.writeln(Out.Warning, "WorldsIniParser: skipping malformed entry: "
                + String.join(" ", tokens));
            return null;
        }
    }
}
