package notify;

import app.Mongo;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the inputs NotifyService needs by pulling from the existing
 * Mongo collections (`listings`, `property_pricing_updates`, `properties`,
 * `accounts`). Kept separate from NotifyService so the service can be
 * unit-tested without Mongo.
 */
public class NotifyDAO {

    private static final String FIELD_PID = "pid";
    private static final String FIELD_ID = "_id";
    private static final String FIELD_UPDATED_PRICE = "updated_price";
    private static final String FIELD_DATE = "date";
    private static final String FIELD_POST_CODE = "post_code";
    private static final String FIELD_PROPERTY_ID = "property_id";
    private static final String BUYER_TYPE = "Buyer";

    private final MongoCollection<Document> listings;
    private final MongoCollection<Document> pricing;
    private final MongoCollection<Document> properties;
    private final MongoCollection<Document> accounts;

    public NotifyDAO() {
        MongoDatabase db = Mongo.database();
        this.listings = db.getCollection("listings");
        this.pricing = db.getCollection("property_pricing_updates");
        this.properties = db.getCollection("properties");
        this.accounts = db.getCollection("accounts");
    }

    /** Postcode → list of for-sale properties in that postcode. */
    public Map<String, List<PropertyForSale>> buildPostcodeIndex() {
        Set<ObjectId> forSalePids = forSalePropertyIds();
        if (forSalePids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<ObjectId, Double> latestPriceByPid = latestPricePerProperty(forSalePids);
        return assemblePostcodeIndex(forSalePids, latestPriceByPid);
    }

    /** All Buyer accounts with their (possibly empty) postcode list. */
    public List<PurchaserSummary> fetchPurchasers() {
        List<PurchaserSummary> out = new ArrayList<>();
        for (Document d : accounts.find(Filters.eq("account_type", BUYER_TYPE))) {
            out.add(toPurchaserSummary(d));
        }
        return out;
    }

    private Set<ObjectId> forSalePropertyIds() {
        Set<ObjectId> ids = new HashSet<>();
        for (Document l : listings.find()) {
            ObjectId pid = l.getObjectId(FIELD_PID);
            if (pid != null) {
                ids.add(pid);
            }
        }
        return ids;
    }

    private Map<ObjectId, Double> latestPricePerProperty(Set<ObjectId> forSalePids) {
        Map<ObjectId, Double> latest = new HashMap<>();
        Iterable<Document> agg = pricing.aggregate(Arrays.asList(
                Aggregates.match(Filters.in(FIELD_PID, forSalePids)),
                Aggregates.sort(Sorts.descending(FIELD_DATE)),
                Aggregates.group("$" + FIELD_PID,
                        Accumulators.first("price", "$" + FIELD_UPDATED_PRICE))));
        for (Document d : agg) {
            ObjectId pid = d.getObjectId(FIELD_ID);
            Double price = d.getDouble("price");
            if (pid != null && price != null) {
                latest.put(pid, price);
            }
        }
        return latest;
    }

    private Map<String, List<PropertyForSale>> assemblePostcodeIndex(
            Set<ObjectId> forSalePids, Map<ObjectId, Double> latestPriceByPid) {
        Map<String, List<PropertyForSale>> index = new HashMap<>();
        for (Document p : properties.find(Filters.in(FIELD_ID, forSalePids))) {
            ObjectId pid = p.getObjectId(FIELD_ID);
            String postcode = p.getString(FIELD_POST_CODE);
            Long propertyId = p.getLong(FIELD_PROPERTY_ID);
            Double price = latestPriceByPid.get(pid);
            if (postcode == null || propertyId == null || price == null) {
                continue;
            }
            index.computeIfAbsent(postcode, k -> new ArrayList<>())
                    .add(new PropertyForSale(propertyId, price, postcode));
        }
        return index;
    }

    private static PurchaserSummary toPurchaserSummary(Document d) {
        ObjectId id = d.getObjectId(FIELD_ID);
        List<String> postcodes = d.getList("postcode_interest", String.class, Collections.emptyList());
        return new PurchaserSummary(
                id == null ? null : id.toHexString(),
                d.getString("name"),
                d.getString("email"),
                new ArrayList<>(postcodes));
    }
}
