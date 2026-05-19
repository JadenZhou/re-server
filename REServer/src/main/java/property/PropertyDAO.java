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

public class PropertyDAO {

    // Cap unbounded queries so a single request can't try to ship millions of rows.
    private static final int MAX_RESULTS = 1000;

    private final Connection c;

    public PropertyDAO() {
        this.c = Db.connection();
    }

    public boolean newProperty(Property property) {
        if (property == null || property.postcode == null) return false;
        Db.ensurePostcode(c, property.postcode);
        String id = (property.propertyID == null || property.propertyID.isEmpty())
                ? ObjectIdLike.next()
                : property.propertyID;
        Long price = parseLongOrNull(property.propertyPrice);
        String sql = "INSERT INTO properties " +
                "(id, property_id, post_code, purchase_price, address, council_name, " +
                " property_type, contract_date, for_sale, search_count) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            // Property model has no NSW source property_id slot; leave null. Loader populates it.
            ps.setObject(2, null);
            ps.setString(3, property.postcode);
            if (price == null) ps.setNull(4, java.sql.Types.INTEGER); else ps.setLong(4, price);
            ps.setString(5, property.address);
            ps.setString(6, property.councilName);
            ps.setString(7, property.propertyType);
            ps.setString(8, property.contractDate);
            ps.setInt(9, property.forSale ? 1 : 0);
            ps.executeUpdate();
            // Propagate the generated id back so callers (and any HTML render) see it.
            property.propertyID = id;
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("newProperty failed", e);
        }
    }

    public Optional<Property> getPropertyById(String propertyID) {
        if (propertyID == null || propertyID.isEmpty()) return Optional.empty();
        String sql = "SELECT * FROM properties WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, propertyID);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Property p = mapRow(rs);
                auditProperty(p);
                return Optional.of(p);
            }
        } catch (SQLException e) {
            throw new RuntimeException("getPropertyById failed", e);
        }
    }

    public List<Property> getPropertiesByPostCode(String postCode) {
        List<Property> out = new ArrayList<>();
        String sql = "SELECT * FROM properties WHERE post_code = ? LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, postCode);
            ps.setInt(2, MAX_RESULTS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("getPropertiesByPostCode failed", e);
        }
        out.forEach(this::auditProperty);
        return out;
    }

    public List<Property> getAllProperties() {
        List<Property> out = new ArrayList<>();
        String sql = "SELECT * FROM properties LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, MAX_RESULTS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("getAllProperties failed", e);
        }
        out.forEach(this::auditProperty);
        return out;
    }

    public List<Property> getPropertiesByPriceRange(long minPrice, long maxPrice) {
        List<Property> out = new ArrayList<>();
        String sql = "SELECT * FROM properties WHERE purchase_price BETWEEN ? AND ? LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, minPrice);
            ps.setLong(2, maxPrice);
            ps.setInt(3, MAX_RESULTS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("getPropertiesByPriceRange failed", e);
        }
        out.forEach(this::auditProperty);
        return out;
    }

    public List<String> getAllPropertyPrices() {
        List<String> out = new ArrayList<>();
        String sql = "SELECT purchase_price FROM properties LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, MAX_RESULTS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long price = rs.getLong(1);
                    if (!rs.wasNull()) out.add(Long.toString(price));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("getAllPropertyPrices failed", e);
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
        p.searchCount = rs.getLong("search_count");
        return p;
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Increment search counters: per-property and per-postcode. Mirrors what
     * the Mongo branch did with {@code $inc} on `properties` and `postcode_stats`.
     */
    private void auditProperty(Property property) {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE properties SET search_count = search_count + 1 WHERE id = ?")) {
            ps.setString(1, property.propertyID);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("audit property failed", e);
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE postcodes SET search_count = search_count + 1 WHERE post_code = ?")) {
            ps.setString(1, property.postcode);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("audit postcode failed", e);
        }
    }
}
