package notification;

import listing.ListingDAO;
import property.Property;
import property.PropertyDAO;
import purchaser.Purchaser;
import purchaser.PurchaserDAO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Matches purchasers to for-sale properties in their preferred postcodes.
 * Uses a postcode cache so each postcode is queried once even when shared
 * by many purchasers.
 */
public class NotificationService {

    private final PurchaserDAO purchaserDAO;
    private final PropertyDAO propertyDAO;
    private final ListingDAO listingDAO;

    public NotificationService(PurchaserDAO purchaserDAO, PropertyDAO propertyDAO, ListingDAO listingDAO) {
        this.purchaserDAO = purchaserDAO;
        this.propertyDAO = propertyDAO;
        this.listingDAO = listingDAO;
    }

    public List<PurchaserNotification> generateNotifications() {
        List<Purchaser> purchasers = purchaserDAO.getAllPurchasers();

        // Collect all distinct postcodes up-front to query each postcode only once.
        Set<String> distinctPostcodes = new HashSet<>();
        for (Purchaser p : purchasers) {
            distinctPostcodes.addAll(p.postcodes);
        }

        Map<String, List<Property>> forSaleByPostcode = new HashMap<>();
        for (String postcode : distinctPostcodes) {
            List<Property> props = propertyDAO.getForSalePropertiesByPostcode(postcode);
            if (!props.isEmpty()) {
                forSaleByPostcode.put(postcode, props);
            }
        }

        List<PurchaserNotification> notifications = new ArrayList<>();
        for (Purchaser purchaser : purchasers) {
            List<PropertyMatch> matches = new ArrayList<>();
            for (String postcode : purchaser.postcodes) {
                for (Property prop : forSaleByPostcode.getOrDefault(postcode, List.of())) {
                    double price = listingDAO.getLatestPriceByPropertyObjId(prop.mongoObjId);
                    matches.add(new PropertyMatch(prop.propertyID, postcode, price));
                }
            }
            if (!matches.isEmpty()) {
                notifications.add(new PurchaserNotification(purchaser, matches));
            }
        }
        return notifications;
    }

    public static class PropertyMatch {
        public final String propertyId;
        public final String postcode;
        public final double price;

        public PropertyMatch(String propertyId, String postcode, double price) {
            this.propertyId = propertyId;
            this.postcode = postcode;
            this.price = price;
        }
    }

    public static class PurchaserNotification {
        public final Purchaser purchaser;
        public final List<PropertyMatch> matches;

        public PurchaserNotification(Purchaser purchaser, List<PropertyMatch> matches) {
            this.purchaser = purchaser;
            this.matches = matches;
        }
    }
}
