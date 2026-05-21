package notify;

import org.bson.types.ObjectId;

import java.util.List;

/** One purchaser's notification: who they are, plus the matching properties. */
public class Notification {
    public final ObjectId purchaserId;
    public final String purchaserName;
    public final String purchaserEmail;
    public final List<String> watchedPostcodes;
    public final List<PropertyForSale> matches;

    public Notification(ObjectId purchaserId, String purchaserName, String purchaserEmail,
                        List<String> watchedPostcodes, List<PropertyForSale> matches) {
        this.purchaserId = purchaserId;
        this.purchaserName = purchaserName;
        this.purchaserEmail = purchaserEmail;
        this.watchedPostcodes = watchedPostcodes;
        this.matches = matches;
    }
}
