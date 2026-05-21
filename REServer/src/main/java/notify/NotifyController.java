package notify;

import io.javalin.http.Context;
import listing.ListingDAO;
import listing.PricingDAO;
import org.bson.Document;
import org.bson.types.ObjectId;
import property.PropertyDAO;
import purchaser.Purchaser;
import purchaser.PurchaserDAO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GET /notify
 *   ?format=html (default) — table per purchaser
 *   ?format=text           — plain text, one block per purchaser (large output friendly)
 */
public class NotifyController {

    private final ListingDAO listingDAO;
    private final PricingDAO pricingDAO;
    private final PropertyDAO propertyDAO;
    private final PurchaserDAO purchaserDAO;
    private final NotifyService service;

    public NotifyController(ListingDAO listingDAO, PricingDAO pricingDAO,
                            PropertyDAO propertyDAO, PurchaserDAO purchaserDAO,
                            NotifyService service) {
        this.listingDAO = listingDAO;
        this.pricingDAO = pricingDAO;
        this.propertyDAO = propertyDAO;
        this.purchaserDAO = purchaserDAO;
        this.service = service;
    }

    public void notify(Context ctx) {
        Map<String, List<PropertyForSale>> index = buildPostcodeIndex();
        List<PurchaserSummary> purchasers = fetchPurchasers();
        List<Notification> notifications = service.notify(index, purchasers);

        String format = ctx.queryParam("format");
        if ("text".equalsIgnoreCase(format)) {
            ctx.contentType("text/plain");
            ctx.result(renderText(notifications));
        } else {
            ctx.html(renderHtml(notifications));
        }
    }

    private Map<String, List<PropertyForSale>> buildPostcodeIndex() {
        List<Document> listings = listingDAO.findAll();
        List<ObjectId> pids = listings.stream()
                .map(d -> d.getObjectId("pid"))
                .filter(p -> p != null)
                .distinct()
                .collect(Collectors.toList());
        if (pids.isEmpty()) return Collections.emptyMap();

        Map<ObjectId, Double> prices = pricingDAO.getLatestPrices(pids);

        Map<String, List<PropertyForSale>> index = new HashMap<>();
        for (Document p : propertyDAO.findByIds(pids)) {
            ObjectId pid = p.getObjectId("_id");
            String postcode = p.getString("post_code");
            Long propertyId = p.getLong("property_id");
            Double price = prices.get(pid);
            if (postcode == null || propertyId == null || price == null) continue;
            index.computeIfAbsent(postcode, k -> new ArrayList<>())
                    .add(new PropertyForSale(propertyId, price, postcode));
        }
        return index;
    }

    private List<PurchaserSummary> fetchPurchasers() {
        List<PurchaserSummary> out = new ArrayList<>();
        for (Purchaser p : purchaserDAO.getAllPurchasers()) {
            out.add(new PurchaserSummary(p.purchaserId, p.name, p.email, p.postcodes));
        }
        return out;
    }

    static String renderText(List<Notification> notifications) {
        StringBuilder sb = new StringBuilder();
        sb.append("Notification report — ").append(notifications.size())
          .append(" purchaser(s) with matching for-sale properties\n");
        sb.append("=".repeat(72)).append("\n\n");
        for (Notification n : notifications) {
            sb.append(n.purchaserName == null ? "(unnamed)" : n.purchaserName)
              .append("  <").append(n.purchaserEmail == null ? "?" : n.purchaserEmail).append(">")
              .append("  id=").append(n.purchaserId).append("\n");
            sb.append("  watching: ").append(String.join(", ", n.watchedPostcodes)).append("\n");
            for (PropertyForSale p : n.matches) {
                sb.append(String.format("    property_id=%-12d  postcode=%s  price=$%,.0f%n",
                        p.propertyId, p.postcode, p.price));
            }
            sb.append("\n");
        }
        if (notifications.isEmpty()) sb.append("(no matches)\n");
        return sb.toString();
    }

    static String renderHtml(List<Notification> notifications) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><title>Notifications</title>")
          .append("<style>body{font-family:system-ui,sans-serif;max-width:900px;margin:2em auto;padding:0 1em}")
          .append("h1{margin-bottom:.25em}h2{margin-top:1.5em;border-top:1px solid #ddd;padding-top:1em}")
          .append(".meta{color:#666;font-size:.9em;margin-bottom:.5em}")
          .append("table{border-collapse:collapse;width:100%}th,td{border:1px solid #ccc;padding:.4em .7em;text-align:left}")
          .append("th{background:#f4f4f4}.empty{color:#999;font-style:italic}</style></head><body>");
        sb.append("<h1>Notification report</h1>");
        sb.append("<p class=\"meta\">").append(notifications.size())
          .append(" purchaser(s) with matching for-sale properties.</p>");
        if (notifications.isEmpty()) {
            sb.append("<p class=\"empty\">No purchaser had a matching property in their watched postcodes.</p>");
        }
        for (Notification n : notifications) {
            sb.append("<h2>").append(escape(n.purchaserName)).append("</h2>");
            sb.append("<p class=\"meta\">")
              .append(escape(n.purchaserEmail))
              .append(" · id ").append(escape(n.purchaserId.toString()))
              .append(" · watching ").append(escape(String.join(", ", n.watchedPostcodes)))
              .append("</p>");
            sb.append("<table><tr><th>Property ID</th><th>Postcode</th><th>Price</th></tr>");
            for (PropertyForSale p : n.matches) {
                sb.append("<tr><td>").append(p.propertyId)
                  .append("</td><td>").append(escape(p.postcode))
                  .append("</td><td>$").append(String.format("%,.0f", p.price))
                  .append("</td></tr>");
            }
            sb.append("</table>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
