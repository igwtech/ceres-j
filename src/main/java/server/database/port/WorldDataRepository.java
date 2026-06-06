package server.database.port;

import server.database.importer.WorldDatParser;

/**
 * Port for persisting structured records parsed from NC2 client
 * {@code .dat} world files (objects, doors, NPCs, waypoints, regions,
 * markers, raw blobs, …) consumed by
 * {@link server.database.importer.WorldDatImporter}.
 */
public interface WorldDataRepository {

    /**
     * Ensure all {@code world_*} tables and indexes exist. Idempotent.
     *
     * @throws RepositoryException on any backend failure
     */
    void ensureSchema();

    /**
     * @return {@code true} if any {@code world_*} table already holds rows
     *         for {@code worldPath} (used to skip re-import).
     * @throws RepositoryException on any backend failure
     */
    boolean contains(String worldPath);

    /**
     * Persist one parsed world in a single transaction (all element kinds,
     * with NPC waypoints linked to their generated NPC row ids).
     *
     * @throws RepositoryException on any backend failure
     */
    void insertWorld(String worldPath, WorldDatParser.ParsedWorld world);
}
