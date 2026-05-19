package stats;

import db.Db;
import io.javalin.http.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /stats — at-a-glance numbers for demos and audits.
 *
 * Returns row counts per table, plus the top postcodes by search_count
 * (which is incremented every time a property in that postcode is read).
 * Output is JSON so it's trivially curlable and pipeable into jq.
 *
 * Query params:
 *   ?topPostcodes=N  override how many top postcodes to return (default 10)
 */
public class StatsController {

    private static final int DEFAULT_TOP = 10;

    public void stats(Context ctx) {
        int top = parseIntParam(ctx.queryParam("topPostcodes"), DEFAULT_TOP);

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        Connection c = Db.connection();

        for (String table : new String[] {
                "properties", "listings", "listing_prices",
                "accounts", "postcode_interest", "postcodes", "purchases"
        }) {
            counts.put(table, countTable(c, table));
        }
        out.put("counts", counts);
        out.put("topPostcodesBySearch", topPostcodes(c, top));
        out.put("topPropertiesBySearch", topProperties(c, top));
        out.put("totalSearches", sumColumn(c, "postcodes", "search_count"));
        out.put("forSaleListings", countWhere(c, "listings", ""));
        out.put("propertiesForSale", countWhere(c, "properties", "WHERE for_sale = 1"));

        ctx.json(out);
    }

    private static long countTable(Connection c, String table) {
        return countWhere(c, table, "");
    }

    private static long countWhere(Connection c, String table, String where) {
        String sql = "SELECT COUNT(*) FROM " + table + " " + where;
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            return -1L;
        }
    }

    private static long sumColumn(Connection c, String table, String col) {
        String sql = "SELECT COALESCE(SUM(" + col + "), 0) FROM " + table;
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            return -1L;
        }
    }

    private static List<Map<String, Object>> topPostcodes(Connection c, int top) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT post_code, search_count FROM postcodes " +
                "WHERE search_count > 0 ORDER BY search_count DESC, post_code ASC LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, top);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("postcode", rs.getString(1));
                    row.put("searchCount", rs.getLong(2));
                    out.add(row);
                }
            }
        } catch (SQLException ignored) {}
        return out;
    }

    private static List<Map<String, Object>> topProperties(Connection c, int top) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT id, post_code, search_count FROM properties " +
                "WHERE search_count > 0 ORDER BY search_count DESC, id ASC LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, top);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString(1));
                    row.put("postcode", rs.getString(2));
                    row.put("searchCount", rs.getLong(3));
                    out.add(row);
                }
            }
        } catch (SQLException ignored) {}
        return out;
    }

    private static int parseIntParam(String v, int dflt) {
        if (v == null || v.isEmpty()) return dflt;
        try {
            int n = Integer.parseInt(v.trim());
            return Math.max(1, Math.min(100, n));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
