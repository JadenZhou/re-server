package app;

import io.javalin.Javalin;
import io.javalin.http.Context;
import listing.Listing;
import listing.ListingDAO;
import listing.PriceEntry;
import property.Property;
import property.PropertyDAO;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Property microservice. Owns: postcodes, properties, listings, listing_prices.
 *
 * JSON-only internal API consumed by the gateway:
 *   GET   /property                            ?minPrice & ?maxPrice optional
 *   GET   /property/{id}
 *   POST  /property                            body = Property JSON
 *   GET   /property/postcode/{pc}
 *   GET   /property/for-sale                   internal: for notifier orchestration
 *   GET   /listing
 *   GET   /listing/{id}                        + ?withHistory=true → also include price history
 *   POST  /listing                             body = {propertyId, price}
 *   POST  /listing/{id}/price                  body = {price}
 *   POST  /listing/seed
 *
 * Default port 7071; override with PROPERTY_PORT.
 */
public class PropertyServer {

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PROPERTY_PORT", "7071"));
        PropertyDAO props = new PropertyDAO();
        ListingDAO listings = new ListingDAO();

        Javalin app = Javalin.create()
                .get("/", ctx -> ctx.result("property-server up"))
                .start(port);

        app.get("/property", ctx -> getProperties(ctx, props));
        app.post("/property", ctx -> createProperty(ctx, props));
        app.get("/property/{id}", ctx -> {
            Optional<Property> p = props.getPropertyById(ctx.pathParam("id"));
            if (p.isPresent()) ctx.json(p.get()); else ctx.status(404).result("Property not found");
        });
        app.get("/property/postcode/{pc}", ctx -> {
            List<Property> list = props.getPropertiesByPostCode(ctx.pathParam("pc"));
            ctx.json(list);
        });
        app.get("/internal/for-sale", ctx -> ctx.json(props.getForSaleWithLatestPrice()));

        app.get("/listing", ctx -> ctx.json(listings.getAllListings()));
        app.post("/listing", ctx -> createListing(ctx, listings));
        app.get("/listing/{id}", ctx -> {
            Optional<Listing> l = listings.getListingById(ctx.pathParam("id"));
            if (l.isEmpty()) { ctx.status(404).result("Listing not found"); return; }
            if ("true".equalsIgnoreCase(ctx.queryParam("withHistory"))) {
                List<PriceEntry> history = listings.getPriceHistory(ctx.pathParam("id"));
                ctx.json(Map.of("listing", l.get(), "history", history));
            } else {
                ctx.json(l.get());
            }
        });
        app.post("/listing/{id}/price", ctx -> addPriceUpdate(ctx, listings));
        app.post("/listing/seed", ctx -> {
            int n = listings.seedListings();
            ctx.status(201).json(Map.of("seeded", n));
        });
    }

    // ── handlers ──────────────────────────────────────────────────────────────

    private static void getProperties(Context ctx, PropertyDAO dao) {
        String min = ctx.queryParam("minPrice");
        String max = ctx.queryParam("maxPrice");
        if (min != null || max != null) {
            long lo = (min != null) ? Long.parseLong(min) : Long.MIN_VALUE;
            long hi = (max != null) ? Long.parseLong(max) : Long.MAX_VALUE;
            ctx.json(dao.getPropertiesByPriceRange(lo, hi));
        } else {
            ctx.json(dao.getAllProperties());
        }
    }

    private static void createProperty(Context ctx, PropertyDAO dao) {
        Property body = ctx.bodyAsClass(Property.class);
        if (dao.newProperty(body)) ctx.status(201).json(body);
        else ctx.status(400).result("Failed to add property");
    }

    private static void createListing(Context ctx, ListingDAO dao) {
        CreateListingRequest req = ctx.bodyValidator(CreateListingRequest.class)
                .check(r -> r.propertyId != null && !r.propertyId.isEmpty(), "propertyId required")
                .check(r -> r.price > 0, "price must be positive")
                .get();
        Optional<String> id = dao.createListing(req.propertyId, req.price);
        if (id.isPresent()) ctx.status(201).json(Map.of("listingId", id.get()));
        else ctx.status(404).result("Property not found");
    }

    private static void addPriceUpdate(Context ctx, ListingDAO dao) {
        PriceUpdateRequest req = ctx.bodyValidator(PriceUpdateRequest.class)
                .check(r -> r.price > 0, "price must be positive")
                .get();
        if (dao.addPriceUpdate(ctx.pathParam("id"), req.price)) ctx.status(201).result("Price updated");
        else ctx.status(404).result("Listing not found");
    }

    public static class CreateListingRequest { public String propertyId; public double price; }
    public static class PriceUpdateRequest { public double price; }
}
