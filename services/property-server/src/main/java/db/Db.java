package db;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

/**
 * Property-service's Mongo handle. Owns the property-context collections:
 *   properties              one doc per property
 *   listings                one doc per active listing, with `pid` -> properties._id
 *   property_pricing_updates one doc per price entry, with `pid` -> properties._id
 *
 * Counters live in the analytics service's separate collections; this
 * service has no knowledge of them.
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
