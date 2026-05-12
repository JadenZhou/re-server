package app;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import property.PropertyDAO;
import property.PropertyController;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class REServer {
        private static final Logger LOG = LoggerFactory.getLogger(REServer.class);

        public static void main(String[] args) {

            // in memory test data store
            var properties = new PropertyDAO();

            // API implementation
            PropertyController propertyHandler = new PropertyController(properties);

            // start Javalin on port 7070
            var app = Javalin.create()
                    .get("/", ctx -> ctx.result("Real Estate server is running"))
                    .start(7070);

            // configure endpoint handlers to process HTTP requests
            JavalinConfig config = new JavalinConfig();
            config.router.apiBuilder(() -> {
                // Property records are immutable hence no PUT and DELETE

                // return a property by property ID
                app.get("/property/{propertyID}", ctx -> {
                    propertyHandler.getPropertyByID(ctx, ctx.pathParam("propertyID"));
                });
                // get all property records - could be big!
                app.get("/property", ctx -> {
                    propertyHandler.getAllProperties(ctx);
                });
                // create a new property record
                app.post("/property", ctx -> {
                    propertyHandler.createProperty(ctx);
                });
                // Get all properties for a specified postcode
                app.get("/property/postcode/{postcode}", ctx -> {
                    propertyHandler.findPropertyByPostCode(ctx, ctx.pathParam("postcode"));
                });
            });


        }
}


