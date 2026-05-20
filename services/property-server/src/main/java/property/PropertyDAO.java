package property;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import db.Db;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure CRUD over the `properties` collection. The API uses Mongo's ObjectId
 * hex strings as the propertyID (24-char hex).
 *
 * No counter side-effects — analytics is a separate service.
 */
public class PropertyDAO {

    private static final int MAX_RESULTS = 1000;

    private final MongoCollection<Document> coll = Db.database().getCollection("properties");

    public boolean newProperty(Property p) {
        if (p == null || p.postcode == null) return false;
        Document d = new Document()
                .append("property_id", parseLongOrNull(p.propertyID))
                .append("post_code", p.postcode)
                .append("purchase_price", parseLongOrNull(p.propertyPrice))
                .append("address", p.address)
                .append("council_name", p.councilName)
                .append("property_type", p.propertyType)
                .append("contract_date", p.contractDate)
                .append("for_sale", p.forSale);
        coll.insertOne(d);
        // Echo the assigned _id back as the client-facing id.
        p.propertyID = d.getObjectId("_id").toHexString();
        return true;
    }

    public Optional<Property> getPropertyById(String id) {
        if (!ObjectId.isValid(id)) return Optional.empty();
        Document d = coll.find(Filters.eq("_id", new ObjectId(id))).first();
        return Optional.ofNullable(d).map(PropertyDAO::toProperty);
    }

    public List<Property> getPropertiesByPostCode(String postCode) {
        List<Property> out = new ArrayList<>();
        for (Document d : coll.find(Filters.eq("post_code", postCode)).limit(MAX_RESULTS)) {
            out.add(toProperty(d));
        }
        return out;
    }

    public List<Property> getAllProperties() {
        List<Property> out = new ArrayList<>();
        for (Document d : coll.find().limit(MAX_RESULTS)) out.add(toProperty(d));
        return out;
    }

    public List<Property> getPropertiesByPriceRange(long minPrice, long maxPrice) {
        Bson filter = Filters.and(
                Filters.gte("purchase_price", minPrice),
                Filters.lte("purchase_price", maxPrice));
        List<Property> out = new ArrayList<>();
        for (Document d : coll.find(filter).limit(MAX_RESULTS)) out.add(toProperty(d));
        return out;
    }

    /**
     * Cheap path used by the notifier: every for-sale property with its
     * latest listing price. Latest price comes from `property_pricing_updates`
     * via the same aggregation the monolith used.
     */
    public List<ForSaleRow> getForSaleWithLatestPrice() {
        // 1. for-sale properties → pid set + postcode lookup
        List<ObjectId> pids = new ArrayList<>();
        java.util.Map<ObjectId, Document> propByPid = new java.util.HashMap<>();
        for (Document p : coll.find(Filters.eq("for_sale", true)).limit(MAX_RESULTS * 10)) {
            ObjectId pid = p.getObjectId("_id");
            pids.add(pid);
            propByPid.put(pid, p);
        }
        if (pids.isEmpty()) return List.of();

        // 2. latest price per pid via one aggregation
        MongoCollection<Document> pricing = Db.database().getCollection("property_pricing_updates");
        java.util.Map<ObjectId, Double> latest = new java.util.HashMap<>();
        for (Document d : pricing.aggregate(java.util.Arrays.asList(
                com.mongodb.client.model.Aggregates.match(Filters.in("pid", pids)),
                com.mongodb.client.model.Aggregates.sort(Sorts.descending("date")),
                com.mongodb.client.model.Aggregates.group("$pid",
                        com.mongodb.client.model.Accumulators.first("price", "$updated_price"))))) {
            ObjectId pid = d.getObjectId("_id");
            Double price = d.getDouble("price");
            if (pid != null && price != null) latest.put(pid, price);
        }

        // 3. emit one row per for-sale pid that has a price
        List<ForSaleRow> out = new ArrayList<>();
        for (ObjectId pid : pids) {
            Double price = latest.get(pid);
            if (price == null) continue;
            Document p = propByPid.get(pid);
            String postcode = p.getString("post_code");
            Long nswPropertyId = p.getLong("property_id");
            out.add(new ForSaleRow(pid.toHexString(), postcode, nswPropertyId, price));
        }
        return out;
    }

    public record ForSaleRow(String propertyId, String postcode, Long nswPropertyId, double latestPrice) {}

    private static Property toProperty(Document d) {
        ObjectId id = d.getObjectId("_id");
        Long price = d.getLong("purchase_price");
        Property p = new Property(
                id == null ? null : id.toHexString(),
                d.getString("post_code"),
                price == null ? null : price.toString());
        p.address = d.getString("address");
        p.councilName = d.getString("council_name");
        p.propertyType = d.getString("property_type");
        p.contractDate = d.getString("contract_date");
        Boolean forSale = d.getBoolean("for_sale");
        p.forSale = forSale != null && forSale;
        return p;
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
