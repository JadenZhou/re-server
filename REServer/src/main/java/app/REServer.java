package app;

import io.javalin.Javalin;
import listing.ListingController;
import listing.ListingDAO;
import notify.NotifyController;
import notify.NotifyDAO;
import notify.NotifyService;
import property.PropertyController;
import property.PropertyDAO;
import purchaser.PurchaserController;
import purchaser.PurchaserDAO;
import stats.MongoPostcodeStats;
import stats.PostcodeStats;

public class REServer {

    public static void main(String[] args) {
        PostcodeStats postcodeStats = new MongoPostcodeStats();

        PropertyController propertyHandler = new PropertyController(new PropertyDAO(), postcodeStats);
        ListingController listingHandler = new ListingController(new ListingDAO());
        PurchaserController purchaserHandler = new PurchaserController(new PurchaserDAO(), postcodeStats);
        NotifyController notifyHandler = new NotifyController(new NotifyDAO(), new NotifyService());

        Javalin app = Javalin.create()
                .get("/", ctx -> ctx.result("Real Estate server is running"))
                .start(7070);

        registerPropertyRoutes(app, propertyHandler);
        registerListingRoutes(app, listingHandler);
        registerPurchaserRoutes(app, purchaserHandler);
        app.get("/notify", notifyHandler::notify);
        app.get("/stats/postcode/{postcode}", ctx -> {
            String pc = ctx.pathParam("postcode");
            ctx.json(java.util.Map.of("postcode", pc, "searchCount", postcodeStats.getSearchCount(pc)));
        });
    }

    // Property records are immutable, so no PUT or DELETE.
    private static void registerPropertyRoutes(Javalin app, PropertyController h) {
        app.get("/property", h::getAllProperties);
        app.post("/property", h::createProperty);
        app.get("/property/{propertyID}", ctx -> h.getPropertyByID(ctx, ctx.pathParam("propertyID")));
        app.get("/property/postcode/{postcode}", ctx -> h.findPropertyByPostCode(ctx, ctx.pathParam("postcode")));
    }

    private static void registerListingRoutes(Javalin app, ListingController h) {
        app.post("/listing", h::createListing);
        app.post("/listing/seed", h::seedListings);
        app.get("/listing", h::getAllListings);
        app.get("/listing/{listingID}", ctx -> h.getListingById(ctx, ctx.pathParam("listingID")));
        app.post("/listing/{listingID}/price", ctx -> h.addPriceUpdate(ctx, ctx.pathParam("listingID")));
    }

    private static void registerPurchaserRoutes(Javalin app, PurchaserController h) {
        app.post("/purchaser", h::createPurchaser);
        app.get("/purchaser", h::getAllPurchasers);
        app.post("/purchaser/seed", h::seedPurchasers);
        app.get("/purchaser/{purchaserID}", ctx -> h.getPurchaserById(ctx, ctx.pathParam("purchaserID")));
        app.get("/purchaser/postcode/{postcode}", ctx -> h.getPurchasersByPostcode(ctx, ctx.pathParam("postcode")));
        app.post("/purchaser/{purchaserID}/interest", ctx -> h.addInterest(ctx, ctx.pathParam("purchaserID")));
        app.delete("/purchaser/{purchaserID}/interest/{postcode}", ctx ->
                h.removeInterest(ctx, ctx.pathParam("purchaserID"), ctx.pathParam("postcode")));
    }
}


