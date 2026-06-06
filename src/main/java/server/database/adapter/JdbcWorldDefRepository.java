package server.database.adapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import server.database.SqlDialect;
import server.database.importer.WorldsIniParser;
import server.database.port.RepositoryException;
import server.database.port.WorldDefRepository;

/**
 * JDBC adapter for {@link WorldDefRepository}. Backend-neutral: the
 * dialect-specific upsert is delegated to {@link SqlDialect}, detected from
 * the supplied {@link Connection}, so the same adapter drives SQLite,
 * PostgreSQL and MySQL/MariaDB.
 */
public final class JdbcWorldDefRepository implements WorldDefRepository {

    private static final String TABLE = "world_defs";
    private static final String[] COLS = {"id", "path", "bsp_name"};
    private static final String[] KEY  = {"id"};

    private final Connection conn;
    private final SqlDialect dialect;

    public JdbcWorldDefRepository(Connection conn) {
        this.conn = conn;
        this.dialect = SqlDialect.of(conn);
    }

    @Override
    public boolean isPopulated() {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + TABLE)) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            throw new RepositoryException("probe " + TABLE + " failed", e);
        }
    }

    @Override
    public void upsertAll(List<WorldsIniParser.Entry> entries) {
        String sql = dialect.upsert(TABLE, COLS, KEY);
        boolean prevAutoCommit = true;
        try {
            prevAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (WorldsIniParser.Entry e : entries) {
                    ps.setInt(1, e.id);
                    ps.setString(2, e.path);
                    ps.setString(3, e.bspName);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new RepositoryException(TABLE + " insert failed", e);
            } finally {
                conn.setAutoCommit(prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new RepositoryException(TABLE + " transaction error", e);
        }
    }
}
