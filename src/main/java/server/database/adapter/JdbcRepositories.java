package server.database.adapter;

import java.sql.Connection;

import server.database.SqlDialect;
import server.database.port.ClientDefRepository;
import server.database.port.WorldDataRepository;
import server.database.port.WorldDefRepository;

/**
 * Composition root for the JDBC adapter set.
 *
 * <p>The importer application layer asks this factory for the ports it
 * needs, passing the {@link Connection} it was handed. The factory picks the
 * JDBC adapters; the adapters resolve the {@link SqlDialect} from the
 * connection, so the same wiring transparently targets SQLite, PostgreSQL or
 * MySQL/MariaDB. Swapping in a non-JDBC backend later means adding a sibling
 * factory, not touching the importers.
 */
public final class JdbcRepositories {

    private JdbcRepositories() {}

    /** Dialect the supplied connection will be driven with (for logging). */
    public static SqlDialect dialectOf(Connection conn) {
        return SqlDialect.of(conn);
    }

    public static WorldDefRepository worldDefs(Connection conn) {
        return new JdbcWorldDefRepository(conn);
    }

    public static ClientDefRepository clientDefs(Connection conn) {
        return new JdbcClientDefRepository(conn);
    }

    public static WorldDataRepository worldData(Connection conn) {
        return new JdbcWorldDataRepository(conn);
    }
}
