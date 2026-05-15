package app;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

/** Shared MongoDB bootstrap so each DAO doesn't re-read the env var and DB name. */
public final class Mongo {

    public static final String DB_NAME = "nsw_property_data";

    private static volatile MongoDatabase db;

    private Mongo() {}

    public static MongoDatabase db() {
        MongoDatabase local = db;
        if (local != null) return local;
        synchronized (Mongo.class) {
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
