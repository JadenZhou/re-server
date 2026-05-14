package notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure business logic for the notifier. Given a precomputed postcode index
 * of for-sale properties and a list of purchasers, produces one notification
 * per purchaser that has matching properties.
 *
 * Purchasers with zero matches are omitted from the output (keeps the report
 * short — a 10k-purchaser table that's mostly empty is hostile to read).
 *
 * No Mongo, no I/O — so this class is trivial to unit-test in isolation.
 */
public class NotifyService {

    /**
     * @param postcodeIndex map of postcode → list of for-sale properties in that postcode
     * @param purchasers    every purchaser to evaluate
     * @return one notification per purchaser with at least one match
     */
    public List<Notification> notify(Map<String, List<PropertyForSale>> postcodeIndex,
                                     List<PurchaserSummary> purchasers) {
        List<Notification> out = new ArrayList<>();
        for (PurchaserSummary p : purchasers) {
            List<PropertyForSale> matches = collectMatches(postcodeIndex, p.postcodes);
            if (matches.isEmpty()) continue;
            out.add(new Notification(p.id, p.name, p.email, p.postcodes, matches));
        }
        return out;
    }

    private List<PropertyForSale> collectMatches(Map<String, List<PropertyForSale>> index,
                                                 List<String> postcodes) {
        if (postcodes == null || postcodes.isEmpty()) return List.of();
        List<PropertyForSale> out = new ArrayList<>();
        for (String pc : postcodes) {
            List<PropertyForSale> hits = index.get(pc);
            if (hits != null) out.addAll(hits);
        }
        return out;
    }
}
