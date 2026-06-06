package server.database.port;

import java.util.List;

/**
 * Port for persisting generic {@code defs\*.def} entries (one JSON document
 * per {@code (def_name, entry_id)}) consumed by
 * {@link server.database.importer.DefImporter}.
 */
public interface ClientDefRepository {

    /** A single def entry to persist: its numeric id and JSON-encoded fields. */
    record Row(int entryId, String fieldsJson) {}

    /**
     * Ensure the backing {@code client_defs} table exists. Idempotent.
     *
     * @throws RepositoryException on any backend failure
     */
    void ensureSchema();

    /**
     * @return number of rows already stored for {@code defName}
     *         (0 if none / table empty).
     * @throws RepositoryException on any backend failure
     */
    int countForDef(String defName);

    /**
     * Insert-or-replace all {@code rows} for {@code defName}, keyed on
     * {@code (def_name, entry_id)}.
     *
     * @return number of rows written
     * @throws RepositoryException on any backend failure
     */
    int upsertAll(String defName, List<Row> rows);
}
