package analytics;

import db.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** All reads and writes against the analytics tables. */
public class AnalyticsDAO {

    private final Connection c = Db.connection();

    public long incrementPropertyView(String propertyId) {
        return upsertCount("property_views", "property_id", propertyId);
    }

    public long getPropertyViews(String propertyId) {
        return readCount("property_views", "property_id", propertyId);
    }

    public long incrementPostcodeSearch(String postcode) {
        return upsertCount("postcode_searches", "post_code", postcode);
    }

    public long getPostcodeSearches(String postcode) {
        return readCount("postcode_searches", "post_code", postcode);
    }

    /**
     * UPSERT the counter row, atomically bump, return new value. SQLite's
     * `INSERT … ON CONFLICT DO UPDATE` makes this one round trip.
     */
    private long upsertCount(String table, String keyCol, String key) {
        if (key == null || key.isBlank()) return 0;
        String sql = "INSERT INTO " + table + " (" + keyCol + ", count) VALUES (?, 1) " +
                "ON CONFLICT(" + keyCol + ") DO UPDATE SET count = count + 1 " +
                "RETURNING count";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new RuntimeException("upsertCount failed on " + table, e);
        }
    }

    private long readCount(String table, String keyCol, String key) {
        if (key == null) return 0;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count FROM " + table + " WHERE " + keyCol + " = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new RuntimeException("readCount failed on " + table, e);
        }
    }
}
