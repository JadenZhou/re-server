package app;

import analytics.AnalyticsDAO;
import io.javalin.Javalin;

import java.util.Map;

/**
 * Analytics microservice. Owns the property_views + postcode_searches tables.
 *
 * Internal JSON API consumed by the gateway:
 *   GET  /views/property/{id}        -> {"propertyId": "...", "count": N}
 *   POST /views/property/{id}        -> {"propertyId": "...", "count": N (after bump)}
 *   GET  /searches/postcode/{pc}     -> {"postcode": "...", "count": N}
 *   POST /searches/postcode/{pc}     -> {"postcode": "...", "count": N (after bump)}
 *
 * Default port 7073; override with ANALYTICS_PORT.
 */
public class AnalyticsServer {

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("ANALYTICS_PORT", "7073"));
        AnalyticsDAO dao = new AnalyticsDAO();

        Javalin app = Javalin.create()
                .get("/", ctx -> ctx.result("analytics-server up"))
                .start(port);

        app.get("/views/property/{id}", ctx -> {
            String id = ctx.pathParam("id");
            ctx.json(Map.of("propertyId", id, "count", dao.getPropertyViews(id)));
        });
        app.post("/views/property/{id}", ctx -> {
            String id = ctx.pathParam("id");
            ctx.json(Map.of("propertyId", id, "count", dao.incrementPropertyView(id)));
        });
        app.get("/searches/postcode/{pc}", ctx -> {
            String pc = ctx.pathParam("pc");
            ctx.json(Map.of("postcode", pc, "count", dao.getPostcodeSearches(pc)));
        });
        app.post("/searches/postcode/{pc}", ctx -> {
            String pc = ctx.pathParam("pc");
            ctx.json(Map.of("postcode", pc, "count", dao.incrementPostcodeSearch(pc)));
        });
    }
}
