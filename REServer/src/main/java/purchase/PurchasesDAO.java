package purchase;

import com.mongodb.client.FindIterable;
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

import purchaser.Purchaser;

public class PurchasesDAO {

  private static final String DB_NAME = "nsw_property_data";
  private static final String COLLECTION_NAME = "purchases";
  // Cap unbounded queries so a single request can't try to ship millions of rows.
  private static final int MAX_RESULTS = 1000;

  private final MongoCollection<Document> coll;

  public PurchasesDAO() {
    String uri = System.getenv("MONGO_URI");
    if (uri == null || uri.isEmpty()) {
      throw new IllegalStateException("MONGO_URI env var is required");
    }
    MongoClient client = MongoClients.create(uri);
    MongoDatabase db = client.getDatabase(DB_NAME);
    this.coll = db.getCollection(COLLECTION_NAME);
  }

  public void addPurchase(ObjectId aid, ObjectId pid) {
    coll.insertOne(new Document("aid", aid).append("pid", pid).append("date", new Date()));
  }

  public List<Purchase> getPurchasesByAccount(ObjectId aid) {
    FindIterable<Document> docs = coll.find(Filters.eq("aid", aid));
    List<Purchase> ret = new ArrayList<Purchase>();
    for (Document d : docs) {
      ret.add(toPurchase(d));
    }
    return ret;
  }

  /**
   * Method to get all purchases that have purchased a specific property
   *
   * @param pid the property ID
   * @return the list of purchases
   */
  public List<Purchase> getPurchasesByProperty(ObjectId pid) {
    FindIterable<Document> docs = coll.find(Filters.eq("pid", pid));
    List<Purchase> ret = new ArrayList<Purchase>();
    for (Document d : docs) {
      ret.add(new Purchase(d.getObjectId("_id"), d.getObjectId("aid"), d.getObjectId("pid"), d.getDate("date")));
    }
    return ret;
  }

  private Purchase toPurchase(Document doc) {
    return new Purchase(
            doc.getObjectId("_id"),
            doc.getObjectId("aid"),
            doc.getObjectId("pid"),
            doc.getDate("date"));
  }
}
