package stats;

import app.Mongo;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import org.bson.Document;

public class MongoPostcodeStats implements PostcodeStats {

    private static final String COLLECTION = "postcode_stats";
    private static final String COUNT_FIELD = "search_count";

    private final MongoCollection<Document> coll;

    public MongoPostcodeStats() {
        this.coll = Mongo.db().getCollection(COLLECTION);
    }

    @Override
    public long incrementSearch(String postcode) {
        if (postcode == null || postcode.isBlank()) return 0;
        Document updated = coll.findOneAndUpdate(
                Filters.eq("postcode", postcode),
                Updates.inc(COUNT_FIELD, 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        if (updated == null) return 0;
        Long n = updated.getLong(COUNT_FIELD);
        return n == null ? 0 : n;
    }

    @Override
    public long getSearchCount(String postcode) {
        if (postcode == null) return 0;
        Document d = coll.find(Filters.eq("postcode", postcode)).first();
        if (d == null) return 0;
        Long n = d.getLong(COUNT_FIELD);
        return n == null ? 0 : n;
    }
}
