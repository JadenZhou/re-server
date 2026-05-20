package purchaser;

import db.Db;
import db.ObjectIdLike;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Buyer accounts + their watched postcodes. Pure CRUD over the purchaser
 * tables; no analytics side-effects.
 */
public class PurchaserDAO {

    private static final String BUYER_TYPE = "Buyer";
    private static final int MAX_RESULTS = 1000;
    public static final int MAX_POSTCODES = 5;

    private static final int[][] NSW_RANGES = {
            {1000, 2599}, {2619, 2899}, {2921, 2999}
    };

    private final Connection c = Db.connection();

    public static boolean isValidNswPostcode(String pc) {
        if (pc == null) return false;
        try {
            int n = Integer.parseInt(pc.trim());
            for (int[] r : NSW_RANGES) if (n >= r[0] && n <= r[1]) return true;
            return false;
        } catch (NumberFormatException e) { return false; }
    }

    public String createPurchaser(String name, String email, List<String> postcodes) {
        if (name == null || name.isBlank() || email == null || email.isBlank()) return null;
        List<String> clean = sanitizePostcodes(postcodes);
        if (clean == null) return null;
        String id = ObjectIdLike.next();
        try {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO accounts (id, name, email, account_type) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, id); ps.setString(2, name.trim());
                ps.setString(3, email.trim().toLowerCase()); ps.setString(4, BUYER_TYPE);
                ps.executeUpdate();
            }
            insertInterests(id, clean);
            c.commit();
            return id;
        } catch (SQLException e) {
            rollbackQuietly();
            return null;
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    public List<Purchaser> getAllPurchasers() {
        return queryList(
                "SELECT id, name, email FROM accounts WHERE account_type = ? LIMIT ?",
                ps -> { ps.setString(1, BUYER_TYPE); ps.setInt(2, MAX_RESULTS); });
    }

    public Optional<Purchaser> getPurchaserById(String id) {
        if (!ObjectIdLike.isValid(id)) return Optional.empty();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, name, email FROM accounts WHERE id = ? AND account_type = ?")) {
            ps.setString(1, id); ps.setString(2, BUYER_TYPE);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(loadPurchaser(rs.getString("id"), rs.getString("name"), rs.getString("email")));
            }
        } catch (SQLException e) { throw new RuntimeException("getPurchaserById failed", e); }
    }

    public List<Purchaser> getPurchasersByPostcode(String postcode) {
        return queryList(
                "SELECT a.id, a.name, a.email FROM accounts a " +
                "JOIN postcode_interest pi ON pi.account_id = a.id " +
                "WHERE pi.post_code = ? AND a.account_type = ? LIMIT ?",
                ps -> { ps.setString(1, postcode); ps.setString(2, BUYER_TYPE); ps.setInt(3, MAX_RESULTS); });
    }

    public AddResult addInterest(String purchaserId, String postcode) {
        if (!ObjectIdLike.isValid(purchaserId)) return AddResult.NOT_FOUND;
        if (!isValidNswPostcode(postcode)) return AddResult.INVALID_POSTCODE;
        if (!buyerExists(purchaserId)) return AddResult.NOT_FOUND;
        List<String> current = fetchPostcodes(purchaserId);
        if (current.contains(postcode)) return AddResult.DUPLICATE;
        if (current.size() >= MAX_POSTCODES) return AddResult.LIMIT_REACHED;
        Db.ensurePostcode(c, postcode);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO postcode_interest (account_id, post_code) VALUES (?, ?)")) {
            ps.setString(1, purchaserId); ps.setString(2, postcode);
            return ps.executeUpdate() == 1 ? AddResult.OK : AddResult.NOT_FOUND;
        } catch (SQLException e) { throw new RuntimeException("addInterest failed", e); }
    }

    public boolean removeInterest(String purchaserId, String postcode) {
        if (!ObjectIdLike.isValid(purchaserId) || !buyerExists(purchaserId)) return false;
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM postcode_interest WHERE account_id = ? AND post_code = ?")) {
            ps.setString(1, purchaserId); ps.setString(2, postcode);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) { throw new RuntimeException("removeInterest failed", e); }
    }

    public int seedPurchasers(int count) {
        Random rand = new Random();
        try {
            c.setAutoCommit(false);
            try (PreparedStatement insAcc = c.prepareStatement(
                    "INSERT INTO accounts (id, name, email, account_type) VALUES (?, ?, ?, ?)");
                 PreparedStatement insPi = c.prepareStatement(
                         "INSERT OR IGNORE INTO postcode_interest (account_id, post_code) VALUES (?, ?)")) {
                for (int i = 0; i < count; i++) {
                    int numPostcodes = rand.nextInt(MAX_POSTCODES + 1);
                    Set<String> picks = new LinkedHashSet<>();
                    int attempts = 0;
                    while (picks.size() < numPostcodes && attempts < 50) {
                        picks.add(String.format("%04d", randomNswPostcode(rand)));
                        attempts++;
                    }
                    String suffix = Long.toHexString(System.nanoTime()) + "-" + i;
                    String id = ObjectIdLike.next();
                    insAcc.setString(1, id);
                    insAcc.setString(2, "Synthetic Buyer " + i);
                    insAcc.setString(3, "buyer" + i + "-" + suffix + "@example.com");
                    insAcc.setString(4, BUYER_TYPE);
                    insAcc.addBatch();
                    for (String pc : picks) {
                        Db.ensurePostcode(c, pc);
                        insPi.setString(1, id);
                        insPi.setString(2, pc);
                        insPi.addBatch();
                    }
                    if ((i + 1) % 500 == 0) {
                        insAcc.executeBatch();
                        insPi.executeBatch();
                    }
                }
                insAcc.executeBatch();
                insPi.executeBatch();
            }
            c.commit();
            return count;
        } catch (SQLException e) {
            rollbackQuietly();
            throw new RuntimeException("seedPurchasers failed", e);
        } finally {
            setAutoCommitQuietly(true);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private interface ParamBinder { void bind(PreparedStatement ps) throws SQLException; }

    private List<Purchaser> queryList(String sql, ParamBinder binder) {
        List<Purchaser> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(loadPurchaser(rs.getString("id"), rs.getString("name"), rs.getString("email")));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("query failed: " + sql, e);
        }
        return out;
    }

    private Purchaser loadPurchaser(String id, String name, String email) {
        return new Purchaser(id, name, email, fetchPostcodes(id));
    }

    private List<String> fetchPostcodes(String accountId) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT post_code FROM postcode_interest WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) { throw new RuntimeException("fetchPostcodes failed", e); }
        return out;
    }

    private boolean buyerExists(String id) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM accounts WHERE id = ? AND account_type = ?")) {
            ps.setString(1, id); ps.setString(2, BUYER_TYPE);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { throw new RuntimeException("buyerExists failed", e); }
    }

    private List<String> sanitizePostcodes(List<String> in) {
        if (in == null) return new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String p : in) {
            if (p == null) continue;
            String t = p.trim();
            if (!isValidNswPostcode(t)) return null;
            seen.add(t);
            if (seen.size() > MAX_POSTCODES) return null;
        }
        return new ArrayList<>(seen);
    }

    private void insertInterests(String accountId, List<String> postcodes) throws SQLException {
        if (postcodes.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO postcode_interest (account_id, post_code) VALUES (?, ?)")) {
            for (String pc : postcodes) {
                Db.ensurePostcode(c, pc);
                ps.setString(1, accountId);
                ps.setString(2, pc);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static int randomNswPostcode(Random rand) {
        int[] r = NSW_RANGES[rand.nextInt(NSW_RANGES.length)];
        return r[0] + rand.nextInt(r[1] - r[0] + 1);
    }

    private void rollbackQuietly() { try { c.rollback(); } catch (SQLException ignored) {} }
    private void setAutoCommitQuietly(boolean v) { try { c.setAutoCommit(v); } catch (SQLException ignored) {} }

    public enum AddResult {
        OK, NOT_FOUND, INVALID_POSTCODE, LIMIT_REACHED, DUPLICATE
    }
}
