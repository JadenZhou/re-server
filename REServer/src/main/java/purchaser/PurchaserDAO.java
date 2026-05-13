package purchaser;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
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
 * Buyers live in the shared `accounts` collection alongside other account types.
 * All queries filter `account_type = "Buyer"`. New buyers and synthetic seed
 * always set `account_type = "Buyer"` so they are visible to teammate code.
 *
 * `accounts` schema (mixed legacy + new):
 *   _id          ObjectId
 *   id           Integer    (legacy, optional)
 *   name         String
 *   email        String
 *   password     String     (legacy, optional)
 *   account_type String     ("Buyer" | "Seller" | ...)
 *   postcodes    [String]   (NEW — may be missing on legacy docs)
 */
public class PurchaserDAO {

    private static final String DB_NAME = "nsw_property_data";
    private static final String COLLECTION_NAME = "accounts";
    private static final String BUYER_TYPE = "Buyer";
    private static final int MAX_RESULTS = 1000;
    public static final int MAX_POSTCODES = 5;

    // NSW postcode ranges (rough): 1000-2599, 2619-2899, 2921-2999
    private static final int[][] NSW_RANGES = {
            {1000, 2599}, {2619, 2899}, {2921, 2999}
    };

    private static final Bson BUYER_FILTER = Filters.eq("account_type", BUYER_TYPE);

    private final MongoCollection<Document> coll;

    public PurchaserDAO() {
        String uri = System.getenv("MONGO_URI");
        if (uri == null || uri.isEmpty()) {
            throw new IllegalStateException("MONGO_URI env var is required");
        }
        MongoClient client = MongoClients.create(uri);
        MongoDatabase db = client.getDatabase(DB_NAME);
        this.coll = db.getCollection(COLLECTION_NAME);
    }

    public static boolean isValidNswPostcode(String pc) {
        if (pc == null) return false;
        try {
            int n = Integer.parseInt(pc.trim());
            for (int[] r : NSW_RANGES) {
                if (n >= r[0] && n <= r[1]) return true;
            }
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Returns new purchaser id, or null if invalid. */
    public String createPurchaser(String name, String email, List<String> postcodes) {
        if (name == null || name.isBlank()) return null;
        if (email == null || email.isBlank()) return null;

        List<String> clean = sanitizePostcodes(postcodes);
        if (clean == null) return null;

        Document d = new Document()
                .append("name", name.trim())
                .append("email", email.trim().toLowerCase())
                .append("account_type", BUYER_TYPE)
                .append("postcodes", clean);
        coll.insertOne(d);
        return d.getObjectId("_id").toHexString();
    }

    public List<Purchaser> getAllPurchasers() {
        List<Purchaser> out = new ArrayList<>();
        for (Document d : coll.find(BUYER_FILTER).limit(MAX_RESULTS)) {
            out.add(toPurchaser(d));
        }
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
        Bson filter = Filters.and(BUYER_FILTER, Filters.eq("postcodes", postcode));
        for (Document d : coll.find(filter).limit(MAX_RESULTS)) {
            out.add(toPurchaser(d));
        }
        return out;
    }

    /** Adds a postcode of interest. Returns OK / NOT_FOUND / INVALID_POSTCODE / DUPLICATE / LIMIT_REACHED. */
    public AddResult addInterest(String purchaserId, String postcode) {
        if (!ObjectId.isValid(purchaserId)) return AddResult.NOT_FOUND;
        if (!isValidNswPostcode(postcode)) return AddResult.INVALID_POSTCODE;

        Document existing = coll.find(Filters.and(
                Filters.eq("_id", new ObjectId(purchaserId)),
                BUYER_FILTER)).first();
        if (existing == null) return AddResult.NOT_FOUND;

        List<String> current = existing.getList("postcodes", String.class, Collections.emptyList());
        if (current.contains(postcode)) return AddResult.DUPLICATE;
        if (current.size() >= MAX_POSTCODES) return AddResult.LIMIT_REACHED;

        UpdateResult r = coll.updateOne(
                Filters.eq("_id", new ObjectId(purchaserId)),
                Updates.addToSet("postcodes", postcode));
        return r.getModifiedCount() == 1 ? AddResult.OK : AddResult.NOT_FOUND;
    }

    public boolean removeInterest(String purchaserId, String postcode) {
        if (!ObjectId.isValid(purchaserId)) return false;
        UpdateResult r = coll.updateOne(
                Filters.and(Filters.eq("_id", new ObjectId(purchaserId)), BUYER_FILTER),
                Updates.pull("postcodes", postcode));
        return r.getModifiedCount() == 1;
    }

    /** Bulk-insert N synthetic Buyer accounts each with 0..5 random NSW postcodes. */
    public int seedPurchasers(int count) {
        Random rand = new Random();
        List<Document> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int numPostcodes = rand.nextInt(MAX_POSTCODES + 1); // 0..5
            Set<String> picks = new LinkedHashSet<>();
            int attempts = 0;
            while (picks.size() < numPostcodes && attempts < 50) {
                picks.add(String.format("%04d", randomNswPostcode(rand)));
                attempts++;
            }
            String suffix = Long.toHexString(System.nanoTime()) + "-" + i;
            batch.add(new Document()
                    .append("name", "Synthetic Buyer " + i)
                    .append("email", "buyer" + i + "-" + suffix + "@example.com")
                    .append("account_type", BUYER_TYPE)
                    .append("postcodes", new ArrayList<>(picks)));

            if (batch.size() >= 1000) {
                coll.insertMany(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) coll.insertMany(batch);
        return count;
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
        // postcodes may be missing on legacy account docs
        List<String> postcodes = d.getList("postcodes", String.class, Collections.emptyList());
        return new Purchaser(id, d.getString("name"), d.getString("email"), new ArrayList<>(postcodes));
    }

    public enum AddResult {
        OK, NOT_FOUND, INVALID_POSTCODE, LIMIT_REACHED, DUPLICATE
    }
}
