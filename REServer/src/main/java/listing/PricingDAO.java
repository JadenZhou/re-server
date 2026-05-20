package listing;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PricingDAO {

    private static final String DB_NAME = "nsw_property_data";

    private final MongoCollection<Document> coll;

    public PricingDAO() {
        String uri = System.getenv("MONGO_URI");
        if (uri == null || uri.isEmpty()) throw new IllegalStateException("MONGO_URI env var is required");
        MongoClient client = MongoClients.create(uri);
        MongoDatabase db = client.getDatabase(DB_NAME);
        this.coll = db.getCollection("property_pricing_updates");
    }

    public void insertPrice(ObjectId pid, double price, Date date) {
        coll.insertOne(new Document()
                .append("pid", pid)
                .append("updated_price", price)
                .append("date", date));
    }

    public void insertMany(List<Document> docs) {
        if (!docs.isEmpty()) coll.insertMany(docs);
    }

    public double getLatestPrice(ObjectId pid) {
        Document d = coll.find(Filters.eq("pid", pid))
                .sort(Sorts.descending("date"))
                .first();
        if (d == null) return 0;
        Double p = d.getDouble("updated_price");
        return p == null ? 0 : p;
    }

    public Map<ObjectId, Double> getLatestPrices(Collection<ObjectId> pids) {
        Map<ObjectId, Double> result = new HashMap<>();
        if (pids.isEmpty()) return result;
        for (Document d : coll.aggregate(Arrays.asList(
                Aggregates.match(Filters.in("pid", pids)),
                Aggregates.sort(Sorts.descending("date")),
                Aggregates.group("$pid", Accumulators.first("price", "$updated_price"))))) {
            ObjectId pid = d.getObjectId("_id");
            Double price = d.getDouble("price");
            if (pid != null && price != null) result.put(pid, price);
        }
        return result;
    }

    public List<PriceEntry> getPriceHistory(ObjectId pid) {
        List<PriceEntry> out = new ArrayList<>();
        for (Document d : coll.find(Filters.eq("pid", pid)).sort(Sorts.ascending("date"))) {
            Double price = d.getDouble("updated_price");
            Date date = d.getDate("date");
            if (price != null && date != null) out.add(new PriceEntry(price, date.toString()));
        }
        return out;
    }
}
