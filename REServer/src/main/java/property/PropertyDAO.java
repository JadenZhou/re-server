package property;

import app.Mongo;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PropertyDAO {

    private static final String COLLECTION_NAME = "properties";
    private static final String FIELD_PROPERTY_ID = "property_id";
    private static final String FIELD_POST_CODE = "post_code";
    private static final String FIELD_PURCHASE_PRICE = "purchase_price";
    private static final String FIELD_CONTRACT_DATE = "contract_date";

    // Cap unbounded queries so a single request can't try to ship millions of rows.
    private static final int MAX_RESULTS = 1000;

    private final MongoCollection<Document> coll;

    public PropertyDAO() {
        this.coll = Mongo.database().getCollection(COLLECTION_NAME);
    }

    public boolean newProperty(Property property) {
        Document d = new Document()
                .append(FIELD_PROPERTY_ID, parseLongOrNull(property.propertyID))
                .append(FIELD_POST_CODE, property.postcode)
                .append(FIELD_PURCHASE_PRICE, parseLongOrNull(property.propertyPrice))
                .append("address", property.address)
                .append("council_name", property.councilName)
                .append("property_type", property.propertyType)
                .append(FIELD_CONTRACT_DATE, property.contractDate)
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
        if (id == null) {
            return Optional.empty();
        }
        Document d = coll.find(Filters.eq(FIELD_PROPERTY_ID, id))
                .sort(Sorts.descending(FIELD_CONTRACT_DATE))
                .first();
        return Optional.ofNullable(d).map(PropertyDAO::toProperty);
    }

    public List<Property> getPropertiesByPostCode(String postCode) {
        return collect(coll.find(Filters.eq(FIELD_POST_CODE, postCode)).limit(MAX_RESULTS));
    }

    public List<Property> getAllProperties() {
        return collect(coll.find().limit(MAX_RESULTS));
    }

    public List<Property> getPropertiesByPriceRange(long minPrice, long maxPrice) {
        Bson filter = Filters.and(
                Filters.gte(FIELD_PURCHASE_PRICE, minPrice),
                Filters.lte(FIELD_PURCHASE_PRICE, maxPrice));
        return collect(coll.find(filter).limit(MAX_RESULTS));
    }

    public List<String> getAllPropertyPrices() {
        List<String> out = new ArrayList<>();
        for (Document d : coll.find().limit(MAX_RESULTS)) {
            Long p = d.getLong(FIELD_PURCHASE_PRICE);
            if (p != null) {
                out.add(p.toString());
            }
        }
        return out;
    }

    private static List<Property> collect(FindIterable<Document> docs) {
        List<Property> out = new ArrayList<>();
        for (Document d : docs) {
            out.add(toProperty(d));
        }
        return out;
    }

    private static Property toProperty(Document d) {
        Long pid = d.getLong(FIELD_PROPERTY_ID);
        Long price = d.getLong(FIELD_PURCHASE_PRICE);
        Property p = new Property(
                pid == null ? null : pid.toString(),
                d.getString(FIELD_POST_CODE),
                price == null ? null : price.toString());
        p.address = d.getString("address");
        p.councilName = d.getString("council_name");
        p.propertyType = d.getString("property_type");
        p.contractDate = d.getString(FIELD_CONTRACT_DATE);
        Boolean forSale = d.getBoolean("for_sale");
        p.forSale = forSale != null && forSale;
        return p;
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
