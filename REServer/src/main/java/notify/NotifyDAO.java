package notify;

import db.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the inputs NotifyService needs by reading from SQLite. Kept
 * separate from NotifyService so the service stays a pure-data unit
 * (and its existing test suite continues to pass without a DB).
 */
public class NotifyDAO {

    private final Connection c;

    public NotifyDAO() {
        this.c = Db.connection();
    }

    /** Postcode → list of for-sale properties in that postcode. */
    public Map<String, List<PropertyForSale>> buildPostcodeIndex() {
        // Join listings → properties → latest price.
        // For each listing, pick the most recent price by updated_at desc.
        String sql =
                "SELECT p.post_code, p.property_id, lp.price " +
                "FROM listings l " +
                "JOIN properties p ON p.id = l.property_id " +
                "JOIN listing_prices lp ON lp.listing_id = l.id " +
                "WHERE lp.id = ( " +
                "    SELECT id FROM listing_prices " +
                "    WHERE listing_id = l.id " +
                "    ORDER BY updated_at DESC, id DESC LIMIT 1 " +
                ")";
        Map<String, List<PropertyForSale>> index = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String postcode = rs.getString("post_code");
                long propertyId = rs.getLong("property_id");
                boolean propertyIdNull = rs.wasNull();
                double price = rs.getDouble("price");
                if (postcode == null || propertyIdNull) continue;
                index.computeIfAbsent(postcode, k -> new ArrayList<>())
                        .add(new PropertyForSale(propertyId, price, postcode));
            }
        } catch (SQLException e) {
            throw new RuntimeException("buildPostcodeIndex failed", e);
        }
        return index.isEmpty() ? Collections.emptyMap() : index;
    }

    /** All Buyer accounts with their (possibly empty) postcode lists. */
    public List<PurchaserSummary> fetchPurchasers() {
        Map<String, PurchaserSummary> byId = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, name, email FROM accounts WHERE account_type = 'Buyer'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString("id");
                byId.put(id, new PurchaserSummary(
                        id, rs.getString("name"), rs.getString("email"), new ArrayList<>()));
            }
        } catch (SQLException e) {
            throw new RuntimeException("fetchPurchasers failed", e);
        }
        if (byId.isEmpty()) return Collections.emptyList();

        try (PreparedStatement ps = c.prepareStatement(
                "SELECT account_id, post_code FROM postcode_interest");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                PurchaserSummary p = byId.get(rs.getString("account_id"));
                if (p != null) p.postcodes.add(rs.getString("post_code"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("fetchPurchasers interests failed", e);
        }
        return new ArrayList<>(byId.values());
    }
}
