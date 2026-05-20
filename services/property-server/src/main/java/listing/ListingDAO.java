package listing;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import db.Db;
import events.EventPublisher;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Listings + per-property price history. Three collections used:
 *   listings                 _id (ObjectId) + pid (ObjectId -> properties._id) + is_discounted + date_added
 *   property_pricing_updates pid + updated_price + date
 *   properties               (read-only here; flips for_sale on seed)
 */
public class ListingDAO {

    private static final int MAX_RESULTS = 1000;

    private final MongoCollection<Document> listings = Db.database().getCollection("listings");
    private final MongoCollection<Document> pricing = Db.database().getCollection("property_pricing_updates");
    private final MongoCollection<Document> properties = Db.database().getCollection("properties");
    private final EventPublisher events = EventPublisher.get();

    public Optional<String> createListing(String propertyIdHex, double price) {
        if (!ObjectId.isValid(propertyIdHex)) return Optional.empty();
        ObjectId pid = new ObjectId(propertyIdHex);
        if (properties.find(Filters.eq("_id", pid)).first() == null) return Optional.empty();

        Date now = new Date();
        Document listing = new Document()
                .append("pid", pid)
                .append("is_discounted", false)
                .append("date_added", now);
        listings.insertOne(listing);

        pricing.insertOne(new Document()
                .append("pid", pid)
                .append("updated_price", price)
                .append("date", now));

        properties.updateOne(Filters.eq("_id", pid),
                new Document("$set", new Document("for_sale", true)));

        String listingId = listing.getObjectId("_id").toHexString();
        emitListed(pid, listingId, price);
        return Optional.of(listingId);
    }

    /** Sample 1000 properties + create listings + initial price at +20%. */
    public int seedListings() {
        AggregateIterable<Document> sample = properties.aggregate(
                Arrays.asList(Aggregates.sample(1000)));
        List<Document> newListings = new ArrayList<>();
        List<Document> newPrices = new ArrayList<>();
        List<ObjectId> pids = new ArrayList<>();
        List<Double> seedPrices = new ArrayList<>();
        Date now = new Date();

        for (Document prop : sample) {
            ObjectId pid = prop.getObjectId("_id");
            Long purchasePrice = prop.getLong("purchase_price");
            if (purchasePrice == null || purchasePrice <= 0) continue;
            double listingPrice = purchasePrice * 1.2;

            newListings.add(new Document()
                    .append("pid", pid)
                    .append("is_discounted", false)
                    .append("date_added", now));
            newPrices.add(new Document()
                    .append("pid", pid)
                    .append("updated_price", listingPrice)
                    .append("date", now));
            pids.add(pid);
            seedPrices.add(listingPrice);
        }
        if (newListings.isEmpty()) return 0;
        listings.insertMany(newListings);
        pricing.insertMany(newPrices);
        properties.updateMany(Filters.in("_id", pids),
                new Document("$set", new Document("for_sale", true)));
        // Emit one property.listed event per seeded listing so notification-service can fan out.
        for (int i = 0; i < newListings.size(); i++) {
            emitListed(pids.get(i), newListings.get(i).getObjectId("_id").toHexString(), seedPrices.get(i));
        }
        return newListings.size();
    }

    public List<Listing> getAllListings() {
        List<Listing> out = new ArrayList<>();
        for (Document d : listings.find().limit(MAX_RESULTS)) out.add(toListing(d));
        return out;
    }

    public Optional<Listing> getListingById(String id) {
        if (!ObjectId.isValid(id)) return Optional.empty();
        Document d = listings.find(Filters.eq("_id", new ObjectId(id))).first();
        return Optional.ofNullable(d).map(this::toListing);
    }

    public List<PriceEntry> getPriceHistory(String listingId) {
        if (!ObjectId.isValid(listingId)) return List.of();
        Document listing = listings.find(Filters.eq("_id", new ObjectId(listingId))).first();
        if (listing == null) return List.of();
        ObjectId pid = listing.getObjectId("pid");
        List<PriceEntry> out = new ArrayList<>();
        for (Document d : pricing.find(Filters.eq("pid", pid)).sort(Sorts.ascending("date"))) {
            Double price = d.getDouble("updated_price");
            Date date = d.getDate("date");
            if (price != null && date != null) out.add(new PriceEntry(price, date.toString()));
        }
        return out;
    }

    public boolean addPriceUpdate(String listingId, double newPrice) {
        if (!ObjectId.isValid(listingId)) return false;
        Document listing = listings.find(Filters.eq("_id", new ObjectId(listingId))).first();
        if (listing == null) return false;
        ObjectId pid = listing.getObjectId("pid");

        double latest = getLatestPrice(pid);
        if (latest > 0 && newPrice < latest) {
            listings.updateOne(Filters.eq("_id", new ObjectId(listingId)),
                    new Document("$set", new Document("is_discounted", true)));
        }
        pricing.insertOne(new Document()
                .append("pid", pid)
                .append("updated_price", newPrice)
                .append("date", new Date()));
        emitPriceChanged(pid, listingId, latest, newPrice);
        return true;
    }

    // ── event emission ────────────────────────────────────────────────────────

    /** Emits property.listed with the postcode looked up from the property doc. */
    private void emitListed(ObjectId pid, String listingId, double price) {
        Document p = properties.find(Filters.eq("_id", pid)).first();
        if (p == null) return;
        String postcode = p.getString("post_code");
        String json = String.format(
                "{\"type\":\"listed\",\"property_id\":\"%s\",\"listing_id\":\"%s\",\"postcode\":\"%s\",\"price\":%.2f,\"ts\":%d}",
                pid.toHexString(), listingId, postcode == null ? "" : postcode, price, System.currentTimeMillis());
        events.publish("property.listed", json);
    }

    private void emitPriceChanged(ObjectId pid, String listingId, double oldPrice, double newPrice) {
        Document p = properties.find(Filters.eq("_id", pid)).first();
        if (p == null) return;
        String postcode = p.getString("post_code");
        String json = String.format(
                "{\"type\":\"price-changed\",\"property_id\":\"%s\",\"listing_id\":\"%s\",\"postcode\":\"%s\",\"old_price\":%.2f,\"new_price\":%.2f,\"ts\":%d}",
                pid.toHexString(), listingId, postcode == null ? "" : postcode, oldPrice, newPrice, System.currentTimeMillis());
        events.publish("property.price-changed", json);
    }

    private double getLatestPrice(ObjectId pid) {
        Document d = pricing.find(Filters.eq("pid", pid))
                .sort(Sorts.descending("date"))
                .first();
        if (d == null) return 0;
        Double p = d.getDouble("updated_price");
        return p == null ? 0 : p;
    }

    private Listing toListing(Document d) {
        ObjectId id = d.getObjectId("_id");
        ObjectId pid = d.getObjectId("pid");
        boolean discounted = Boolean.TRUE.equals(d.getBoolean("is_discounted"));
        Date dateAdded = d.getDate("date_added");
        double latest = pid != null ? getLatestPrice(pid) : 0;
        return new Listing(
                id.toHexString(),
                pid != null ? pid.toHexString() : null,
                discounted,
                dateAdded != null ? dateAdded.toString() : null,
                latest);
    }
}
