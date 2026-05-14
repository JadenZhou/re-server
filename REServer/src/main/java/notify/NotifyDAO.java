package notify;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
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

    private static final String DB_NAME = "nsw_property_data";

    private final MongoCollection<Document> listings;
    private final MongoCollection<Document> pricing;
    private final MongoCollection<Document> properties;
    private final MongoCollection<Document> accounts;

    public NotifyDAO() {
        String uri = System.getenv("MONGO_URI");
        if (uri == null || uri.isEmpty()) {
            throw new IllegalStateException("MONGO_URI env var is required");
        }
        MongoClient client = MongoClients.create(uri);
        MongoDatabase db = client.getDatabase(DB_NAME);
        this.listings = db.getCollection("listings");
        this.pricing = db.getCollection("property_pricing_updates");
        this.properties = db.getCollection("properties");
        this.accounts = db.getCollection("accounts");
    }

    /** Postcode → list of for-sale properties in that postcode. */
    public Map<String, List<PropertyForSale>> buildPostcodeIndex() {
        // 1. All active listings → set of property ObjectIds for sale.
        Set<ObjectId> forSalePids = new HashSet<>();
        for (Document l : listings.find()) {
            ObjectId pid = l.getObjectId("pid");
            if (pid != null) forSalePids.add(pid);
        }
        if (forSalePids.isEmpty()) return Collections.emptyMap();

        // 2. Latest price per pid, in one aggregation pass.
        //    sort by date desc, then group taking the first updated_price.
        Map<ObjectId, Double> latestPriceByPid = new HashMap<>();
        for (Document d : pricing.aggregate(Arrays.asList(
                Aggregates.match(Filters.in("pid", forSalePids)),
                Aggregates.sort(Sorts.descending("date")),
                Aggregates.group("$pid",
                        com.mongodb.client.model.Accumulators.first("price", "$updated_price"))))) {
            ObjectId pid = d.getObjectId("_id");
            Double price = d.getDouble("price");
            if (pid != null && price != null) latestPriceByPid.put(pid, price);
        }

        // 3. Property metadata (postcode, property_id) for every for-sale pid.
        Map<String, List<PropertyForSale>> index = new HashMap<>();
        for (Document p : properties.find(Filters.in("_id", forSalePids))) {
            ObjectId pid = p.getObjectId("_id");
            String postcode = p.getString("post_code");
            Long propertyId = p.getLong("property_id");
            Double price = latestPriceByPid.get(pid);
            if (postcode == null || propertyId == null || price == null) continue;
            index.computeIfAbsent(postcode, k -> new ArrayList<>())
                    .add(new PropertyForSale(propertyId, price, postcode));
        }
        return index;
    }

    /** All Buyer accounts with their (possibly empty) postcode list. */
    public List<PurchaserSummary> fetchPurchasers() {
        List<PurchaserSummary> out = new ArrayList<>();
        for (Document d : accounts.find(Filters.eq("account_type", "Buyer"))) {
            ObjectId id = d.getObjectId("_id");
            List<String> postcodes = d.getList("postcode_interest", String.class, Collections.emptyList());
            out.add(new PurchaserSummary(
                    id == null ? null : id.toHexString(),
                    d.getString("name"),
                    d.getString("email"),
                    new ArrayList<>(postcodes)));
        }
        return out;
    }
}
