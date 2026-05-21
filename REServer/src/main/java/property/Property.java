package property;

import org.bson.types.ObjectId;

/**
 * Property record. Source documents in `properties` are immutable sale rows
 * (same `property_id` may appear multiple times — one row per sale). Fields
 * mirror the most useful columns from the NSW property data set so downstream
 * consumers (UI, listings, purchaser matching) can read address, council,
 * type, and most-recent contract date without going back to Mongo.
 */
public class Property {
    public ObjectId _id;
    public String postcode;
    public String propertyPrice;
    public boolean forSale;
    public String address;
    public String councilName;
    public String propertyType;
    public String contractDate;
    public long searchCount = 0;

    public Property() {}

    public Property(ObjectId _id, String postcode, String propertyPrice) {
        this._id = _id;
        this.postcode = postcode;
        this.propertyPrice = propertyPrice;
        this.forSale = false;
    }
}
