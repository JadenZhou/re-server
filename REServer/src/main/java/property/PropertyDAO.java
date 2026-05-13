package property;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PropertyDAO {

    private static final String DB_NAME = "nsw_property_data";
    private static final String COLLECTION_NAME = "properties";

    // Cap unbounded queries so a single request can't try to ship millions of rows.
    private static final int MAX_RESULTS = 1000;

    private final MongoCollection<Document> coll;

    public PropertyDAO() {
        String uri = System.getenv("MONGO_URI");
        if (uri == null || uri.isEmpty()) {
            throw new IllegalStateException("MONGO_URI env var is required");
        }
        MongoClient client = MongoClients.create(uri);
        MongoDatabase db = client.getDatabase(DB_NAME);
        this.coll = db.getCollection(COLLECTION_NAME);
    }

    public boolean newProperty(Property property) {
        Document d = new Document()
                .append("property_id", parseLongOrNull(property.propertyID))
                .append("post_code", property.postcode)
                .append("purchase_price", parseLongOrNull(property.propertyPrice));
        coll.insertOne(d);
        return true;
    }

    // Task 4.5 "hack": property_id is NOT unique in the source data — each row is a sale.
    // The API expects a single Property; we return the most recent sale by contract_date.
    public Optional<Property> getPropertyById(String propertyID) {
        Long id = parseLongOrNull(propertyID);
        if (id == null) return Optional.empty();
        Document d = coll.find(Filters.eq("property_id", id))
                .sort(Sorts.descending("contract_date"))
                .first();
        return Optional.ofNullable(d).map(PropertyDAO::toProperty);
    }

    public List<Property> getPropertiesByPostCode(String postCode) {
        return collect(coll.find(Filters.eq("post_code", postCode)).limit(MAX_RESULTS));
    }

    public List<Property> getAllProperties() {
        return collect(coll.find().limit(MAX_RESULTS));
    }

    public List<Property> getPropertiesByPriceRange(long minPrice, long maxPrice) {
        Bson filter = Filters.and(
                Filters.gte("purchase_price", minPrice),
                Filters.lte("purchase_price", maxPrice));
        return collect(coll.find(filter).limit(MAX_RESULTS));
    }

    public List<String> getAllPropertyPrices() {
        List<String> out = new ArrayList<>();
        for (Document d : coll.find().limit(MAX_RESULTS)) {
            Long p = d.getLong("purchase_price");
            if (p != null) out.add(p.toString());
        }
        return out;
    }

    private static List<Property> collect(FindIterable<Document> docs) {
        List<Property> out = new ArrayList<>();
        for (Document d : docs) out.add(toProperty(d));
        return out;
    }

    private static Property toProperty(Document d) {
        Long pid = d.getLong("property_id");
        Long price = d.getLong("purchase_price");
        return new Property(
                pid == null ? null : pid.toString(),
                d.getString("post_code"),
                price == null ? null : price.toString());
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
