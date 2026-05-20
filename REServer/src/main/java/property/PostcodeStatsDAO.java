package property;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;

public class PostcodeStatsDAO {

    private static final String DB_NAME = "nsw_property_data";

    private final MongoCollection<Document> coll;

    public PostcodeStatsDAO() {
        String uri = System.getenv("MONGO_URI");
        if (uri == null || uri.isEmpty()) throw new IllegalStateException("MONGO_URI env var is required");
        MongoClient client = MongoClients.create(uri);
        MongoDatabase db = client.getDatabase(DB_NAME);
        this.coll = db.getCollection("postcode_stats");
    }

    public void incrementSearchCount(String postcode) {
        coll.updateOne(Filters.eq("postcode", postcode),
                new Document("$inc", new Document("search_count", 1L)));
    }
}
