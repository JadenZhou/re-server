package org.example;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Loads the NSW property CSV into SQLite. Drops and recreates the schema,
 * then bulk-inserts rows in a single transaction. Uses random 24-char hex
 * IDs so the {@code properties.id} primary keys match the wire format the
 * HTTP API has always exposed.
 */
public class Main {

    private static final CSVFormat CSV_FORMAT = CSVFormat.Builder.create(CSVFormat.RFC4180)
            .setHeader()
            .setSkipHeaderRecord(true)
            .setAllowDuplicateHeaderNames(false)
            .build();

    private static final int BATCH_SIZE = 1000;
    private static final SecureRandom RND = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final String DEFAULT_CSV = "../data/nsw_property_data.csv";
    private static final String DEFAULT_DB = "re-server.db";

    public static void main(String[] args) throws IOException {
        String sqlitePath = System.getenv().getOrDefault("SQLITE_PATH", DEFAULT_DB);
        String csvPath = System.getenv().getOrDefault("RE_CSV_PATH", DEFAULT_CSV);
        Path csvFilePath = Paths.get(csvPath);
        System.out.println("Loading CSV: " + csvFilePath + " → SQLite: " + sqlitePath);

        long start = System.currentTimeMillis();
        long inserted = 0;
        long parseErrors = 0;

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
             CSVParser parser = CSVParser.parse(csvFilePath, StandardCharsets.UTF_8, CSV_FORMAT)) {

            // SQLite tuning for bulk loads.
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode = WAL");
                s.execute("PRAGMA synchronous = NORMAL");
                s.execute("PRAGMA foreign_keys = ON");
            }

            resetSchema(c);
            c.setAutoCommit(false);

            System.out.println("Headers: " + parser.getHeaderNames());

            try (PreparedStatement insPostcode = c.prepareStatement(
                    "INSERT OR IGNORE INTO postcodes (post_code) VALUES (?)");
                 PreparedStatement insProperty = c.prepareStatement(
                         "INSERT INTO properties (id, property_id, post_code, purchase_price, " +
                                 "address, council_name, property_type, contract_date, for_sale, search_count) " +
                                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0)")) {

                int batchCount = 0;
                for (CSVRecord record : parser) {
                    Row row = toRow(record);
                    if (row == null) {
                        parseErrors++;
                        continue;
                    }

                    insPostcode.setString(1, row.postcode);
                    insPostcode.addBatch();

                    insProperty.setString(1, generateId());
                    if (row.propertyId == null) insProperty.setNull(2, java.sql.Types.INTEGER);
                    else insProperty.setLong(2, row.propertyId);
                    insProperty.setString(3, row.postcode);
                    if (row.purchasePrice == null) insProperty.setNull(4, java.sql.Types.INTEGER);
                    else insProperty.setLong(4, row.purchasePrice);
                    insProperty.setString(5, row.address);
                    insProperty.setString(6, row.councilName);
                    insProperty.setString(7, row.propertyType);
                    insProperty.setString(8, row.contractDate);
                    insProperty.addBatch();

                    batchCount++;
                    if (batchCount >= BATCH_SIZE) {
                        insPostcode.executeBatch();
                        insProperty.executeBatch();
                        c.commit();
                        inserted += batchCount;
                        batchCount = 0;
                        if (inserted % 50_000 == 0) {
                            System.out.printf("  ... %d rows inserted (%.1fs)%n",
                                    inserted, (System.currentTimeMillis() - start) / 1000.0);
                        }
                    }
                }
                if (batchCount > 0) {
                    insPostcode.executeBatch();
                    insProperty.executeBatch();
                    c.commit();
                    inserted += batchCount;
                }
            }

