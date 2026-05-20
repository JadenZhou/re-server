package property;

public class Property {
    public String propertyID;
    public String postcode;
    public String propertyPrice;
    public boolean forSale;
    public String address;
    public String councilName;
    public String propertyType;
    public String contractDate;

    public Property() {}

    public Property(String propertyID, String postcode, String propertyPrice) {
        this.propertyID = propertyID;
        this.postcode = postcode;
        this.propertyPrice = propertyPrice;
        this.forSale = false;
    }
}
