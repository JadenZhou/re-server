package db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Shared SQLite connection and one-time schema bootstrap for REServer.
 *
 * Replaces the old MongoClient. Lives as a single static instance — SQLite
 * handles a long-lived connection in one JVM just fine, especially in WAL
 * mode. All DAOs ask for the connection via {@link #connection()}.
 */
public final class Db {

    private static volatile Connection conn;

    private Db() {}

    /**
     * Returns the shared connection, opening + initializing the DB the first
     * time it is called.
     */
    public static synchronized Connection connection() {
        if (conn == null) {
            conn = open();
            init(conn);
        }
        return conn;
    }

    /** Open + configure a SQLite connection. */
    private static Connection open() {
        String path = System.getenv("SQLITE_PATH");
        if (path == null || path.isEmpty()) {
            path = "re-server.db";
        }
        String url = "jdbc:sqlite:" + path;
        try {
            Connection c = DriverManager.getConnection(url);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON");
                s.execute("PRAGMA journal_mode = WAL");
                s.execute("PRAGMA synchronous = NORMAL");
                s.execute("PRAGMA busy_timeout = 5000");
            }
            return c;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open SQLite at " + url, e);
        }
    }

    /** Create the schema if it doesn't exist. Idempotent. */
    private static void init(Connection c) {
        try (Statement s = c.createStatement()) {
            for (String stmt : SCHEMA) {
                s.execute(stmt);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize SQLite schema", e);
        }
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

    public static final String[] SCHEMA = new String[] {
            "CREATE TABLE IF NOT EXISTS postcodes (" +
                    "  post_code     TEXT PRIMARY KEY," +
                    "  search_count  INTEGER NOT NULL DEFAULT 0" +
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
                    "  for_sale        INTEGER NOT NULL DEFAULT 0," +
                    "  search_count    INTEGER NOT NULL DEFAULT 0" +
                    ")",
            "CREATE INDEX IF NOT EXISTS idx_properties_post_code      ON properties(post_code)",
            "CREATE INDEX IF NOT EXISTS idx_properties_purchase_price ON properties(purchase_price)",
            "CREATE INDEX IF NOT EXISTS idx_properties_property_id    ON properties(property_id)",
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
            "CREATE TABLE IF NOT EXISTS purchases (" +
                    "  id           INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  account_id   TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE," +
                    "  property_id  TEXT NOT NULL REFERENCES properties(id) ON DELETE CASCADE," +
                    "  purchased_at TEXT NOT NULL" +
                    ")",
    };
}
