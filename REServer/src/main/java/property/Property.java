package property;

// Simple class to provide test data in PropertyDAO

public class Property {
    public String propertyID;
    public String postcode;
    public String propertyPrice;
    public boolean forSale;

    public Property(String propertyID, String postcode, String propertyPrice) {
        this.propertyID = propertyID;
        this.postcode = postcode;
        this.propertyPrice = propertyPrice;
        this.forSale = false;
    }

    // needed for JSON conversion
    public Property() {}
}
