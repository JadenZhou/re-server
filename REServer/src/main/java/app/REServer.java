package app;

import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import listing.ListingController;
import listing.ListingDAO;
import listing.PricingDAO;
import notify.NotifyController;
import notify.NotifyDAO;
import notify.NotifyService;
import postcode_stats.PostcodeStatsDAO;
import property.PropertyController;
import property.PropertyDAO;
import purchase.PurchasesDAO;
import purchaser.PurchaserController;
import purchaser.PurchaserDAO;
import purchase.PurchasesDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class REServer {
  private static final Logger LOG = LoggerFactory.getLogger(REServer.class);

  public static void main(String[] args) {

    var postcodeStats = new PostcodeStatsDAO();
    var purchases = new PurchasesDAO();

    var properties = new PropertyDAO();
    PropertyController propertyHandler = new PropertyController(properties, postcodeStats);

    var listings = new ListingDAO();
    ListingController listingHandler = new ListingController(listings);

    var purchasers = new PurchaserDAO();
    PurchaserController purchaserHandler = new PurchaserController(purchasers);

    NotifyController notifyHandler = new NotifyController(new NotifyDAO(), new NotifyService());

    // start Javalin on port 7070
    var app = Javalin.create()
            .get("/", ctx -> ctx.result("Real Estate server is running"))
            .start(7070);

    // configure endpoint handlers to process HTTP requests
    JavalinConfig config = new JavalinConfig();
    config.router.apiBuilder(() -> {

      // Property Endpoints
      app.get("/property/{propertyID}", ctx -> {
        propertyHandler.getPropertyByID(ctx, ctx.pathParam("propertyID"));
      });
      app.get("/property", ctx -> {
        propertyHandler.getAllProperties(ctx);
      });
      app.post("/property", ctx -> {
        propertyHandler.createProperty(ctx);
      });
      app.get("/property/postcode/{postcode}", ctx -> {
        propertyHandler.findPropertyByPostCode(ctx, ctx.pathParam("postcode"));
      });

      // Listing endpoints
      app.post("/listing", ctx -> listingHandler.createListing(ctx));
      app.post("/listing/seed", ctx -> listingHandler.seedListings(ctx));
      app.get("/listing", ctx -> listingHandler.getAllListings(ctx));
      app.get("/listing/{listingID}", ctx -> listingHandler.getListingById(ctx, ctx.pathParam("listingID")));
      app.post("/listing/{listingID}/price", ctx -> listingHandler.addPriceUpdate(ctx, ctx.pathParam("listingID")));

      // Purchaser endpoints
      app.post("/purchaser", ctx -> purchaserHandler.createPurchaser(ctx));
      app.get("/purchaser", ctx -> purchaserHandler.getAllPurchasers(ctx));
      app.get("/purchaser/{purchaserID}", ctx -> purchaserHandler.getPurchaserById(ctx, ctx.pathParam("purchaserID")));
      app.get("/purchaser/postcode/{postcode}", ctx -> purchaserHandler.getPurchasersByPostcode(ctx, ctx.pathParam("postcode")));
      app.post("/purchaser/{purchaserID}/interest", ctx -> purchaserHandler.addInterest(ctx, ctx.pathParam("purchaserID")));
      app.delete("/purchaser/{purchaserID}/interest/{postcode}", ctx ->
              purchaserHandler.removeInterest(ctx, ctx.pathParam("purchaserID"), ctx.pathParam("postcode")));
      app.post("/purchaser/seed", ctx -> purchaserHandler.seedPurchasers(ctx));

      // Notification report — for each Buyer with watched postcodes,
      // list the for-sale properties in those postcodes (ID + price).
      app.get("/notify", ctx -> notifyHandler.notify(ctx));
    });


  }
}


