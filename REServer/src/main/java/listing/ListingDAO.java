package listing;

import com.mongodb.client.AggregateIterable;
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
import java.util.Date;
import java.util.List;
import java.util.Optional;

public class ListingDAO {

    private static final String DB_NAME = "nsw_property_data";
    private static final int MAX_RESULTS = 1000;

    private final MongoCollection<Document> listingsColl;
    private final MongoCollection<Document> pricingColl;
    private final MongoCollection<Document> propertiesColl;

    public ListingDAO() {
        String uri = System.getenv("MONGO_URI");
        if (uri == null || uri.isEmpty()) {
            throw new IllegalStateException("MONGO_URI env var is required");
        }
        MongoClient client = MongoClients.create(uri);
        MongoDatabase db = client.getDatabase(DB_NAME);
        this.listingsColl = db.getCollection("listings");
        this.pricingColl = db.getCollection("property_pricing_updates");
        this.propertiesColl = db.getCollection("properties");
    }

    /** Randomly samples 1000 properties and creates a listing + initial price at +20% for each. */
    public int seedListings() {
        AggregateIterable<Document> sample = propertiesColl.aggregate(
                Arrays.asList(Aggregates.sample(1000)));

        List<Document> newListings = new ArrayList<>();
        List<Document> newPrices = new ArrayList<>();
        Date now = new Date();

        for (Document prop : sample) {
            ObjectId propId = prop.getObjectId("_id");
            Long purchasePrice = prop.getLong("purchase_price");
            if (purchasePrice == null || purchasePrice <= 0) continue;

            double listingPrice = purchasePrice * 1.2;

            newListings.add(new Document()
                    .append("pid", propId)
                    .append("is_discounted", false)
                    .append("date_added", now));

            newPrices.add(new Document()
                    .append("pid", propId)
                    .append("updated_price", listingPrice)
                    .append("date", now));
        }

        if (newListings.isEmpty()) return 0;
        listingsColl.insertMany(newListings);
        pricingColl.insertMany(newPrices);
        return newListings.size();
    }

    public List<Listing> getAllListings() {
        List<Listing> out = new ArrayList<>();
        for (Document d : listingsColl.find().limit(MAX_RESULTS)) {
            out.add(toListing(d));
        }
        return out;
    }

    public Optional<Listing> getListingById(String id) {
        if (!ObjectId.isValid(id)) return Optional.empty();
        Document d = listingsColl.find(Filters.eq("_id", new ObjectId(id))).first();
        return Optional.ofNullable(d).map(this::toListing);
    }

    /** Returns all price entries for a listing, oldest first. */
    public List<PriceEntry> getPriceHistory(String listingId) {
        if (!ObjectId.isValid(listingId)) return List.of();
        Document listing = listingsColl.find(Filters.eq("_id", new ObjectId(listingId))).first();
        if (listing == null) return List.of();

        ObjectId pid = listing.getObjectId("pid");
        List<PriceEntry> out = new ArrayList<>();
        for (Document d : pricingColl.find(Filters.eq("pid", pid)).sort(Sorts.ascending("date"))) {
            Double price = d.getDouble("updated_price");
            Date date = d.getDate("date");
            if (price != null && date != null) {
                out.add(new PriceEntry(price, date.toString()));
            }
        }
        return out;
    }

    /** Adds a new price update for the property tied to this listing. */
    public boolean addPriceUpdate(String listingId, double newPrice) {
        if (!ObjectId.isValid(listingId)) return false;
        Document listing = listingsColl.find(Filters.eq("_id", new ObjectId(listingId))).first();
        if (listing == null) return false;

        ObjectId pid = listing.getObjectId("pid");

        // Mark discounted if the new price is lower than the current latest price
        double latest = getLatestPrice(pid);
        if (latest > 0 && newPrice < latest) {
            listingsColl.updateOne(
                    Filters.eq("_id", new ObjectId(listingId)),
                    new Document("$set", new Document("is_discounted", true)));
        }

        pricingColl.insertOne(new Document()
                .append("pid", pid)
                .append("updated_price", newPrice)
                .append("date", new Date()));
        return true;
    }

    private double getLatestPrice(ObjectId pid) {
        Document d = pricingColl.find(Filters.eq("pid", pid))
                .sort(Sorts.descending("date"))
                .first();
        if (d == null) return 0;
        Double p = d.getDouble("updated_price");
        return p == null ? 0 : p;
    }

    private Listing toListing(Document d) {
        String id = d.getObjectId("_id").toHexString();
        ObjectId pid = d.getObjectId("pid");
        boolean discounted = Boolean.TRUE.equals(d.getBoolean("is_discounted"));
        Date dateAdded = d.getDate("date_added");
        double latest = pid != null ? getLatestPrice(pid) : 0;
        return new Listing(
                id,
                pid != null ? pid.toHexString() : null,
                discounted,
                dateAdded != null ? dateAdded.toString() : null,
                latest);
    }
}
