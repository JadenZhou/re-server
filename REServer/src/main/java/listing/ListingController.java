package listing;

import io.javalin.http.Context;

import java.util.List;
import java.util.Optional;

public class ListingController {

    private final ListingDAO dao;

    public ListingController(ListingDAO dao) {
        this.dao = dao;
    }

    public void seedListings(Context ctx) {
        int count = dao.seedListings();
        ctx.result("Seeded " + count + " listings");
        ctx.status(201);
    }

    public void getAllListings(Context ctx) {
        List<Listing> listings = dao.getAllListings();
        if (listings.isEmpty()) {
            ctx.html(errorHtml("No listings found"));
            ctx.status(404);
        } else {
            ctx.html(listingTableHtml("All Listings", listings));
            ctx.status(200);
        }
    }

    public void getListingById(Context ctx, String id) {
        Optional<Listing> listing = dao.getListingById(id);
        if (listing.isEmpty()) {
            ctx.html(errorHtml("Listing not found"));
            ctx.status(404);
            return;
        }
        List<PriceEntry> history = dao.getPriceHistory(id);
        ctx.html(listingDetailHtml(listing.get(), history));
        ctx.status(200);
    }

    public void addPriceUpdate(Context ctx, String listingId) {
        Double price = ctx.bodyValidator(PriceUpdateRequest.class)
                .check(r -> r.price > 0, "price must be positive")
                .get().price;

        if (dao.addPriceUpdate(listingId, price)) {
            ctx.result("Price updated");
            ctx.status(201);
        } else {
            ctx.result("Listing not found");
            ctx.status(404);
        }
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
}
