package notification;

import listing.ListingDAO;
import org.junit.jupiter.api.Test;
import property.Property;
import property.PropertyDAO;
import purchaser.Purchaser;
import purchaser.PurchaserDAO;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    private static Property forSaleProp(String propertyId, String postcode, String mongoObjId) {
        Property p = new Property(propertyId, postcode, "500000");
        p.forSale = true;
        p.mongoObjId = mongoObjId;
        return p;
    }

    // --- happy-path tests ---

    @Test
    void purchaserReceivesMatchingForSaleProperties() {
        PurchaserDAO purchaserDAO = mock(PurchaserDAO.class);
        PropertyDAO propertyDAO = mock(PropertyDAO.class);
        ListingDAO listingDAO = mock(ListingDAO.class);

        Purchaser alice = new Purchaser("id1", "Alice", "alice@test.com", List.of("2000"));
        when(purchaserDAO.getAllPurchasers()).thenReturn(List.of(alice));

        Property prop = forSaleProp("12345", "2000", "aaa111");
        when(propertyDAO.getForSalePropertiesByPostcode("2000")).thenReturn(List.of(prop));
        when(listingDAO.getLatestPriceByPropertyObjId("aaa111")).thenReturn(600_000.0);

        NotificationService service = new NotificationService(purchaserDAO, propertyDAO, listingDAO);
        List<NotificationService.PurchaserNotification> result = service.generateNotifications();

        assertEquals(1, result.size());
        assertEquals("Alice", result.get(0).purchaser.name);
        assertEquals(1, result.get(0).matches.size());
        assertEquals("12345", result.get(0).matches.get(0).propertyId);
        assertEquals(600_000.0, result.get(0).matches.get(0).price, 0.01);
        assertEquals("2000", result.get(0).matches.get(0).postcode);
    }

    @Test
    void purchaserWithMultiplePostcodesGetsMatchesFromAll() {
        PurchaserDAO purchaserDAO = mock(PurchaserDAO.class);
        PropertyDAO propertyDAO = mock(PropertyDAO.class);
        ListingDAO listingDAO = mock(ListingDAO.class);

        Purchaser carol = new Purchaser("id3", "Carol", "carol@test.com", List.of("2000", "2010"));
        when(purchaserDAO.getAllPurchasers()).thenReturn(List.of(carol));

        when(propertyDAO.getForSalePropertiesByPostcode("2000"))
                .thenReturn(List.of(forSaleProp("111", "2000", "obj1")));
        when(propertyDAO.getForSalePropertiesByPostcode("2010"))
                .thenReturn(List.of(forSaleProp("222", "2010", "obj2")));
        when(listingDAO.getLatestPriceByPropertyObjId("obj1")).thenReturn(500_000.0);
        when(listingDAO.getLatestPriceByPropertyObjId("obj2")).thenReturn(750_000.0);

        NotificationService service = new NotificationService(purchaserDAO, propertyDAO, listingDAO);
        List<NotificationService.PurchaserNotification> result = service.generateNotifications();

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).matches.size(), "Carol should get 2 property matches");

        List<String> matchedIds = result.get(0).matches.stream()
                .map(m -> m.propertyId).collect(Collectors.toList());
        assertTrue(matchedIds.contains("111"));
        assertTrue(matchedIds.contains("222"));
    }

    @Test
    void multiplePurchasersWithOverlappingPostcodesEachGetNotified() {
        PurchaserDAO purchaserDAO = mock(PurchaserDAO.class);
        PropertyDAO propertyDAO = mock(PropertyDAO.class);
        ListingDAO listingDAO = mock(ListingDAO.class);

        Purchaser dave = new Purchaser("id4", "Dave", "dave@test.com", List.of("2000"));
        Purchaser eve  = new Purchaser("id5", "Eve",  "eve@test.com",  List.of("2000"));
        when(purchaserDAO.getAllPurchasers()).thenReturn(List.of(dave, eve));

        Property prop = forSaleProp("999", "2000", "objX");
        when(propertyDAO.getForSalePropertiesByPostcode("2000")).thenReturn(List.of(prop));
        when(listingDAO.getLatestPriceByPropertyObjId("objX")).thenReturn(400_000.0);

        NotificationService service = new NotificationService(purchaserDAO, propertyDAO, listingDAO);
        List<NotificationService.PurchaserNotification> result = service.generateNotifications();

        assertEquals(2, result.size(), "Both purchasers should receive notifications for the shared postcode");
        // The shared postcode should be queried only once due to the cache
        verify(propertyDAO, times(1)).getForSalePropertiesByPostcode("2000");
    }

    @Test
    void multiplePropertiesInSamePostcodeAreAllReported() {
        PurchaserDAO purchaserDAO = mock(PurchaserDAO.class);
        PropertyDAO propertyDAO = mock(PropertyDAO.class);
        ListingDAO listingDAO = mock(ListingDAO.class);

        Purchaser gina = new Purchaser("id7", "Gina", "gina@test.com", List.of("2060"));
        when(purchaserDAO.getAllPurchasers()).thenReturn(List.of(gina));

        when(propertyDAO.getForSalePropertiesByPostcode("2060")).thenReturn(List.of(
                forSaleProp("A1", "2060", "o1"),
                forSaleProp("A2", "2060", "o2"),
                forSaleProp("A3", "2060", "o3")));
        when(listingDAO.getLatestPriceByPropertyObjId("o1")).thenReturn(300_000.0);
        when(listingDAO.getLatestPriceByPropertyObjId("o2")).thenReturn(450_000.0);
        when(listingDAO.getLatestPriceByPropertyObjId("o3")).thenReturn(600_000.0);

        NotificationService service = new NotificationService(purchaserDAO, propertyDAO, listingDAO);
        List<NotificationService.PurchaserNotification> result = service.generateNotifications();

        assertEquals(1, result.size());
        assertEquals(3, result.get(0).matches.size(), "All three properties in postcode 2060 should be listed");
    }

    // --- edge-case / exclusion tests ---

    @Test
    void purchaserWithNoMatchingPropertiesIsExcluded() {
        PurchaserDAO purchaserDAO = mock(PurchaserDAO.class);
        PropertyDAO propertyDAO = mock(PropertyDAO.class);
        ListingDAO listingDAO = mock(ListingDAO.class);

        Purchaser bob = new Purchaser("id2", "Bob", "bob@test.com", List.of("2010"));
        when(purchaserDAO.getAllPurchasers()).thenReturn(List.of(bob));
        when(propertyDAO.getForSalePropertiesByPostcode("2010")).thenReturn(List.of());

        NotificationService service = new NotificationService(purchaserDAO, propertyDAO, listingDAO);
        List<NotificationService.PurchaserNotification> result = service.generateNotifications();

        assertTrue(result.isEmpty(), "Purchaser with no matching for-sale properties should not appear");
    }

    @Test
    void purchaserWithNoPostcodesGetsNoNotification() {
        PurchaserDAO purchaserDAO = mock(PurchaserDAO.class);
        PropertyDAO propertyDAO = mock(PropertyDAO.class);
        ListingDAO listingDAO = mock(ListingDAO.class);

        Purchaser frank = new Purchaser("id6", "Frank", "frank@test.com", List.of());
        when(purchaserDAO.getAllPurchasers()).thenReturn(List.of(frank));

        NotificationService service = new NotificationService(purchaserDAO, propertyDAO, listingDAO);
        List<NotificationService.PurchaserNotification> result = service.generateNotifications();

        assertTrue(result.isEmpty(), "Purchaser with no postcodes should receive no notifications");
        verifyNoInteractions(propertyDAO, listingDAO);
    }

    @Test
    void emptyPurchaserListProducesNoNotifications() {
        PurchaserDAO purchaserDAO = mock(PurchaserDAO.class);
        PropertyDAO propertyDAO = mock(PropertyDAO.class);
        ListingDAO listingDAO = mock(ListingDAO.class);

        when(purchaserDAO.getAllPurchasers()).thenReturn(List.of());

        NotificationService service = new NotificationService(purchaserDAO, propertyDAO, listingDAO);
        List<NotificationService.PurchaserNotification> result = service.generateNotifications();

        assertTrue(result.isEmpty());
        verifyNoInteractions(propertyDAO, listingDAO);
    }
}
