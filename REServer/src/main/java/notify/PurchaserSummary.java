package notify;

import java.util.List;

/**
 * The data NotifyService needs about a purchaser. Decoupled from
 * `purchaser.Purchaser` so the service can be unit-tested without
 * touching Mongo or any DAO classes.
 */
public class PurchaserSummary {
    public final String id;
    public final String name;
    public final String email;
    public final List<String> postcodes;

    public PurchaserSummary(String id, String name, String email, List<String> postcodes) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.postcodes = postcodes;
    }
}
