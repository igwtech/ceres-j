package server.database.port;

import java.util.List;

import server.database.importer.WorldsIniParser;

/**
 * Port for persisting world definitions parsed from the NC2 client's
 * {@code worlds/worlds.ini}.
 *
 * <p>This is the hexagonal <i>port</i>: it states what
 * {@link server.database.importer.ClientDataImporter} needs from
 * persistence in domain terms, with no JDBC, SQL dialect, or
 * {@link java.sql.Connection} leaking through. A backend-specific
 * <i>adapter</i> (e.g. {@code JdbcWorldDefRepository}) implements it.
 */
public interface WorldDefRepository {

    /** @return {@code true} if {@code world_defs} already holds at least one row. */
    boolean isPopulated();

    /**
     * Insert-or-replace every parsed world definition in a single
     * transaction. Keyed on {@code id}; existing rows are overwritten.
     *
     * @throws RepositoryException on any backend failure
     */
    void upsertAll(List<WorldsIniParser.Entry> entries);
}
