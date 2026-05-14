package listing;

import app.Mongo;
import com.mongodb.client.AggregateIterable;
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

    private static final int MAX_RESULTS = 1000;
    private static final int SEED_SAMPLE = 1000;
    private static final double LISTING_MARKUP = 1.2;
    private static final String FIELD_PID = "pid";
    private static final String FIELD_ID = "_id";
    private static final String FIELD_UPDATED_PRICE = "updated_price";
    private static final String FIELD_DATE = "date";
    private static final String FIELD_IS_DISCOUNTED = "is_discounted";
    private static final String FIELD_DATE_ADDED = "date_added";

    private final MongoCollection<Document> listingsColl;
    private final MongoCollection<Document> pricingColl;
    private final MongoCollection<Document> propertiesColl;

    public ListingDAO() {
        MongoDatabase db = Mongo.database();
        this.listingsColl = db.getCollection("listings");
        this.pricingColl = db.getCollection("property_pricing_updates");
        this.propertiesColl = db.getCollection("properties");
    }

    /** Creates a listing for the given property ObjectId at the specified price. */
    public Optional<String> createListing(String propertyObjId, double price) {
        if (!ObjectId.isValid(propertyObjId)) {
            return Optional.empty();
        }
        ObjectId pid = new ObjectId(propertyObjId);

        if (propertiesColl.find(Filters.eq(FIELD_ID, pid)).first() == null) {
            return Optional.empty();
        }

        Date now = new Date();
        Document listing = new Document()
                .append(FIELD_PID, pid)
                .append(FIELD_IS_DISCOUNTED, false)
                .append(FIELD_DATE_ADDED, now);
        listingsColl.insertOne(listing);
        pricingColl.insertOne(priceDoc(pid, price, now));

        return Optional.of(listing.getObjectId(FIELD_ID).toHexString());
    }

    /** Randomly samples 1000 properties and creates a listing + initial price at +20% for each. */
    public int seedListings() {
        AggregateIterable<Document> sample = propertiesColl.aggregate(
                Arrays.asList(Aggregates.sample(SEED_SAMPLE)));

        List<Document> newListings = new ArrayList<>(SEED_SAMPLE);
        List<Document> newPrices = new ArrayList<>(SEED_SAMPLE);
        List<ObjectId> listedPropIds = new ArrayList<>(SEED_SAMPLE);
        Date now = new Date();

        for (Document prop : sample) {
            ObjectId propId = prop.getObjectId(FIELD_ID);
            Long purchasePrice = prop.getLong("purchase_price");
            if (purchasePrice == null || purchasePrice <= 0) {
                continue;
            }
            double listingPrice = purchasePrice * LISTING_MARKUP;
            newListings.add(new Document()
                    .append(FIELD_PID, propId)
                    .append(FIELD_IS_DISCOUNTED, false)
                    .append(FIELD_DATE_ADDED, now));
            newPrices.add(priceDoc(propId, listingPrice, now));
            listedPropIds.add(propId);
        }

        if (newListings.isEmpty()) {
            return 0;
        }
        listingsColl.insertMany(newListings);
        pricingColl.insertMany(newPrices);

        // Sync `for_sale = true` on the property docs that just got listed so
        // PropertyDAO reads expose the listing state without a separate join.
        propertiesColl.updateMany(
                Filters.in(FIELD_ID, listedPropIds),
                new Document("$set", new Document("for_sale", true)));
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
        if (!ObjectId.isValid(id)) {
            return Optional.empty();
        }
        Document d = listingsColl.find(Filters.eq(FIELD_ID, new ObjectId(id))).first();
        return Optional.ofNullable(d).map(this::toListing);
    }

    /** Returns all price entries for a listing, oldest first. */
    public List<PriceEntry> getPriceHistory(String listingId) {
        if (!ObjectId.isValid(listingId)) {
            return List.of();
        }
        Document listing = listingsColl.find(Filters.eq(FIELD_ID, new ObjectId(listingId))).first();
        if (listing == null) {
            return List.of();
        }

        ObjectId pid = listing.getObjectId(FIELD_PID);
        List<PriceEntry> out = new ArrayList<>();
        for (Document d : pricingColl.find(Filters.eq(FIELD_PID, pid)).sort(Sorts.ascending(FIELD_DATE))) {
            Double price = d.getDouble(FIELD_UPDATED_PRICE);
            Date date = d.getDate(FIELD_DATE);
            if (price != null && date != null) {
                out.add(new PriceEntry(price, date.toString()));
            }
        }
        return out;
    }

    /** Adds a new price update for the property tied to this listing. */
    public boolean addPriceUpdate(String listingId, double newPrice) {
        if (!ObjectId.isValid(listingId)) {
            return false;
        }
        Document listing = listingsColl.find(Filters.eq(FIELD_ID, new ObjectId(listingId))).first();
        if (listing == null) {
            return false;
        }

        ObjectId pid = listing.getObjectId(FIELD_PID);

        // Mark discounted if the new price is lower than the current latest price
        double latest = getLatestPrice(pid);
        if (latest > 0 && newPrice < latest) {
            listingsColl.updateOne(
                    Filters.eq(FIELD_ID, new ObjectId(listingId)),
                    new Document("$set", new Document(FIELD_IS_DISCOUNTED, true)));
        }

        pricingColl.insertOne(priceDoc(pid, newPrice, new Date()));
        return true;
    }

    private static Document priceDoc(ObjectId pid, double price, Date when) {
        return new Document()
                .append(FIELD_PID, pid)
                .append(FIELD_UPDATED_PRICE, price)
                .append(FIELD_DATE, when);
    }

    private double getLatestPrice(ObjectId pid) {
        Document d = pricingColl.find(Filters.eq(FIELD_PID, pid))
                .sort(Sorts.descending(FIELD_DATE))
                .first();
        if (d == null) {
            return 0;
        }
        Double p = d.getDouble(FIELD_UPDATED_PRICE);
        return p == null ? 0 : p;
    }

    private Listing toListing(Document d) {
        String id = d.getObjectId(FIELD_ID).toHexString();
        ObjectId pid = d.getObjectId(FIELD_PID);
        boolean discounted = Boolean.TRUE.equals(d.getBoolean(FIELD_IS_DISCOUNTED));
        Date dateAdded = d.getDate(FIELD_DATE_ADDED);
        double latest = pid != null ? getLatestPrice(pid) : 0;
        return new Listing(
                id,
                pid != null ? pid.toHexString() : null,
                discounted,
                dateAdded != null ? dateAdded.toString() : null,
                latest);
    }
}
