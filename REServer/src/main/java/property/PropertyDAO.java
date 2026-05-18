package property;

import app.Mongo;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PropertyDAO {

    // Cap unbounded queries so a single request can't try to ship millions of rows.
    private static final int MAX_RESULTS = 1000;

    private final MongoCollection<Document> coll;

    public PropertyDAO() {
        this.coll = Mongo.db().getCollection("properties");
    }

    public boolean newProperty(Property property) {
        Document d = new Document()
                .append("property_id", parseLongOrNull(property.propertyID))
                .append("post_code", property.postcode)
                .append("purchase_price", parseLongOrNull(property.propertyPrice))
                .append("address", property.address)
                .append("council_name", property.councilName)
                .append("property_type", property.propertyType)
                .append("contract_date", property.contractDate)
                .append("for_sale", property.forSale);
        coll.insertOne(d);
        return true;
    }

    // property_id is NOT unique in the source data — each row is a sale. The
    // API returns the most recent sale by contract_date. contract_date is
    // stored as an ISO-8601 String (YYYY-MM-DD), which sorts correctly under
    // lexicographic ordering — equivalent to chronological order for that format.
    public Optional<Property> getPropertyById(String propertyID) {
        Long id = parseLongOrNull(propertyID);
        if (id == null) return Optional.empty();
        Document d = coll.find(Filters.eq("property_id", id))
                .sort(Sorts.descending("contract_date"))
                .first();
        return Optional.ofNullable(d).map(PropertyDAO::toProperty);
    }

    /**
     * Atomically bumps view_count on every sale row sharing this property_id,
     * so any subsequent read sees the same total. Returns the new count, or 0
     * if the property doesn't exist (no rows match) / the id is malformed.
     */
    public long incrementViewCount(String propertyID) {
        Long id = parseLongOrNull(propertyID);
        if (id == null) return 0;
        coll.updateMany(Filters.eq("property_id", id), Updates.inc("view_count", 1L));
        Document d = coll.find(Filters.eq("property_id", id)).first();
        if (d == null) return 0;
        Long n = d.getLong("view_count");
        return n == null ? 0 : n;
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
        Property p = new Property(
                pid == null ? null : pid.toString(),
                d.getString("post_code"),
                price == null ? null : price.toString());
        p.address = d.getString("address");
        p.councilName = d.getString("council_name");
        p.propertyType = d.getString("property_type");
        p.contractDate = d.getString("contract_date");
        Boolean forSale = d.getBoolean("for_sale");
        p.forSale = forSale != null && forSale;
        Long views = d.getLong("view_count");
        p.viewCount = views == null ? 0 : views;
        return p;
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
