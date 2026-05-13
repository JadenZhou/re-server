package app;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import listing.ListingController;
import listing.ListingDAO;
import property.PropertyController;
import property.PropertyDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class REServer {
        private static final Logger LOG = LoggerFactory.getLogger(REServer.class);

        public static void main(String[] args) {

            var properties = new PropertyDAO();
            PropertyController propertyHandler = new PropertyController(properties);

            var listings = new ListingDAO();
            ListingController listingHandler = new ListingController(listings);

            // start Javalin on port 7070
            var app = Javalin.create()
                    .get("/", ctx -> ctx.result("Real Estate server is running"))
                    .start(7070);

            // configure endpoint handlers to process HTTP requests
            JavalinConfig config = new JavalinConfig();
            config.router.apiBuilder(() -> {
                // Property records are immutable hence no PUT and DELETE

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
                app.post("/listing/seed", ctx -> listingHandler.seedListings(ctx));
                app.get("/listing", ctx -> listingHandler.getAllListings(ctx));
                app.get("/listing/{listingID}", ctx -> listingHandler.getListingById(ctx, ctx.pathParam("listingID")));
                app.post("/listing/{listingID}/price", ctx -> listingHandler.addPriceUpdate(ctx, ctx.pathParam("listingID")));
            });


        }
}


