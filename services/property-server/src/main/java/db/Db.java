package db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Property-service's private SQLite handle. Owns ONLY the property-context
 * tables (postcodes, properties, listings, listing_prices). The schema does
 * NOT carry search_count columns — those live in the analytics service's
 * tables, per the bounded-context rule.
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
                    for (String stmt : SCHEMA) s.execute(stmt);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to open SQLite at " + path, e);
            }
        }
        return conn;
    }

    /** INSERT OR IGNORE a postcode so FK references will resolve. */
    public static void ensurePostcode(Connection c, String postcode) {
        if (postcode == null || postcode.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO postcodes (post_code) VALUES (?)")) {
            ps.setString(1, postcode);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("ensurePostcode failed for " + postcode, e);
        }
    }

    private static final String[] SCHEMA = new String[] {
            "CREATE TABLE IF NOT EXISTS postcodes (" +
                    "  post_code TEXT PRIMARY KEY" +
                    ")",
            "CREATE TABLE IF NOT EXISTS properties (" +
                    "  id              TEXT PRIMARY KEY," +
                    "  property_id     INTEGER," +
                    "  post_code       TEXT NOT NULL REFERENCES postcodes(post_code)," +
                    "  purchase_price  INTEGER," +
                    "  address         TEXT," +
                    "  council_name    TEXT," +
                    "  property_type   TEXT," +
                    "  contract_date   TEXT," +
                    "  for_sale        INTEGER NOT NULL DEFAULT 0" +
                    ")",
            "CREATE INDEX IF NOT EXISTS idx_properties_post_code      ON properties(post_code)",
            "CREATE INDEX IF NOT EXISTS idx_properties_purchase_price ON properties(purchase_price)",
            "CREATE INDEX IF NOT EXISTS idx_properties_property_id    ON properties(property_id)",
            "CREATE TABLE IF NOT EXISTS listings (" +
                    "  id            TEXT PRIMARY KEY," +
                    "  property_id   TEXT NOT NULL REFERENCES properties(id) ON DELETE CASCADE," +
                    "  is_discounted INTEGER NOT NULL DEFAULT 0," +
                    "  date_added    TEXT NOT NULL" +
                    ")",
            "CREATE INDEX IF NOT EXISTS idx_listings_property ON listings(property_id)",
            "CREATE TABLE IF NOT EXISTS listing_prices (" +
                    "  id          INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  listing_id  TEXT NOT NULL REFERENCES listings(id) ON DELETE CASCADE," +
                    "  price       REAL NOT NULL," +
                    "  updated_at  TEXT NOT NULL" +
                    ")",
            "CREATE INDEX IF NOT EXISTS idx_prices_listing_date ON listing_prices(listing_id, updated_at DESC)",
    };
}