            c.setAutoCommit(true);
            double secs = (System.currentTimeMillis() - start) / 1000.0;
            System.out.printf("Done. Inserted %d rows in %.1fs (%.0f rows/sec). Parse errors: %d%n",
                    inserted, secs, inserted / Math.max(secs, 0.001), parseErrors);
        } catch (SQLException e) {
            throw new RuntimeException("Loader failed", e);
        }
    }

    private static void resetSchema(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            // Drop dependents first.
            for (String t : new String[] {
                    "purchases", "listing_prices", "listings",
                    "postcode_interest", "accounts", "properties", "postcodes"
            }) {
                s.execute("DROP TABLE IF EXISTS " + t);
            }
            for (String stmt : SCHEMA) {
                s.execute(stmt);
            }
        }
    }

    private static Row toRow(CSVRecord record) {
        try {
            Row r = new Row();
            r.propertyId = parseLongOrNull(record.get("property_id"));
            if (r.propertyId == null) return null;
            r.postcode = nullIfEmpty(record.get("post_code"));
            if (r.postcode == null) return null;
            r.purchasePrice = parseLongOrNull(record.get("purchase_price"));
            r.address = nullIfEmpty(record.get("address"));
            r.councilName = nullIfEmpty(record.get("council_name"));
            r.propertyType = nullIfEmpty(record.get("property_type"));
            r.contractDate = nullIfEmpty(record.get("contract_date"));
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private static String nullIfEmpty(String v) {
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String generateId() {
        byte[] bytes = new byte[12];
        int seconds = (int) (System.currentTimeMillis() / 1000L);
        bytes[0] = (byte) (seconds >>> 24);
        bytes[1] = (byte) (seconds >>> 16);
        bytes[2] = (byte) (seconds >>> 8);
        bytes[3] = (byte) seconds;
        byte[] tail = new byte[8];
        RND.nextBytes(tail);
        System.arraycopy(tail, 0, bytes, 4, 8);
        char[] out = new char[24];
        for (int i = 0; i < 12; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2]     = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    private static final class Row {
        Long propertyId;
        String postcode;
        Long purchasePrice;
        String address;
        String councilName;
        String propertyType;
        String contractDate;
    }

    // Schema duplicated here so the loader is self-contained.
    private static final String[] SCHEMA = new String[] {
            "CREATE TABLE postcodes (" +
                    "  post_code     TEXT PRIMARY KEY," +
                    "  search_count  INTEGER NOT NULL DEFAULT 0" +
                    ")",
            "CREATE TABLE properties (" +
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
            "CREATE INDEX idx_properties_post_code      ON properties(post_code)",
            "CREATE INDEX idx_properties_purchase_price ON properties(purchase_price)",
            "CREATE INDEX idx_properties_property_id    ON properties(property_id)",
            "CREATE TABLE accounts (" +
                    "  id            TEXT PRIMARY KEY," +
                    "  name          TEXT NOT NULL," +
                    "  email         TEXT NOT NULL UNIQUE," +
                    "  account_type  TEXT NOT NULL" +
                    ")",
            "CREATE TABLE postcode_interest (" +
                    "  account_id  TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE," +
                    "  post_code   TEXT NOT NULL REFERENCES postcodes(post_code)," +
                    "  PRIMARY KEY (account_id, post_code)" +
                    ")",
            "CREATE INDEX idx_pi_post_code ON postcode_interest(post_code)",
            "CREATE TABLE listings (" +
                    "  id            TEXT PRIMARY KEY," +
                    "  property_id   TEXT NOT NULL REFERENCES properties(id) ON DELETE CASCADE," +
                    "  is_discounted INTEGER NOT NULL DEFAULT 0," +
                    "  date_added    TEXT NOT NULL" +
                    ")",
            "CREATE INDEX idx_listings_property ON listings(property_id)",
            "CREATE TABLE listing_prices (" +
                    "  id          INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  listing_id  TEXT NOT NULL REFERENCES listings(id) ON DELETE CASCADE," +
                    "  price       REAL NOT NULL," +
                    "  updated_at  TEXT NOT NULL" +
                    ")",
            "CREATE INDEX idx_prices_listing_date ON listing_prices(listing_id, updated_at DESC)",
            "CREATE TABLE purchases (" +
                    "  id           INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  account_id   TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE," +
                    "  property_id  TEXT NOT NULL REFERENCES properties(id) ON DELETE CASCADE," +
                    "  purchased_at TEXT NOT NULL" +
                    ")",
    };
}
