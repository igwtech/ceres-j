# Client Data Import

Ceres-J ships with a one-shot importer that mirrors selected NC2 client
resource files into the server's SQLite database (`database/ceres.db`).
Once imported, runtime subsystems read from SQLite instead of re-parsing
the client PAKs on every boot, which makes the server bootable on hosts
that do not have a client installed at all.

## What the importer does

On every server startup, `SqliteDatabase.init()` calls
`ClientDataImporter.runIfNeeded(Connection)` **after** `createTables()`
and **before** `migrateFromCsv()`. The importer:

1. Probes each target table with `SELECT COUNT(*)`. If rows already
   exist, it logs `World defs already populated, skipping import` and
   returns — the import is idempotent.
2. Opens the source file via `server.tools.VirtualFileSystem`, which
   resolves paths against the configured `NC2ClientPath` (decompressed
   file, `pak_` prefixed file, or `.pak` archive).
3. Parses the file with `WorldsIniParser` (which in turn reuses the
   existing `IniReader` tokenizer and `World` path-normalisation
   helper).
4. Inserts the rows in a single batched transaction
   (`INSERT OR REPLACE`), committing or rolling back as a unit.

If `VirtualFileSystem` cannot find the source file — because no client
is mounted on a fresh install — the importer logs a warning and returns
cleanly without throwing. The call site in `SqliteDatabase.init()` also
wraps the importer in a `try/catch (RuntimeException)` so no importer
failure can ever abort server startup.

## Source files read

| Client path (VFS)     | Parser             | Target table | Status      |
|-----------------------|--------------------|--------------|-------------|
| `worlds\worlds.ini`   | `WorldsIniParser`  | `world_defs` | implemented |
| `defs\pak_items.def`  | (future)           | `item_defs`  | reserved    |

## Target tables (schema version 2)

```sql
CREATE TABLE IF NOT EXISTS world_defs (
  id INTEGER PRIMARY KEY,
  path TEXT NOT NULL,       -- e.g. "plaza/plaza_p1"
  bsp_name TEXT NOT NULL    -- e.g. "plaza_p1.bsp"
);

CREATE TABLE IF NOT EXISTS item_defs (
  id INTEGER PRIMARY KEY,
  name TEXT,
  type INTEGER,
  tech_level INTEGER,
  stats_json TEXT
);
```

`path` matches the existing `World.getName()` contract — backslashes
replaced with forward slashes, the `.\worlds\` prefix and `.bsp`
suffix stripped. `bsp_name` is the raw file name (with extension) so
future consumers that need to resolve the actual BSP asset can find it
without re-parsing.

## Schema version coordination

The importer owns **schema version 2** (`SqliteDatabase.CURRENT_SCHEMA_VERSION = 2`).
A parallel track on branch `feature/charinfo-fidelity` owns version 1.
When the two tracks merge, bump the constant and run the migrations in
order (`v1` first, then `v2`). The version is persisted via
`PRAGMA user_version` so future conditional migrations can inspect
what has already run without altering existing DDL.

## Runtime consumer: WorldManager

`WorldManager.init()` prefers SQLite. It:

1. Reads `id, path` from `world_defs`. If any rows are found, it
   populates the in-memory `TreeMap<Integer, World>` directly and logs
   `Loaded N World IDs from SQLite`.
2. If `world_defs` is empty, it falls back to the legacy
   `VirtualFileSystem.getFileInputStream("worlds\\worlds.ini")` path
   and parses with `IniReader` + `World` as before.
3. If both sources are unavailable, it logs an error and leaves the
   map empty. This is a softening of the previous behaviour, which
   threw `StartupException`. Existing callers of `getWorldname(int)`
   already tolerate `null` returns from `TreeMap.get`, so the server
   can still boot and reach the login screen without a mounted client.

## Manual re-run

The importer exposes a `main(String[])` entry point for operators who
need to re-import after upgrading the client's PAK contents without a
full server restart. It is **not** wired into `pom.xml`; invoke it
directly from a packaged build:

```bash
java -cp bin/ceres-j.jar:lib/* \
  server.database.importer.ClientDataImporter \
  /path/to/NC2Client
```

The manual path deletes any existing `world_defs` rows before
re-importing, so it always fully refreshes the table. The automatic
startup path, by contrast, is purely additive and idempotent.
