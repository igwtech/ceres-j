package server.database.importer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import server.database.adapter.JdbcRepositories;
import server.database.port.RepositoryException;
import server.database.port.WorldDataRepository;
import server.tools.Config;
import server.tools.Out;

/**
 * Walks the NC2 client's {@code worlds/} (and optionally
 * {@code terrain/}) tree, parses every {@code .dat} and {@code .bsp}
 * file via {@link WorldDatParser}, and writes the structured records
 * into the {@code world_objects}, {@code world_doors},
 * {@code world_npcs}, {@code world_npc_waypoints} and
 * {@code world_raw_elements} tables.
 *
 * <p>Idempotent on a per-{@code world_path} basis: each file's
 * {@code world_path} key (e.g. {@code worlds/citysewer/peppersewer_1a})
 * is checked for an existing row count before processing. Re-running
 * is therefore cheap.
 *
 * <p>Like {@link ClientDataImporter} and {@link DefImporter}, the
 * importer is safe to invoke on every server boot: missing client
 * roots, unreadable files, and parse errors are logged but never
 * thrown — the server must boot on a fresh install with no client
 * mounted.
 *
 * <p>Limitations (deliberately scoped):
 * <ul>
 *   <li>Operates on already-extracted {@code .dat}/{@code .bsp}
 *       files. Reading directly from {@code worlds.pak} is not
 *       implemented; the launcher extracts archives on first run.</li>
 *   <li>Element types other than {@code 1000003} (object),
 *       {@code 1000005} (door), and {@code 1000006} (NPC) are stored
 *       as raw blobs in {@code world_raw_elements} so future RE
 *       passes can decode them without re-parsing.</li>
 * </ul>
 */
public final class WorldDatImporter {

    /** Default directories under {@code NC2ClientPath} we walk. */
    public static final String[] DEFAULT_ROOTS = {"worlds", "terrain"};

    private WorldDatImporter() {}

    /**
     * Entry point: ensure tables exist, walk the configured client
     * root, import any new files. Cheap to call on every boot.
     */
    public static void runIfNeeded(Connection conn) {
        if (conn == null) {
            Out.writeln(Out.Warning, "WorldDatImporter: no DB connection, skipping");
            return;
        }
        String clientPath;
        try {
            clientPath = Config.getProperty("NC2ClientPath");
        } catch (RuntimeException e) {
            Out.writeln(Out.Warning,
                "WorldDatImporter: Config.NC2ClientPath unavailable; skipping");
            return;
        }
        if (clientPath == null || clientPath.isEmpty()) {
            Out.writeln(Out.Warning,
                "WorldDatImporter: NC2ClientPath empty; skipping");
            return;
        }
        File root = new File(clientPath);
        if (!root.isDirectory()) {
            Out.writeln(Out.Warning,
                "WorldDatImporter: " + clientPath
                + " is not a directory; skipping");
            return;
        }
        runForRoot(conn, root, DEFAULT_ROOTS);
    }

    /** Walk a specific client root directory. Public for tests / CLI. */
    public static void runForRoot(Connection conn, File clientRoot,
                                   String[] subdirs) {
        WorldDataRepository repo = JdbcRepositories.worldData(conn);
        try {
            repo.ensureSchema();
        } catch (RepositoryException e) {
            Out.writeln(Out.Error,
                "WorldDatImporter: create table failed: " + e.getMessage());
            return;
        }

        int filesScanned = 0;
        int filesImported = 0;
        int totalObjects = 0, totalDoors = 0, totalNpcs = 0;

        for (String sub : subdirs) {
            File dir = new File(clientRoot, sub);
            if (!dir.isDirectory()) continue;
            List<File> files = listDatFiles(dir);
            for (File f : files) {
                filesScanned++;
                String worldPath = relativePath(clientRoot, f);
                if (repo.contains(worldPath)) continue;

                byte[] raw;
                try {
                    raw = Files.readAllBytes(f.toPath());
                } catch (IOException e) {
                    Out.writeln(Out.Warning,
                        "WorldDatImporter: read failed " + f
                        + ": " + e.getMessage());
                    continue;
                }
                WorldDatParser.ParsedWorld pw;
                try {
                    pw = WorldDatParser.parse(raw);
                } catch (WorldDatParser.ParseException e) {
                    Out.writeln(Out.Warning,
                        "WorldDatImporter: parse failed " + f
                        + ": " + e.getMessage());
                    continue;
                }
                try {
                    repo.insertWorld(worldPath, pw);
                    filesImported++;
                    totalObjects += pw.objects.size();
                    totalDoors   += pw.doors.size();
                    totalNpcs    += pw.npcs.size();
                    if (pw.malformedElements > 0) {
                        Out.writeln(Out.Warning,
                            "WorldDatImporter: " + worldPath
                            + " had " + pw.malformedElements
                            + " malformed elements (captured as "
                            + "negative-tagged raw blobs)");
                    }
                } catch (RepositoryException e) {
                    Out.writeln(Out.Error,
                        "WorldDatImporter: insert failed " + worldPath
                        + ": " + e.getMessage());
                }
            }
        }
        Out.writeln(Out.Info,
            "WorldDatImporter: scanned " + filesScanned + " files, imported "
            + filesImported + " (" + totalObjects + " objects, "
            + totalDoors + " doors, " + totalNpcs + " NPCs)");
    }

    /** Lex-sorted gameplay {@code .dat} files under {@code dir},
     *  recursive. The following file classes coexist with gameplay
     *  data but use unrelated formats and are skipped:
     *  <ul>
     *    <li>{@code .bsp} — GBSP 3D-geometry / engine visibility</li>
     *    <li>{@code *height.dat} — VF00 terrain heightmaps</li>
     *  </ul> */
    static List<File> listDatFiles(File dir) {
        List<File> out = new ArrayList<>();
        try {
            Files.walk(dir.toPath())
                .filter(Files::isRegularFile)
                .forEach((Path p) -> {
                    String n = p.getFileName().toString().toLowerCase();
                    if (!n.endsWith(".dat")) return;
                    if (n.endsWith("height.dat")) return;
                    out.add(p.toFile());
                });
        } catch (IOException e) {
            Out.writeln(Out.Warning,
                "WorldDatImporter: walk failed " + dir + ": " + e.getMessage());
        }
        out.sort((a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));
        return out;
    }

    /** Build a forward-slash relative path like
     *  {@code worlds/citysewer/peppersewer_1a.dat}. */
    static String relativePath(File root, File child) {
        String r = root.getAbsolutePath();
        String c = child.getAbsolutePath();
        if (c.startsWith(r)) {
            String rel = c.substring(r.length());
            if (rel.startsWith(File.separator)) rel = rel.substring(1);
            return rel.replace(File.separatorChar, '/');
        }
        return c.replace(File.separatorChar, '/');
    }

}
