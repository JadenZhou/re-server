package property;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

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
    Document d = fromPropertyToDocument(property);
    coll.insertOne(d);
    return true;
  }

  // property_id is NOT unique in the source data — each row is a sale. The
  // API returns the most recent sale by contract_date. contract_date is
  // stored as an ISO-8601 String (YYYY-MM-DD), which sorts correctly under
  // lexicographic ordering — equivalent to chronological order for that format.


  public Optional<Property> getPropertyById(String propertyID) {
    if (propertyID == null || propertyID.isEmpty()) return Optional.empty();
    Document d = coll.find(Filters.eq("_id", new ObjectId(propertyID))).first();
    Optional<Property> property = Optional.ofNullable(d).map(PropertyDAO::toProperty);
    if (property.isEmpty()) {
      return Optional.empty();
    }
    auditProperty(property.get());
    return property;
  }

  public List<Property> getPropertiesByPostCode(String postCode) {
    List<Property> properties = collect(coll.find(Filters.eq("post_code", postCode)).limit(MAX_RESULTS));
    properties.forEach(this::auditProperty);
    return properties;
  }

  public List<Property> getAllProperties() {
    List<Property> properties = collect(coll.find().limit(MAX_RESULTS));
    properties.forEach(this::auditProperty); // says get all, but only gets 1000
    return properties;
  }

  public List<Property> getPropertiesByPriceRange(long minPrice, long maxPrice) {
    Bson filter = Filters.and(
            Filters.gte("purchase_price", minPrice),
            Filters.lte("purchase_price", maxPrice));
    List<Property> properties = collect(coll.find(filter).limit(MAX_RESULTS));
    properties.forEach(this::auditProperty);
    return properties;
  }

  public List<String> getAllPropertyPrices() {
    List<String> out = new ArrayList<>();
    for (Document d : coll.find().limit(MAX_RESULTS)) {
      Long p = d.getLong("purchase_price");
      if (p != null) out.add(p.toString());
    }
    return out;
  }

  private static Document fromPropertyToDocument(Property property) {
    return new Document()
            .append("_id", property._id)
            .append("post_code", property.postcode)
            .append("purchase_price", parseLongOrNull(property.propertyPrice))
            .append("address", property.address)
            .append("council_name", property.councilName)
            .append("property_type", property.propertyType)
            .append("contract_date", property.contractDate)
            .append("for_sale", property.forSale);
  }

  private static List<Property> collect(FindIterable<Document> docs) {
    List<Property> out = new ArrayList<>();
    for (Document d : docs) out.add(toProperty(d));
    return out;
  }

  private static Property toProperty(Document d) {
    ObjectId pid = d.getObjectId("_id");
    Long price = d.getLong("purchase_price");
    Property p = new Property(
            pid,
            d.getString("post_code"),
            price == null ? null : price.toString());
    p.address = d.getString("address");
    p.councilName = d.getString("council_name");
    p.propertyType = d.getString("property_type");
    p.contractDate = d.getString("contract_date");
    Boolean forSale = d.getBoolean("for_sale");
    p.forSale = forSale != null && forSale;
    Long searchCount = d.getLong("search_count");
    if (searchCount != null) {
      p.searchCount = searchCount;
    } else {
      p.searchCount = 0;
    }
    return p;
  }

  private static Long parseLongOrNull(String s) {
    if (s == null || s.isEmpty()) return null;
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private void auditProperty(ObjectId pid) {
    Bson update = new Document("$inc", new Document("search_count", 1L));
    coll.updateOne(Filters.eq("_id", pid), update);
  }

  public void auditProperty(Property property) {
    this.auditProperty(property._id);
  }
}
