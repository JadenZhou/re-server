package db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Analytics-service's private SQLite handle. Owns ONLY the analytics tables
 * (property_views, postcode_searches). Other services use their own Db with
 * their own table set, enforcing the bounded-context rule at the schema layer.
 *
 * Tables are created idempotently on first {@link #connection()} call, so the
 * service can boot in any order relative to property-server / purchaser-server
 * / loader as long as they share the SQLITE_PATH.
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
                    s.execute("PRAGMA journal_mode = WAL");
                    s.execute("PRAGMA synchronous = NORMAL");
                    s.execute("PRAGMA busy_timeout = 5000");
                    for (String stmt : SCHEMA) s.execute(stmt);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to open SQLite at " + path, e);
            }
        }
        return conn;
    }

    private static final String[] SCHEMA = new String[] {
            "CREATE TABLE IF NOT EXISTS property_views (" +
                    "  property_id TEXT PRIMARY KEY," +
                    "  count       INTEGER NOT NULL DEFAULT 0" +
                    ")",
            "CREATE TABLE IF NOT EXISTS postcode_searches (" +
                    "  post_code   TEXT PRIMARY KEY," +
                    "  count       INTEGER NOT NULL DEFAULT 0" +
                    ")",
    };
}
