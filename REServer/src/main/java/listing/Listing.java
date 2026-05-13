package listing;

public class Listing {
    public String listingId;
    public String propertyObjId;
    public boolean isDiscounted;
    public String dateAdded;
    public double latestPrice;

    public Listing() {}

    public Listing(String listingId, String propertyObjId, boolean isDiscounted, String dateAdded, double latestPrice) {
        this.listingId = listingId;
        this.propertyObjId = propertyObjId;
        this.isDiscounted = isDiscounted;
        this.dateAdded = dateAdded;
        this.latestPrice = latestPrice;
    }
}
