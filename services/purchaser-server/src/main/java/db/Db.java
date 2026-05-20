package db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Purchaser-service's private SQLite handle. Owns ONLY: accounts,
 * postcode_interest, purchases. Note that postcode_interest references the
 * postcodes table (owned by property-service) — both services point at the
 * same SQLite file, but each service's Db.java only CREATES its own tables.
 * The postcodes table is created by either the property-service or the
 * loader before this service is asked to write interests.
 */
public final class Db {

    private static volatile Connection conn;

    private Db() {}

    public static synchronized Connection connection() {
        if (conn == null) {
            String path = System.getenv().getOrDefault("SQLITE_PATH", "re-server.db");
            try {
                conn = DriverManager.getConnection("jdbc:sqlite:" + path);
                try (Statement s = conn.createStatement()) {
                    s.execute("PRAGMA foreign_keys = ON");
                    s.execute("PRAGMA journal_mode = WAL");
                    s.execute("PRAGMA synchronous = NORMAL");
                    s.execute("PRAGMA busy_timeout = 5000");
                    // Bootstrap postcodes if property-service / loader hasn't run yet.
                    // Without this an interest insert would fail the FK check on first boot.
                    s.execute("CREATE TABLE IF NOT EXISTS postcodes (post_code TEXT PRIMARY KEY)");
                    for (String stmt : SCHEMA) s.execute(stmt);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to open SQLite at " + path, e);
            }
        }
        return conn;
    }

    public static void ensurePostcode(Connection c, String postcode) {
        if (postcode == null || postcode.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO postcodes (post_code) VALUES (?)")) {
            ps.setString(1, postcode);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("ensurePostcode failed", e);
        }
    }

    private static final String[] SCHEMA = new String[] {
            "CREATE TABLE IF NOT EXISTS accounts (" +
                    "  id            TEXT PRIMARY KEY," +
                    "  name          TEXT NOT NULL," +
                    "  email         TEXT NOT NULL UNIQUE," +
                    "  account_type  TEXT NOT NULL" +
                    ")",
            "CREATE TABLE IF NOT EXISTS postcode_interest (" +
                    "  account_id  TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE," +
                    "  post_code   TEXT NOT NULL REFERENCES postcodes(post_code)," +
                    "  PRIMARY KEY (account_id, post_code)" +
                    ")",
            "CREATE INDEX IF NOT EXISTS idx_pi_post_code ON postcode_interest(post_code)",
            "CREATE TABLE IF NOT EXISTS purchases (" +
                    "  id           INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  account_id   TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE," +
                    "  property_id  TEXT NOT NULL," +
                    "  purchased_at TEXT NOT NULL" +
                    ")",
    };
}
