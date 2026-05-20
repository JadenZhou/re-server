package favoritedProperties;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.ObjectId;
import java.util.ArrayList;
import java.util.List;

public class FavoritedPropertiesDAO {
  private static final String COLLECTION_NAME = "favorited_properties";
  private static final String DB_NAME = "nsw_property_data";
  private final MongoClient client;
  private final MongoCollection<Document> collection;


  public FavoritedPropertiesDAO() {
    String uri = System.getenv("MONGO_URI");
    if (uri == null || uri.isEmpty()) {
      throw new IllegalArgumentException("MONGO_URI environment variable not set");
    }
    this.client = MongoClients.create(uri);
    MongoDatabase database = client.getDatabase(DB_NAME);
    this.collection = database.getCollection(COLLECTION_NAME);
  }

  /* favorite a property */
  public void favoriteProperty(ObjectId aid, ObjectId pid) {
    boolean alreadyFavorite = collection.find(
            Filters.and(
                    Filters.eq("accountId", aid),
                    Filters.eq("propertyId", pid)
            )
    ).first() != null;

    if (!alreadyFavorite) {
      Document doc = new Document("accountId", aid)
              .append("propertyId", pid);
      collection.insertOne(doc);
    }
  }


  /* un-favorite a property */
  public void unfavoriteProperty(ObjectId aid, ObjectId pid) {
    collection.deleteOne(Filters.and(
            Filters.eq("accountId", aid),
            Filters.eq("propertyId", pid)

    ));
  }

  /* get all favorited properties of all users*/
  public List<Document> getAllFavoritedProperties() {
    List<Document> results = new ArrayList<>();
    collection.find().into(results);
    return results;

  }

  public List<Document> getAllFavoritedPropertiesByUser(ObjectId userId) {
    List<Document> results = new ArrayList<>();
    collection.find(Filters.eq("accountId", userId)).into(results);
    return results;
  }

  public List<Document> findAllFavoritedProperties() {
    List<Document> results = new ArrayList<>();
    collection.find().into(results);
    return results;
  }

  public void close() {
    client.close();
  }
}
