package listing;

import db.Db;
import db.ObjectIdLike;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ListingDAO {

    private static final int MAX_RESULTS = 1000;

    private final Connection c;

    public ListingDAO() {
        this.c = Db.connection();
    }

    /** Creates a listing for the given property and seeds its initial price. */
    public Optional<String> createListing(String propertyId, double price) {
        if (!ObjectIdLike.isValid(propertyId)) return Optional.empty();
        if (!propertyExists(propertyId)) return Optional.empty();

        String listingId = ObjectIdLike.next();
        String now = Instant.now().toString();
        try {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO listings (id, property_id, is_discounted, date_added) " +
                            "VALUES (?, ?, 0, ?)")) {
                ps.setString(1, listingId);
                ps.setString(2, propertyId);
                ps.setString(3, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO listing_prices (listing_id, price, updated_at) VALUES (?, ?, ?)")) {
                ps.setString(1, listingId);
                ps.setDouble(2, price);
                ps.setString(3, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE properties SET for_sale = 1 WHERE id = ?")) {
                ps.setString(1, propertyId);
                ps.executeUpdate();
            }
            c.commit();
            return Optional.of(listingId);
        } catch (SQLException e) {
            rollbackQuietly();
            throw new RuntimeException("createListing failed", e);
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    /**
     * Randomly samples up to 1000 properties and creates a listing + initial
     * price (at +20% of purchase_price) for each. Mirrors the Mongo branch's
     * seed behavior for parity.
     */
    public int seedListings() {
        List<String> propIds = new ArrayList<>();
        List<Long> prices = new ArrayList<>();
        String sampleSql = "SELECT id, purchase_price FROM properties " +
                "WHERE purchase_price IS NOT NULL AND purchase_price > 0 " +
                "ORDER BY RANDOM() LIMIT 1000";
        try (PreparedStatement ps = c.prepareStatement(sampleSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                propIds.add(rs.getString("id"));
                prices.add(rs.getLong("purchase_price"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("seedListings sample failed", e);
        }
        if (propIds.isEmpty()) return 0;

        String now = Instant.now().toString();
        try {
            c.setAutoCommit(false);
            try (PreparedStatement insListing = c.prepareStatement(
                    "INSERT INTO listings (id, property_id, is_discounted, date_added) VALUES (?, ?, 0, ?)");
                 PreparedStatement insPrice = c.prepareStatement(
                         "INSERT INTO listing_prices (listing_id, price, updated_at) VALUES (?, ?, ?)");
                 PreparedStatement updProp = c.prepareStatement(
                         "UPDATE properties SET for_sale = 1 WHERE id = ?")) {

                for (int i = 0; i < propIds.size(); i++) {
                    String listingId = ObjectIdLike.next();
                    String propId = propIds.get(i);
                    double listingPrice = prices.get(i) * 1.2;

                    insListing.setString(1, listingId);
                    insListing.setString(2, propId);
                    insListing.setString(3, now);
                    insListing.addBatch();

                    insPrice.setString(1, listingId);
                    insPrice.setDouble(2, listingPrice);
                    insPrice.setString(3, now);
                    insPrice.addBatch();

                    updProp.setString(1, propId);
                    updProp.addBatch();
                }
                insListing.executeBatch();
                insPrice.executeBatch();
                updProp.executeBatch();
            }
            c.commit();
            return propIds.size();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new RuntimeException("seedListings failed", e);
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    public List<Listing> getAllListings() {
        List<Listing> out = new ArrayList<>();
        String sql = "SELECT * FROM listings LIMIT ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, MAX_RESULTS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(toListing(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("getAllListings failed", e);
        }
        return out;
    }

    public Optional<Listing> getListingById(String id) {
        if (!ObjectIdLike.isValid(id)) return Optional.empty();
        String sql = "SELECT * FROM listings WHERE id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(toListing(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("getListingById failed", e);
        }
    }

    /** Returns all price entries for a listing, oldest first. */
    public List<PriceEntry> getPriceHistory(String listingId) {
        if (!ObjectIdLike.isValid(listingId)) return List.of();
        List<PriceEntry> out = new ArrayList<>();
        String sql = "SELECT price, updated_at FROM listing_prices " +
                "WHERE listing_id = ? ORDER BY updated_at ASC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PriceEntry(rs.getDouble("price"), rs.getString("updated_at")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("getPriceHistory failed", e);
        }
        return out;
    }

    /** Adds a new price update. Marks the listing as discounted if the price drops below the latest. */
    public boolean addPriceUpdate(String listingId, double newPrice) {
        if (!ObjectIdLike.isValid(listingId)) return false;
        if (!listingExists(listingId)) return false;

        double latest = getLatestPrice(listingId);
        try {
            c.setAutoCommit(false);
            if (latest > 0 && newPrice < latest) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE listings SET is_discounted = 1 WHERE id = ?")) {
                    ps.setString(1, listingId);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO listing_prices (listing_id, price, updated_at) VALUES (?, ?, ?)")) {
                ps.setString(1, listingId);
                ps.setDouble(2, newPrice);
                ps.setString(3, Instant.now().toString());
                ps.executeUpdate();
            }
            c.commit();
            return true;
        } catch (SQLException e) {
            rollbackQuietly();
            throw new RuntimeException("addPriceUpdate failed", e);
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    private double getLatestPrice(String listingId) {
        String sql = "SELECT price FROM listing_prices WHERE listing_id = ? " +
                "ORDER BY updated_at DESC LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
                return 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("getLatestPrice failed", e);
        }
    }

    private boolean propertyExists(String propertyId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM properties WHERE id = ?")) {
            ps.setString(1, propertyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("propertyExists failed", e);
        }
    }

    private boolean listingExists(String listingId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM listings WHERE id = ?")) {
            ps.setString(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("listingExists failed", e);
        }
    }

    private Listing toListing(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String propertyId = rs.getString("property_id");
        boolean discounted = rs.getInt("is_discounted") == 1;
        String dateAdded = rs.getString("date_added");
        double latest = getLatestPrice(id);
        return new Listing(id, propertyId, discounted, dateAdded, latest);
    }

    private void rollbackQuietly() {
        try { c.rollback(); } catch (SQLException ignored) {}
    }

    private void setAutoCommitQuietly(boolean v) {
        try { c.setAutoCommit(v); } catch (SQLException ignored) {}
    }
}
