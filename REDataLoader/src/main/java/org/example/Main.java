package org.example;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.bson.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class Main {

    private static final CSVFormat CSV_FORMAT = CSVFormat.Builder.create(CSVFormat.RFC4180)
            .setHeader()
            .setSkipHeaderRecord(true)
            .setAllowDuplicateHeaderNames(false)
            .build();

    private static final int BATCH_SIZE = 1000;
    private static final String DB_NAME = "realestate";
    private static final String COLLECTION_NAME = "properties";

    static final private String PATH_TO_FILE = System.getenv().getOrDefault("RE_CSV_PATH",
            "../data/nsw_property_data.csv");

    public static void main(String[] args) throws IOException {
        String mongoUri = requireEnv("MONGO_URI");
        Path csvFilePath = Paths.get(PATH_TO_FILE);
        System.out.println("Loading CSV: " + csvFilePath);

        InsertManyOptions unordered = new InsertManyOptions().ordered(false);
        long start = System.currentTimeMillis();
        long inserted = 0;
        long parseErrors = 0;

        try (MongoClient mongo = MongoClients.create(mongoUri);
             CSVParser parser = CSVParser.parse(csvFilePath, StandardCharsets.UTF_8, CSV_FORMAT)) {

            MongoDatabase db = mongo.getDatabase(DB_NAME);
            db.getCollection(COLLECTION_NAME).drop();
            MongoCollection<Document> coll = db.getCollection(COLLECTION_NAME);

            System.out.println("Headers: " + parser.getHeaderNames());

            List<Document> batch = new ArrayList<>(BATCH_SIZE);
            for (CSVRecord record : parser) {
                Document doc = toDocument(record);
                if (doc == null) {
                    parseErrors++;
                    continue;
                }
                batch.add(doc);
                if (batch.size() >= BATCH_SIZE) {
                    coll.insertMany(batch, unordered);
                    inserted += batch.size();
                    batch.clear();
                    if (inserted % 50_000 == 0) {
                        System.out.printf("  ... %d rows inserted (%.1fs)%n",
                                inserted, (System.currentTimeMillis() - start) / 1000.0);
                    }
                }
            }
            if (!batch.isEmpty()) {
                coll.insertMany(batch, unordered);
                inserted += batch.size();
            }

            System.out.println("Building indexes...");
            coll.createIndex(Indexes.ascending("property_id"));
            coll.createIndex(Indexes.ascending("post_code"));
            coll.createIndex(Indexes.ascending("purchase_price"));

            double secs = (System.currentTimeMillis() - start) / 1000.0;
            System.out.printf("Done. Inserted %d docs in %.1fs (%.0f docs/sec). Parse errors: %d%n",
                    inserted, secs, inserted / Math.max(secs, 0.001), parseErrors);
        }
    }

    private static Document toDocument(CSVRecord record) {
        try {
            Document d = new Document();
            // property_id is NOT unique in the CSV — each row is a sale, not a property.
            // Let Mongo auto-generate _id; index property_id separately.
            Long propertyId = parseLongOrNull(record.get("property_id"));
            if (propertyId == null) return null;
            d.put("property_id", propertyId);
            putString(d, record, "download_date");
            putString(d, record, "council_name");
            d.put("purchase_price", parseLongOrNull(record.get("purchase_price")));
            putString(d, record, "address");
            putString(d, record, "post_code");
            putString(d, record, "property_type");
            putString(d, record, "strata_lot_number");
            putString(d, record, "property_name");
            d.put("area", parseDoubleOrNull(record.get("area")));
            putString(d, record, "area_type");
            putString(d, record, "contract_date");
            putString(d, record, "settlement_date");
            putString(d, record, "zoning");
            putString(d, record, "nature_of_property");
            putString(d, record, "primary_purpose");
            putString(d, record, "legal_description");
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private static void putString(Document d, CSVRecord r, String col) {
        String v = r.get(col);
        if (v != null && !v.isEmpty()) d.put(col, v);
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("Missing required env var: " + name);
        }
        return v;
    }
}
