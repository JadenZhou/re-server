package db;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

/**
 * Purchaser-service's Mongo handle. Owns the `accounts` collection (Buyer
 * documents with embedded `postcode_interest` arrays).
 */
public final class Db {

    public static final String DB_NAME = "nsw_property_data";

    private static volatile MongoDatabase db;

    private Db() {}

    public static MongoDatabase database() {
        MongoDatabase local = db;
        if (local != null) return local;
        synchronized (Db.class) {
            if (db == null) {
                String uri = System.getenv("MONGO_URI");
                if (uri == null || uri.isEmpty()) {
                    throw new IllegalStateException("MONGO_URI env var is required");
                }
                MongoClient client = MongoClients.create(uri);
                db = client.getDatabase(DB_NAME);
            }
            return db;
        }
    }
}
