package notify;

import client.ServiceClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway-side notifier orchestration. The brief warned: "the implementation
 * of client facing APIs will need to access more than one microservice. Where
 * you place this logic is a key design decision." Living in the gateway
 * keeps the downstream services single-purpose — neither property-server nor
 * purchaser-server has any knowledge of the other.
 *
 * Steps:
 *   1. GET /property/for-sale       (property-server) — every for-sale property + latest price
 *   2. GET /purchaser               (purchaser-server) — every buyer + their watched postcodes
 *   3. Build a postcode → properties index in-process
 *   4. For each buyer, collect matches in their watched postcodes
 *   5. Emit one notification per buyer with ≥ 1 match
 */
public class Notifier {

    private final ServiceClient client;

    public Notifier(ServiceClient client) {
        this.client = client;
    }

    public List<Map<String, Object>> buildNotifications() {
        Map<String, List<Map<String, Object>>> indexByPostcode = fetchForSaleIndex();
        List<Map<String, Object>> buyers = fetchBuyers();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> b : buyers) {
            @SuppressWarnings("unchecked")
            List<String> postcodes = (List<String>) b.getOrDefault("postcodes", List.of());
            if (postcodes == null || postcodes.isEmpty()) continue;

            List<Map<String, Object>> matches = new ArrayList<>();
            for (String pc : postcodes) {
                List<Map<String, Object>> hits = indexByPostcode.get(pc);
                if (hits != null) matches.addAll(hits);
            }
            if (matches.isEmpty()) continue;

            Map<String, Object> note = new HashMap<>();
            note.put("purchaserId", b.get("purchaserId"));
            note.put("purchaserName", b.get("name"));
            note.put("purchaserEmail", b.get("email"));
            note.put("watchedPostcodes", postcodes);
            note.put("matches", matches);
            out.add(note);
        }
        return out;
    }

    private Map<String, List<Map<String, Object>>> fetchForSaleIndex() {
        ServiceClient.Response r = client.propertyGet("/internal/for-sale");
        if (!r.ok()) throw new RuntimeException("property-server /internal/for-sale failed: " + r.status());
        List<Map<String, Object>> rows = client.deserializeList(r.body());

        Map<String, List<Map<String, Object>>> idx = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String postcode = (String) row.get("postcode");
            Object nswPropertyId = row.get("nswPropertyId");
            Object propertyId = row.get("propertyId");
            Object price = row.get("latestPrice");
            if (postcode == null || price == null) continue;

            Map<String, Object> entry = new HashMap<>();
            entry.put("propertyId", propertyId);
            entry.put("nswPropertyId", nswPropertyId);
            entry.put("postcode", postcode);
            entry.put("price", price);
            idx.computeIfAbsent(postcode, k -> new ArrayList<>()).add(entry);
        }
        return idx;
    }

    private List<Map<String, Object>> fetchBuyers() {
        ServiceClient.Response r = client.purchaserGet("/purchaser");
        if (!r.ok()) throw new RuntimeException("purchaser-server /purchaser failed: " + r.status());
        return client.deserializeList(r.body());
    }
}
