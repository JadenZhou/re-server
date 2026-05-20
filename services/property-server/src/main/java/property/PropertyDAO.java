package property;

import db.Db;
import db.ObjectIdLike;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure CRUD over the property tables. No counter side-effects — analytics is
 * a separate service that the gateway bumps via HTTP.
 */
public class PropertyDAO {

    private static final int MAX_RESULTS = 1000;

    private final Connection c = Db.connection();

    public boolean newProperty(Property p) {
        if (p == null || p.postcode == null) return false;
        Db.ensurePostcode(c, p.postcode);
        String id = (p.propertyID == null || p.propertyID.isEmpty())
                ? ObjectIdLike.next() : p.propertyID;
        Long price = parseLongOrNull(p.propertyPrice);
        String sql = "INSERT INTO properties " +
                "(id, property_id, post_code, purchase_price, address, council_name, " +
                " property_type, contract_date, for_sale) " +
                "VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, p.postcode);
            if (price == null) ps.setNull(3, java.sql.Types.INTEGER); else ps.setLong(3, price);
            ps.setString(4, p.address);
            ps.setString(5, p.councilName);
            ps.setString(6, p.propertyType);
            ps.setString(7, p.contractDate);
            ps.setInt(8, p.forSale ? 1 : 0);
            ps.executeUpdate();
            p.propertyID = id;
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("newProperty failed", e);
        }
    }

    public Optional<Property> getPropertyById(String id) {
        if (id == null || id.isEmpty()) return Optional.empty();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM properties WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("getPropertyById failed", e);
        }
    }

    public List<Property> getPropertiesByPostCode(String postCode) {
        return queryList("SELECT * FROM properties WHERE post_code = ? LIMIT ?", ps -> {
            ps.setString(1, postCode);
            ps.setInt(2, MAX_RESULTS);
        });
    }

    public List<Property> getAllProperties() {
        return queryList("SELECT * FROM properties LIMIT ?", ps -> ps.setInt(1, MAX_RESULTS));
    }

    public List<Property> getPropertiesByPriceRange(long minPrice, long maxPrice) {
        return queryList(
                "SELECT * FROM properties WHERE purchase_price BETWEEN ? AND ? LIMIT ?",
                ps -> { ps.setLong(1, minPrice); ps.setLong(2, maxPrice); ps.setInt(3, MAX_RESULTS); });
    }

    /**
     * For the notifier: every for-sale property joined with its latest listing
     * price. One SQL round trip; gateway combines this with watched-postcode
     * data from the purchaser service.
     */
    public List<ForSaleRow> getForSaleWithLatestPrice() {
        List<ForSaleRow> out = new ArrayList<>();
        String sql =
                "SELECT p.id, p.post_code, p.property_id, " +
                "       (SELECT lp.price FROM listing_prices lp " +
                "         JOIN listings l ON l.id = lp.listing_id " +
                "         WHERE l.property_id = p.id " +
                "         ORDER BY lp.updated_at DESC LIMIT 1) AS latest_price " +
                "  FROM properties p WHERE p.for_sale = 1";
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                double price = rs.getDouble("latest_price");
                if (rs.wasNull()) continue;
                long nswPropertyId = rs.getLong("property_id");
                boolean propertyIdNull = rs.wasNull();
                out.add(new ForSaleRow(
                        rs.getString("id"),
                        rs.getString("post_code"),
                        propertyIdNull ? null : nswPropertyId,
                        price));
            }
        } catch (SQLException e) {
            throw new RuntimeException("getForSaleWithLatestPrice failed", e);
        }
        return out;
    }

    public record ForSaleRow(String propertyId, String postcode, Long nswPropertyId, double latestPrice) {}

    // ── helpers ───────────────────────────────────────────────────────────────

    private interface ParamBinder { void bind(PreparedStatement ps) throws SQLException; }

    private List<Property> queryList(String sql, ParamBinder binder) {
        List<Property> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("query failed: " + sql, e);
        }
        return out;
    }

    private static Property mapRow(ResultSet rs) throws SQLException {
        long price = rs.getLong("purchase_price");
        boolean priceNull = rs.wasNull();
        Property p = new Property(
                rs.getString("id"),
                rs.getString("post_code"),
                priceNull ? null : Long.toString(price));
        p.address = rs.getString("address");
        p.councilName = rs.getString("council_name");
        p.propertyType = rs.getString("property_type");
        p.contractDate = rs.getString("contract_date");
        p.forSale = rs.getInt("for_sale") == 1;
        return p;
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
