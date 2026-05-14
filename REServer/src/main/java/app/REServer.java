package app;

import io.javalin.Javalin;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.redoc.ReDocPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import listing.ListingController;
import listing.ListingDAO;
import notify.NotifyController;
import notify.NotifyDAO;
import notify.NotifyService;
import property.PropertyController;
import property.PropertyDAO;
import purchaser.PurchaserController;
import purchaser.PurchaserDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class REServer {

    private static final Logger LOG = LoggerFactory.getLogger(REServer.class);
    private static final int PORT = 7070;

    private REServer() { }

    public static void main(String[] args) {
        var properties = new PropertyDAO();
        PropertyController propertyHandler = new PropertyController(properties);

        var listings = new ListingDAO();
        ListingController listingHandler = new ListingController(listings);

        var purchasers = new PurchaserDAO();
        PurchaserController purchaserHandler = new PurchaserController(purchasers);

        NotifyController notifyHandler = new NotifyController(new NotifyDAO(), new NotifyService());

        var app = Javalin.create(config -> {
                    config.registerPlugin(new OpenApiPlugin(plugin ->
                            plugin.withDefinitionConfiguration((version, def) ->
                                    def.withInfo(info -> {
                                        info.setTitle("Real Estate Server API");
                                        info.setVersion("1.0");
                                        info.setDescription(
                                                "NSW property records, listings, purchasers, and notifications.");
                                    }))));
                    config.registerPlugin(new SwaggerPlugin());
                    config.registerPlugin(new ReDocPlugin());
                })
                .get("/", ctx -> ctx.result("Real Estate server is running"))
                .start(PORT);

        // Property — records are immutable; no PUT/DELETE.
        app.get("/property/{propertyID}",
                ctx -> propertyHandler.getPropertyByID(ctx, ctx.pathParam("propertyID")));
        app.get("/property", propertyHandler::getAllProperties);
        app.post("/property", propertyHandler::createProperty);
        app.get("/property/postcode/{postcode}",
                ctx -> propertyHandler.findPropertyByPostCode(ctx, ctx.pathParam("postcode")));

        // Listings
        app.post("/listing", listingHandler::createListing);
        app.post("/listing/seed", listingHandler::seedListings);
        app.get("/listing", listingHandler::getAllListings);
        app.get("/listing/{listingID}",
                ctx -> listingHandler.getListingById(ctx, ctx.pathParam("listingID")));
        app.post("/listing/{listingID}/price",
                ctx -> listingHandler.addPriceUpdate(ctx, ctx.pathParam("listingID")));

        // Purchasers
        app.post("/purchaser", purchaserHandler::createPurchaser);
        app.get("/purchaser", purchaserHandler::getAllPurchasers);
        app.get("/purchaser/{purchaserID}",
                ctx -> purchaserHandler.getPurchaserById(ctx, ctx.pathParam("purchaserID")));
        app.get("/purchaser/postcode/{postcode}",
                ctx -> purchaserHandler.getPurchasersByPostcode(ctx, ctx.pathParam("postcode")));
        app.post("/purchaser/{purchaserID}/interest",
                ctx -> purchaserHandler.addInterest(ctx, ctx.pathParam("purchaserID")));
        app.delete("/purchaser/{purchaserID}/interest/{postcode}",
                ctx -> purchaserHandler.removeInterest(
                        ctx, ctx.pathParam("purchaserID"), ctx.pathParam("postcode")));
        app.post("/purchaser/seed", purchaserHandler::seedPurchasers);

        // Notification report
        app.get("/notify", notifyHandler::notify);

        LOG.info("server_started port={}", PORT);
    }
}
