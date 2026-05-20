package purchaser;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import db.Db;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Buyer accounts + watched postcodes. Postcodes live as an array
 * (`postcode_interest`) on the account document — natural Mongo shape.
 *
 * Pure CRUD; no analytics side-effects.
 */
public class PurchaserDAO {

    private static final String BUYER_TYPE = "Buyer";
    private static final int MAX_RESULTS = 1000;
    public static final int MAX_POSTCODES = 5;

    private static final int[][] NSW_RANGES = {
            {1000, 2599}, {2619, 2899}, {2921, 2999}
    };
    private static final Bson BUYER_FILTER = Filters.eq("account_type", BUYER_TYPE);

    private final MongoCollection<Document> coll = Db.database().getCollection("accounts");

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
        Document d = new Document()
                .append("name", name.trim())
                .append("email", email.trim().toLowerCase())
                .append("account_type", BUYER_TYPE)
                .append("postcode_interest", clean);
        coll.insertOne(d);
        return d.getObjectId("_id").toHexString();
    }

    public List<Purchaser> getAllPurchasers() {
        List<Purchaser> out = new ArrayList<>();
        for (Document d : coll.find(BUYER_FILTER).limit(MAX_RESULTS)) out.add(toPurchaser(d));
        return out;
    }

    public Optional<Purchaser> getPurchaserById(String id) {
        if (!ObjectId.isValid(id)) return Optional.empty();
        Document d = coll.find(Filters.and(
                Filters.eq("_id", new ObjectId(id)),
                BUYER_FILTER)).first();
        return Optional.ofNullable(d).map(this::toPurchaser);
    }

    public List<Purchaser> getPurchasersByPostcode(String postcode) {
        List<Purchaser> out = new ArrayList<>();
        Bson filter = Filters.and(BUYER_FILTER, Filters.eq("postcode_interest", postcode));
        for (Document d : coll.find(filter).limit(MAX_RESULTS)) out.add(toPurchaser(d));
        return out;
    }

    public AddResult addInterest(String purchaserId, String postcode) {
        if (!ObjectId.isValid(purchaserId)) return AddResult.NOT_FOUND;
        if (!isValidNswPostcode(postcode)) return AddResult.INVALID_POSTCODE;
        Document existing = coll.find(Filters.and(
                Filters.eq("_id", new ObjectId(purchaserId)),
                BUYER_FILTER)).first();
        if (existing == null) return AddResult.NOT_FOUND;
        List<String> current = existing.getList("postcode_interest", String.class, Collections.emptyList());
        if (current.contains(postcode)) return AddResult.DUPLICATE;
        if (current.size() >= MAX_POSTCODES) return AddResult.LIMIT_REACHED;
        UpdateResult r = coll.updateOne(
                Filters.eq("_id", new ObjectId(purchaserId)),
                Updates.addToSet("postcode_interest", postcode));
        return r.getModifiedCount() == 1 ? AddResult.OK : AddResult.NOT_FOUND;
    }

    public boolean removeInterest(String purchaserId, String postcode) {
        if (!ObjectId.isValid(purchaserId)) return false;
        UpdateResult r = coll.updateOne(
                Filters.and(Filters.eq("_id", new ObjectId(purchaserId)), BUYER_FILTER),
                Updates.pull("postcode_interest", postcode));
        return r.getModifiedCount() == 1;
    }

    public int seedPurchasers(int count) {
        Random rand = new Random();
        List<Document> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            batch.add(syntheticBuyer(i, rand));
            if (batch.size() >= 1000) {
                coll.insertMany(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) coll.insertMany(batch);
        return count;
    }

    private static Document syntheticBuyer(int i, Random rand) {
        String suffix = Long.toHexString(System.nanoTime()) + "-" + i;
        return new Document()
                .append("name", "Synthetic Buyer " + i)
                .append("email", "buyer" + i + "-" + suffix + "@example.com")
                .append("account_type", BUYER_TYPE)
                .append("postcode_interest", randomPostcodes(rand));
    }

    private static List<String> randomPostcodes(Random rand) {
        int target = rand.nextInt(MAX_POSTCODES + 1);
        Set<String> picks = new LinkedHashSet<>();
        int attempts = 0;
        while (picks.size() < target && attempts < 50) {
            picks.add(String.format("%04d", randomNswPostcode(rand)));
            attempts++;
        }
        return new ArrayList<>(picks);
    }

    private static int randomNswPostcode(Random rand) {
        int[] r = NSW_RANGES[rand.nextInt(NSW_RANGES.length)];
        return r[0] + rand.nextInt(r[1] - r[0] + 1);
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

    private Purchaser toPurchaser(Document d) {
        String id = d.getObjectId("_id").toHexString();
        List<String> postcodes = d.getList("postcode_interest", String.class, Collections.emptyList());
        return new Purchaser(id, d.getString("name"), d.getString("email"), new ArrayList<>(postcodes));
    }

    public enum AddResult {
        OK, NOT_FOUND, INVALID_POSTCODE, LIMIT_REACHED, DUPLICATE
    }
}
