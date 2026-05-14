package app;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

public final class Mongo {

    public static final String DB_NAME = "nsw_property_data";

    private static volatile MongoClient client;

    private Mongo() { }

    public static MongoDatabase database() {
        return client().getDatabase(DB_NAME);
    }

    private static MongoClient client() {
        MongoClient local = client;
        if (local == null) {
            synchronized (Mongo.class) {
                local = client;
                if (local == null) {
                    String uri = System.getenv("MONGO_URI");
                    if (uri == null || uri.isEmpty()) {
                        throw new IllegalStateException("MONGO_URI env var is required");
                    }
                    local = MongoClients.create(uri);
                    client = local;
                    Runtime.getRuntime().addShutdownHook(new Thread(local::close, "mongo-shutdown"));
                }
            }
        }
        return local;
    }
}
