package notify;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for NotifyService. The service takes pure data inputs
 * (a postcode → for-sale index, plus purchasers) so no Mongo is needed.
 */
class NotifyServiceTest {

    private final NotifyService service = new NotifyService();

    private static Map<String, List<PropertyForSale>> buildIndex() {
        Map<String, List<PropertyForSale>> idx = new HashMap<>();
        idx.put("2000", List.of(
                new PropertyForSale(101L, 1_000_000.0, "2000"),
                new PropertyForSale(102L, 1_200_000.0, "2000")));
        idx.put("2480", List.of(
                new PropertyForSale(201L, 800_000.0, "2480")));
        // 2770 has no for-sale properties.
        return idx;
    }

    @Test
    void purchaserMatchesAcrossMultiplePostcodes() {
        Notification n = service.notify(buildIndex(), List.of(
                new PurchaserSummary("alice", "Alice", "a@x.com", List.of("2000", "2480"))
        )).get(0);

        assertEquals(3, n.matches.size(),
                "Alice watches both 2000 (2 properties) and 2480 (1) — expect 3 matches");
        assertEquals("alice", n.purchaserId);
    }

    @Test
    void purchaserWithUnmatchedPostcodeOmittedFromOutput() {
        // Bob watches 2770, which has zero for-sale properties.
        List<Notification> out = service.notify(buildIndex(), List.of(
                new PurchaserSummary("bob", "Bob", "b@x.com", List.of("2770"))
        ));
        assertTrue(out.isEmpty(),
                "Purchasers with zero matches should be omitted from the report");
    }

    @Test
    void purchaserWithNoPostcodesOmittedFromOutput() {
        List<Notification> out = service.notify(buildIndex(), List.of(
                new PurchaserSummary("carol", "Carol", "c@x.com", List.of())
        ));
        assertTrue(out.isEmpty(),
                "Empty postcode list — no preferences — means no notification");
    }

    @Test
    void purchaserWithNullPostcodesIsSafe() {
        List<Notification> out = service.notify(buildIndex(), List.of(
                new PurchaserSummary("dave", "Dave", "d@x.com", null)
        ));
        assertTrue(out.isEmpty(),
                "Null postcode list should be treated as no preferences, not crash");
    }

    @Test
    void emptyIndexEmitsNoNotifications() {
        List<Notification> out = service.notify(Map.of(), List.of(
                new PurchaserSummary("alice", "Alice", "a@x.com", List.of("2000"))
        ));
        assertTrue(out.isEmpty(),
                "No for-sale properties anywhere → no notifications");
    }

    @Test
    void multiplePurchasersGetIndependentMatches() {
        List<Notification> out = service.notify(buildIndex(), List.of(
                new PurchaserSummary("alice", "Alice", "a@x.com", List.of("2000")),
                new PurchaserSummary("bob",   "Bob",   "b@x.com", List.of("2480")),
                new PurchaserSummary("carol", "Carol", "c@x.com", List.of("2770"))  // unmatched
        ));
        assertEquals(2, out.size(),
                "Alice and Bob match; Carol is dropped");
        assertEquals("alice", out.get(0).purchaserId);
        assertEquals(2, out.get(0).matches.size());
        assertEquals("bob", out.get(1).purchaserId);
        assertEquals(1, out.get(1).matches.size());
    }

    @Test
    void matchContentsPreserveIdPostcodeAndPrice() {
        Notification n = service.notify(buildIndex(), List.of(
                new PurchaserSummary("alice", "Alice", "a@x.com", List.of("2480"))
        )).get(0);

        PropertyForSale only = n.matches.get(0);
        assertEquals(201L, only.propertyId);
        assertEquals("2480", only.postcode);
        assertEquals(800_000.0, only.price);
    }
}
