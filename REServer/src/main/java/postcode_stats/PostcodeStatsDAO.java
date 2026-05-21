package postcode_stats;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;

import org.bson.Document;

public class PostcodeStatsDAO {
  private static final String DB_NAME = "nsw_property_data";
  private static final String COLL_NAME = "postcode_stats";

  private final MongoCollection<Document> coll;

  public PostcodeStatsDAO() {
    String uri = System.getenv("MONGO_URI");
    if (uri == null || uri.isEmpty()) {
      throw new IllegalStateException("MONGO_URI env var is required");
    }
    MongoClient client = MongoClients.create(uri);
    MongoDatabase db = client.getDatabase(DB_NAME);
    this.coll = db.getCollection(COLL_NAME);
  }

  /**
   * Audits a postcode, updating relevant metrics such as search count.
   *
   * @param postcode the postcode to audit
   */
  public void auditPostcode(String postcode) {
    Document exists = coll.find(Filters.eq("postcode", postcode)).first();
    if (exists == null) {
      coll.insertOne(new Document("postcode", postcode).append("search_count", 1L));
    } else {
      coll.updateOne(
              Filters.eq("postcode", postcode),
              new Document(
                      "$inc",
                      new Document("search_count", 1L)
              )
      );
    }
  }
}
