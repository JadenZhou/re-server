package notify;

/** A single for-sale property in a notification — what a purchaser cares about. */
public class PropertyForSale {
    public final long propertyId;
    public final double price;
    public final String postcode;

    public PropertyForSale(long propertyId, double price, String postcode) {
        this.propertyId = propertyId;
        this.price = price;
        this.postcode = postcode;
    }
}
