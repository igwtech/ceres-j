package server.database.adapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import server.database.SqlDialect;
import server.database.port.ClientDefRepository;
import server.database.port.RepositoryException;

/**
 * JDBC adapter for {@link ClientDefRepository}. The {@code fields} column is
 * a JSON document whose column type and bind placeholder vary per backend
 * (PostgreSQL {@code JSONB}/{@code ?::jsonb}, MySQL {@code JSON}/{@code ?},
 * SQLite {@code TEXT}/{@code ?}); both are sourced from {@link SqlDialect}.
 */
public final class JdbcClientDefRepository implements ClientDefRepository {

    private static final String TABLE = "client_defs";
    private static final String[] COLS = {"def_name", "entry_id", "fields"};
    private static final String[] KEY  = {"def_name", "entry_id"};

    private final Connection conn;
    private final SqlDialect dialect;

    public JdbcClientDefRepository(Connection conn) {
        this.conn = conn;
        this.dialect = SqlDialect.of(conn);
    }

    @Override
    public void ensureSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "  def_name TEXT NOT NULL,"
                + "  entry_id INTEGER NOT NULL,"
                + "  fields " + dialect.jsonType() + " NOT NULL,"
                + "  PRIMARY KEY (def_name, entry_id))";
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new RepositoryException("create table " + TABLE + " failed", e);
        }
    }

    @Override
    public int countForDef(String defName) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE def_name = ?")) {
            ps.setString(1, defName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RepositoryException("probe " + TABLE + " for " + defName + " failed", e);
        }
    }

    @Override
    public int upsertAll(String defName, List<Row> rows) {
        String[] placeholders = {"?", "?", dialect.jsonPlaceholder()};
        String sql = dialect.upsert(TABLE, COLS, placeholders, KEY);
        int n = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Row row : rows) {
                ps.setString(1, defName);
                ps.setInt(2, row.entryId());
                ps.setString(3, row.fieldsJson());
                ps.addBatch();
                n++;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RepositoryException("insert into " + TABLE + " for " + defName + " failed", e);
        }
        return n;
    }
}
