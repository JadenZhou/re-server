package purchaser;

import java.util.List;

public class Purchaser {
    public String purchaserId;
    public String name;
    public String email;
    public List<String> postcodes;

    public Purchaser() {}

    public Purchaser(String purchaserId, String name, String email, List<String> postcodes) {
        this.purchaserId = purchaserId;
        this.name = name;
        this.email = email;
        this.postcodes = postcodes;
    }
}
