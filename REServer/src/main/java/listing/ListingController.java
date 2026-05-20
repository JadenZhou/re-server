package listing;

import io.javalin.http.Context;
import org.bson.Document;
import org.bson.types.ObjectId;
import property.PropertyDAO;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class ListingController {

    private final ListingDAO listingDAO;
    private final PricingDAO pricingDAO;
    private final PropertyDAO propertyDAO;

    public ListingController(ListingDAO listingDAO, PricingDAO pricingDAO, PropertyDAO propertyDAO) {
        this.listingDAO = listingDAO;
        this.pricingDAO = pricingDAO;
        this.propertyDAO = propertyDAO;
    }

    public void createListing(Context ctx) {
        CreateListingRequest req = ctx.bodyValidator(CreateListingRequest.class)
                .check(r -> r.propertyObjId != null && !r.propertyObjId.isEmpty(), "propertyObjId is required")
                .check(r -> r.price > 0, "price must be positive")
                .get();

        if (!ObjectId.isValid(req.propertyObjId)) {
            ctx.result("Property not found"); ctx.status(404); return;
        }
        ObjectId pid = new ObjectId(req.propertyObjId);
        if (!propertyDAO.existsById(pid)) {
            ctx.result("Property not found"); ctx.status(404); return;
        }

        Date now = new Date();
        ObjectId listingId = listingDAO.insertListing(pid, now);
        pricingDAO.insertPrice(pid, req.price, now);
        ctx.result("Listing created: " + listingId.toHexString());
        ctx.status(201);
    }

    public void seedListings(Context ctx) {
        List<Document> sampleProps = propertyDAO.sampleProperties(1000);
        List<Document> newListings = new ArrayList<>();
        List<Document> newPrices = new ArrayList<>();
        List<ObjectId> listedPids = new ArrayList<>();
        Date now = new Date();

        for (Document prop : sampleProps) {
            ObjectId propId = prop.getObjectId("_id");
            Long purchasePrice = prop.getLong("purchase_price");
            if (purchasePrice == null || purchasePrice <= 0) continue;

            newListings.add(new Document()
                    .append("pid", propId)
                    .append("is_discounted", false)
                    .append("date_added", now));
            newPrices.add(new Document()
                    .append("pid", propId)
                    .append("updated_price", purchasePrice * 1.2)
                    .append("date", now));
            listedPids.add(propId);
        }

        listingDAO.insertMany(newListings);
        pricingDAO.insertMany(newPrices);
        propertyDAO.markForSale(listedPids);
        ctx.result("Seeded " + newListings.size() + " listings");
        ctx.status(201);
    }

    public void getAllListings(Context ctx) {
        List<Document> docs = listingDAO.findAll();
        if (docs.isEmpty()) {
            ctx.html(errorHtml("No listings found")); ctx.status(404); return;
        }
        List<ObjectId> pids = docs.stream()
                .map(d -> d.getObjectId("pid"))
                .filter(p -> p != null)
                .collect(Collectors.toList());
        Map<ObjectId, Double> prices = pricingDAO.getLatestPrices(pids);
        List<Listing> listings = docs.stream().map(d -> toListing(d, prices)).collect(Collectors.toList());
        ctx.html(listingTableHtml("All Listings", listings));
        ctx.status(200);
    }

    public void getListingById(Context ctx, String id) {
        Optional<Document> doc = listingDAO.findById(id);
        if (doc.isEmpty()) {
            ctx.html(errorHtml("Listing not found")); ctx.status(404); return;
        }
        Document d = doc.get();
        ObjectId pid = d.getObjectId("pid");
        double latestPrice = pid != null ? pricingDAO.getLatestPrice(pid) : 0;
        List<PriceEntry> history = pid != null ? pricingDAO.getPriceHistory(pid) : List.of();
        ctx.html(listingDetailHtml(toListing(d, latestPrice), history));
        ctx.status(200);
    }

    public void addPriceUpdate(Context ctx, String listingId) {
        double price = ctx.bodyValidator(PriceUpdateRequest.class)
                .check(r -> r.price > 0, "price must be positive")
                .get().price;

        Optional<Document> doc = listingDAO.findById(listingId);
        if (doc.isEmpty()) {
            ctx.result("Listing not found"); ctx.status(404); return;
        }
        ObjectId listingOid = doc.get().getObjectId("_id");
        ObjectId pid = doc.get().getObjectId("pid");

        double current = pid != null ? pricingDAO.getLatestPrice(pid) : 0;
        if (current > 0 && price < current) {
            listingDAO.setDiscounted(listingOid, true);
        }
        pricingDAO.insertPrice(pid, price, new Date());
        ctx.result("Price updated");
        ctx.status(201);
    }

    private static Listing toListing(Document d, Map<ObjectId, Double> prices) {
        ObjectId pid = d.getObjectId("pid");
        double price = pid != null ? prices.getOrDefault(pid, 0.0) : 0.0;
        return toListing(d, price);
    }

    private static Listing toListing(Document d, double latestPrice) {
        String id = d.getObjectId("_id").toHexString();
        ObjectId pid = d.getObjectId("pid");
        boolean discounted = Boolean.TRUE.equals(d.getBoolean("is_discounted"));
        Date dateAdded = d.getDate("date_added");
        return new Listing(
                id,
                pid != null ? pid.toHexString() : null,
                discounted,
                dateAdded != null ? dateAdded.toString() : null,
                latestPrice);
    }

    // ── HTML helpers ──────────────────────────────────────────────────────────

    private static String listingTableHtml(String title, List<Listing> listings) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body><h2>").append(title).append("</h2>");
        sb.append("<table border='1'><tr>")
                .append("<th>Listing ID</th>")
                .append("<th>Property ID</th>")
                .append("<th>Discounted</th>")
                .append("<th>Date Added</th>")
                .append("<th>Latest Price</th>")
                .append("</tr>");
        for (Listing l : listings) {
            sb.append("<tr>")
                    .append("<td>").append(l.listingId).append("</td>")
                    .append("<td>").append(l.propertyObjId).append("</td>")
                    .append("<td>").append(l.isDiscounted).append("</td>")
                    .append("<td>").append(l.dateAdded).append("</td>")
                    .append("<td>$").append(String.format("%.0f", l.latestPrice)).append("</td>")
                    .append("</tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private static String listingDetailHtml(Listing l, List<PriceEntry> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body>");
        sb.append("<h2>Listing ").append(l.listingId).append("</h2>");
        sb.append("<p><b>Property:</b> ").append(l.propertyObjId).append("</p>");
        sb.append("<p><b>Discounted:</b> ").append(l.isDiscounted).append("</p>");
        sb.append("<p><b>Listed:</b> ").append(l.dateAdded).append("</p>");
        sb.append("<h3>Price History</h3>");
        sb.append("<table border='1'><tr><th>Date</th><th>Price</th></tr>");
        for (PriceEntry e : history) {
            sb.append("<tr><td>").append(e.date).append("</td>")
                    .append("<td>$").append(String.format("%.0f", e.price)).append("</td></tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private static String errorHtml(String msg) {
        return "<html><body><p>" + msg + "</p></body></html>";
    }

    public static class PriceUpdateRequest {
        public double price;
    }

    public static class CreateListingRequest {
        public String propertyObjId;
        public double price;
    }
}
