package purchaser;

import app.Mongo;
import com.mongodb.client.MongoCollection;
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
 *   postcode_interest [String]   (may be missing on legacy docs)
 */
public class PurchaserDAO {

    private static final String COLLECTION_NAME = "accounts";
    private static final String BUYER_TYPE = "Buyer";
    private static final String FIELD_ID = "_id";
    private static final String FIELD_POSTCODE_INTEREST = "postcode_interest";
    private static final String FIELD_ACCOUNT_TYPE = "account_type";
    private static final int MAX_RESULTS = 1000;
    private static final int BATCH_SIZE = 1000;
    private static final int SEED_MAX_ATTEMPTS = 50;
    public static final int MAX_POSTCODES = 5;

    // NSW postcode ranges (rough): 1000-2599, 2619-2899, 2921-2999
    private static final int[][] NSW_RANGES = {
            {1000, 2599}, {2619, 2899}, {2921, 2999}
    };

    private static final Bson BUYER_FILTER = Filters.eq(FIELD_ACCOUNT_TYPE, BUYER_TYPE);

    private final MongoCollection<Document> coll;

    public PurchaserDAO() {
        this.coll = Mongo.database().getCollection(COLLECTION_NAME);
    }

    public static boolean isValidNswPostcode(String pc) {
        if (pc == null) {
            return false;
        }
        try {
            int n = Integer.parseInt(pc.trim());
            for (int[] r : NSW_RANGES) {
                if (n >= r[0] && n <= r[1]) {
                    return true;
                }
            }
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Returns new purchaser id, or null if invalid. */
    public String createPurchaser(String name, String email, List<String> postcodes) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (email == null || email.isBlank()) {
            return null;
        }

        List<String> clean = sanitizePostcodes(postcodes);
        if (clean == null) {
            return null;
        }

        Document d = new Document()
                .append("name", name.trim())
                .append("email", email.trim().toLowerCase())
                .append(FIELD_ACCOUNT_TYPE, BUYER_TYPE)
                .append(FIELD_POSTCODE_INTEREST, clean);
        coll.insertOne(d);
        return d.getObjectId(FIELD_ID).toHexString();
    }

    public List<Purchaser> getAllPurchasers() {
        List<Purchaser> out = new ArrayList<>();
        for (Document d : coll.find(BUYER_FILTER).limit(MAX_RESULTS)) {
            out.add(toPurchaser(d));
        }
        return out;
    }

    public Optional<Purchaser> getPurchaserById(String id) {
        if (!ObjectId.isValid(id)) {
            return Optional.empty();
        }
        Document d = coll.find(Filters.and(
                Filters.eq(FIELD_ID, new ObjectId(id)),
                BUYER_FILTER)).first();
        return Optional.ofNullable(d).map(this::toPurchaser);
    }

    public List<Purchaser> getPurchasersByPostcode(String postcode) {
        List<Purchaser> out = new ArrayList<>();
        Bson filter = Filters.and(BUYER_FILTER, Filters.eq(FIELD_POSTCODE_INTEREST, postcode));
        for (Document d : coll.find(filter).limit(MAX_RESULTS)) {
            out.add(toPurchaser(d));
        }
        return out;
    }

    /** Adds a postcode of interest. Returns OK / NOT_FOUND / INVALID_POSTCODE / DUPLICATE / LIMIT_REACHED. */
    public AddResult addInterest(String purchaserId, String postcode) {
        if (!ObjectId.isValid(purchaserId)) {
            return AddResult.NOT_FOUND;
        }
        if (!isValidNswPostcode(postcode)) {
            return AddResult.INVALID_POSTCODE;
        }

        Document existing = coll.find(Filters.and(
                Filters.eq(FIELD_ID, new ObjectId(purchaserId)),
                BUYER_FILTER)).first();
        if (existing == null) {
            return AddResult.NOT_FOUND;
        }

        List<String> current = existing.getList(FIELD_POSTCODE_INTEREST, String.class, Collections.emptyList());
        if (current.contains(postcode)) {
            return AddResult.DUPLICATE;
        }
        if (current.size() >= MAX_POSTCODES) {
            return AddResult.LIMIT_REACHED;
        }

        UpdateResult r = coll.updateOne(
                Filters.eq(FIELD_ID, new ObjectId(purchaserId)),
                Updates.addToSet(FIELD_POSTCODE_INTEREST, postcode));
        return r.getModifiedCount() == 1 ? AddResult.OK : AddResult.NOT_FOUND;
    }

    public boolean removeInterest(String purchaserId, String postcode) {
        if (!ObjectId.isValid(purchaserId)) {
            return false;
        }
        UpdateResult r = coll.updateOne(
                Filters.and(Filters.eq(FIELD_ID, new ObjectId(purchaserId)), BUYER_FILTER),
                Updates.pull(FIELD_POSTCODE_INTEREST, postcode));
        return r.getModifiedCount() == 1;
    }

    /** Bulk-insert N synthetic Buyer accounts each with 0..5 random NSW postcodes. */
    public int seedPurchasers(int count) {
        Random rand = new Random();
        List<Document> batch = new ArrayList<>(BATCH_SIZE);
        for (int i = 0; i < count; i++) {
            batch.add(synthesizeBuyer(rand, i));
            if (batch.size() >= BATCH_SIZE) {
                coll.insertMany(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            coll.insertMany(batch);
        }
        return count;
    }

    private static Document synthesizeBuyer(Random rand, int index) {
        int numPostcodes = rand.nextInt(MAX_POSTCODES + 1);
        Set<String> picks = new LinkedHashSet<>();
        int attempts = 0;
        while (picks.size() < numPostcodes && attempts < SEED_MAX_ATTEMPTS) {
            picks.add(String.format("%04d", randomNswPostcode(rand)));
            attempts++;
        }
        String suffix = Long.toHexString(System.nanoTime()) + "-" + index;
        return new Document()
                .append("name", "Synthetic Buyer " + index)
                .append("email", "buyer" + index + "-" + suffix + "@example.com")
                .append(FIELD_ACCOUNT_TYPE, BUYER_TYPE)
                .append(FIELD_POSTCODE_INTEREST, new ArrayList<>(picks));
    }

    private static int randomNswPostcode(Random rand) {
        int[] r = NSW_RANGES[rand.nextInt(NSW_RANGES.length)];
        return r[0] + rand.nextInt(r[1] - r[0] + 1);
    }

    private List<String> sanitizePostcodes(List<String> in) {
        if (in == null) {
            return new ArrayList<>();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String p : in) {
            if (p == null) {
                continue;
            }
            String t = p.trim();
            if (!isValidNswPostcode(t)) {
                return null;
            }
            seen.add(t);
            if (seen.size() > MAX_POSTCODES) {
                return null;
            }
        }
        return new ArrayList<>(seen);
    }

    private Purchaser toPurchaser(Document d) {
        String id = d.getObjectId(FIELD_ID).toHexString();
        List<String> postcodes = d.getList(FIELD_POSTCODE_INTEREST, String.class, Collections.emptyList());
        return new Purchaser(id, d.getString("name"), d.getString("email"), new ArrayList<>(postcodes));
    }

    public enum AddResult {
        OK, NOT_FOUND, INVALID_POSTCODE, LIMIT_REACHED, DUPLICATE
    }
}
