package analytics;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import db.Db;
import org.bson.Document;

/**
 * Two counter collections, both shape {key, count}. UPSERT via
 * findOneAndUpdate + $inc gives us atomic "bump and return new value"
 * in a single round trip.
 */
public class AnalyticsDAO {

    private static final String VIEWS = "property_views";
    private static final String SEARCHES = "postcode_searches";
    private static final String COUNT = "count";

    private final MongoCollection<Document> views = Db.database().getCollection(VIEWS);
    private final MongoCollection<Document> searches = Db.database().getCollection(SEARCHES);

    public long incrementPropertyView(String propertyId) {
        return bump(views, "property_id", propertyId);
    }

    public long getPropertyViews(String propertyId) {
        return read(views, "property_id", propertyId);
    }

    public long incrementPostcodeSearch(String postcode) {
        return bump(searches, "postcode", postcode);
    }

    public long getPostcodeSearches(String postcode) {
        return read(searches, "postcode", postcode);
    }

    private long bump(MongoCollection<Document> coll, String keyField, String key) {
        if (key == null || key.isBlank()) return 0;
        Document updated = coll.findOneAndUpdate(
                Filters.eq(keyField, key),
                Updates.inc(COUNT, 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        if (updated == null) return 0;
        Long n = updated.getLong(COUNT);
        return n == null ? 0 : n;
    }

    private long read(MongoCollection<Document> coll, String keyField, String key) {
        if (key == null) return 0;
        Document d = coll.find(Filters.eq(keyField, key)).first();
        if (d == null) return 0;
        Long n = d.getLong(COUNT);
        return n == null ? 0 : n;
    }
}
