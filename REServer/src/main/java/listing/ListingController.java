package listing;

import app.Html;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

import java.util.List;
import java.util.Optional;

public class ListingController {

    private static final int TABLE_HTML_INITIAL = 1024;
    private static final int DETAIL_HTML_INITIAL = 512;

    private final ListingDAO dao;

    public ListingController(ListingDAO dao) {
        this.dao = dao;
    }

    @OpenApi(
            path = "/listing",
            methods = HttpMethod.POST,
            summary = "Create a listing for an existing property",
            tags = {"Listing"},
            requestBody = @OpenApiRequestBody(
                    content = @OpenApiContent(from = CreateListingRequest.class), required = true),
            responses = {
                    @OpenApiResponse(status = "201", description = "Listing created"),
                    @OpenApiResponse(status = "404", description = "Property not found")
            })
    public void createListing(Context ctx) {
        CreateListingRequest req = ctx.bodyValidator(CreateListingRequest.class)
                .check(r -> r.propertyObjId != null && !r.propertyObjId.isEmpty(), "propertyObjId is required")
                .check(r -> r.price > 0, "price must be positive")
                .get();

        dao.createListing(req.propertyObjId, req.price)
                .ifPresentOrElse(
                        id -> {
                            ctx.result("Listing created: " + id);
                            ctx.status(201);
                        },
                        () -> {
                            ctx.result("Property not found");
                            ctx.status(404);
                        });
    }

    @OpenApi(
            path = "/listing/seed",
            methods = HttpMethod.POST,
            summary = "Seed 1000 synthetic listings at +20% of last sale price",
            tags = {"Listing"},
            responses = @OpenApiResponse(status = "201", description = "Listings seeded"))
    public void seedListings(Context ctx) {
        int count = dao.seedListings();
        ctx.result("Seeded " + count + " listings");
        ctx.status(201);
    }

    @OpenApi(
            path = "/listing",
            methods = HttpMethod.GET,
            summary = "List all listings (capped)",
            tags = {"Listing"},
            responses = {
                    @OpenApiResponse(status = "200", description = "Listings"),
                    @OpenApiResponse(status = "404", description = "No listings")
            })
    public void getAllListings(Context ctx) {
        List<Listing> listings = dao.getAllListings();
        if (listings.isEmpty()) {
            ctx.html(Html.errorPage("Error", "No listings found"));
            ctx.status(404);
        } else {
            ctx.html(listingTableHtml("All Listings", listings));
            ctx.status(200);
        }
    }

    @OpenApi(
            path = "/listing/{listingID}",
            methods = HttpMethod.GET,
            summary = "Get a listing with its full price history",
            tags = {"Listing"},
            pathParams = @OpenApiParam(name = "listingID", required = true),
            responses = {
                    @OpenApiResponse(status = "200", description = "Listing found"),
                    @OpenApiResponse(status = "404", description = "Listing not found")
            })
    public void getListingById(Context ctx, String id) {
        Optional<Listing> listing = dao.getListingById(id);
        if (listing.isEmpty()) {
            ctx.html(Html.errorPage("Error", "Listing not found"));
            ctx.status(404);
            return;
        }
        List<PriceEntry> history = dao.getPriceHistory(id);
        ctx.html(listingDetailHtml(listing.get(), history));
        ctx.status(200);
    }

    @OpenApi(
            path = "/listing/{listingID}/price",
            methods = HttpMethod.POST,
            summary = "Add a new price entry; marks listing discounted if below latest",
            tags = {"Listing"},
            pathParams = @OpenApiParam(name = "listingID", required = true),
            requestBody = @OpenApiRequestBody(
                    content = @OpenApiContent(from = PriceUpdateRequest.class), required = true),
            responses = {
                    @OpenApiResponse(status = "201", description = "Price updated"),
                    @OpenApiResponse(status = "404", description = "Listing not found")
            })
    public void addPriceUpdate(Context ctx, String listingId) {
        double price = ctx.bodyValidator(PriceUpdateRequest.class)
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

    private static String listingTableHtml(String title, List<Listing> listings) {
        StringBuilder sb = new StringBuilder(TABLE_HTML_INITIAL);
        sb.append("<html><body><h2>").append(Html.escape(title)).append("</h2>");
        sb.append("<table border='1'><tr>")
          .append("<th>Listing ID</th><th>Property ID</th><th>Discounted</th>")
          .append("<th>Date Added</th><th>Latest Price</th></tr>");
        for (Listing l : listings) {
            sb.append("<tr><td>").append(l.listingId)
              .append("</td><td>").append(l.propertyObjId)
              .append("</td><td>").append(l.isDiscounted)
              .append("</td><td>").append(l.dateAdded)
              .append("</td><td>$").append(String.format("%.0f", l.latestPrice))
              .append("</td></tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private static String listingDetailHtml(Listing l, List<PriceEntry> history) {
        StringBuilder sb = new StringBuilder(DETAIL_HTML_INITIAL);
        sb.append("<html><body><h2>Listing ").append(l.listingId).append("</h2>")
          .append("<p><b>Property:</b> ").append(l.propertyObjId).append("</p>")
          .append("<p><b>Discounted:</b> ").append(l.isDiscounted).append("</p>")
          .append("<p><b>Listed:</b> ").append(l.dateAdded).append("</p>")
          .append("<h3>Price History</h3>")
          .append("<table border='1'><tr><th>Date</th><th>Price</th></tr>");
        for (PriceEntry e : history) {
            sb.append("<tr><td>").append(e.date)
              .append("</td><td>$").append(String.format("%.0f", e.price))
              .append("</td></tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    public static class PriceUpdateRequest {
        public double price;
    }

    public static class CreateListingRequest {
        public String propertyObjId;
        public double price;
    }
}
