package listing;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

public class ListingDAO {

    private static final String DB_NAME = "nsw_property_data";
    private static final int MAX_RESULTS = 1000;

    private final MongoCollection<Document> coll;

    public ListingDAO() {
        String uri = System.getenv("MONGO_URI");
        if (uri == null || uri.isEmpty()) throw new IllegalStateException("MONGO_URI env var is required");
        MongoClient client = MongoClients.create(uri);
        MongoDatabase db = client.getDatabase(DB_NAME);
        this.coll = db.getCollection("listings");
    }

    public ObjectId insertListing(ObjectId pid, Date date) {
        Document d = new Document()
                .append("pid", pid)
                .append("is_discounted", false)
                .append("date_added", date);
        coll.insertOne(d);
        return d.getObjectId("_id");
    }

    public void insertMany(List<Document> docs) {
        if (!docs.isEmpty()) coll.insertMany(docs);
    }

    public List<Document> findAll() {
        List<Document> out = new ArrayList<>();
        for (Document d : coll.find().limit(MAX_RESULTS)) out.add(d);
        return out;
    }

    public Optional<Document> findById(String id) {
        if (!ObjectId.isValid(id)) return Optional.empty();
        return Optional.ofNullable(coll.find(Filters.eq("_id", new ObjectId(id))).first());
    }

    public void setDiscounted(ObjectId listingId, boolean discounted) {
        coll.updateOne(Filters.eq("_id", listingId),
                new Document("$set", new Document("is_discounted", discounted)));
    }
}
