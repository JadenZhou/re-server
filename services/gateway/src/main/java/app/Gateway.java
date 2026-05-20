package app;

import client.ServiceClient;
import client.ServiceClient.Response;
import io.javalin.Javalin;
import io.javalin.http.Context;
import notify.Notifier;

import java.util.Map;

/**
 * API Gateway. Only client-facing service; exposes the same paths the
 * monolith did, fans out to property-server / purchaser-server / analytics-
 * server. The gateway has no database — orchestration logic lives here so
 * the downstream services stay single-purpose (bounded contexts).
 *
 * Default port 7070. Env:
 *   PROPERTY_URL    default http://localhost:7071
 *   PURCHASER_URL   default http://localhost:7072
 *   ANALYTICS_URL   default http://localhost:7073
 */
public class Gateway {

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("GATEWAY_PORT", "7070"));
        String propertyUrl  = System.getenv().getOrDefault("PROPERTY_URL",  "http://localhost:7071");
        String purchaserUrl = System.getenv().getOrDefault("PURCHASER_URL", "http://localhost:7072");
        String analyticsUrl = System.getenv().getOrDefault("ANALYTICS_URL", "http://localhost:7073");

        ServiceClient svc = new ServiceClient(propertyUrl, purchaserUrl, analyticsUrl);
        Notifier notifier = new Notifier(svc);

        Javalin app = Javalin.create()
                .get("/", ctx -> ctx.result("Real Estate gateway is running"))
                .start(port);

        registerPropertyRoutes(app, svc);
        registerListingRoutes(app, svc);
        registerPurchaserRoutes(app, svc);
        registerStatsRoutes(app, svc);
        app.get("/notify", ctx -> ctx.json(notifier.buildNotifications()));
    }

    // ── property: gateway fans out to property-server (record) + analytics (bump)

    private static void registerPropertyRoutes(Javalin app, ServiceClient svc) {
        app.get("/property", ctx -> {
            String qs = buildQueryString(ctx, "minPrice", "maxPrice");
            relay(ctx, svc.propertyGet("/property" + qs));
        });
        app.post("/property", ctx -> relay(ctx, svc.propertyPost("/property", rawBody(ctx))));
        app.get("/property/{propertyID}", ctx -> {
            String id = ctx.pathParam("propertyID");
            Response r = svc.propertyGet("/property/" + urlEncode(id));
            if (r.ok()) svc.bumpPropertyView(id);   // only bump on hit
            relay(ctx, r);
        });
        app.get("/property/postcode/{postcode}", ctx -> {
            String pc = ctx.pathParam("postcode");
            Response r = svc.propertyGet("/property/postcode/" + urlEncode(pc));
            if (r.ok() && hasItems(r)) svc.bumpPostcodeSearch(pc);
            relay(ctx, r);
        });
    }

    // ── listings: pure passthrough — single-service responsibility

    private static void registerListingRoutes(Javalin app, ServiceClient svc) {
        app.post("/listing", ctx -> relay(ctx, svc.propertyPost("/listing", rawBody(ctx))));
        app.post("/listing/seed", ctx -> relay(ctx, svc.propertyPost("/listing/seed", null)));
        app.get("/listing", ctx -> relay(ctx, svc.propertyGet("/listing")));
        app.get("/listing/{listingID}", ctx ->
                relay(ctx, svc.propertyGet("/listing/" + urlEncode(ctx.pathParam("listingID")) + "?withHistory=true")));
        app.post("/listing/{listingID}/price", ctx ->
                relay(ctx, svc.propertyPost("/listing/" + urlEncode(ctx.pathParam("listingID")) + "/price", rawBody(ctx))));
    }

    // ── purchasers: mostly passthrough; postcode-search bumps analytics

    private static void registerPurchaserRoutes(Javalin app, ServiceClient svc) {
        app.post("/purchaser", ctx -> relay(ctx, svc.purchaserPost("/purchaser", rawBody(ctx))));
        app.get("/purchaser", ctx -> relay(ctx, svc.purchaserGet("/purchaser")));
        app.get("/purchaser/{purchaserID}", ctx ->
                relay(ctx, svc.purchaserGet("/purchaser/" + urlEncode(ctx.pathParam("purchaserID")))));
        app.get("/purchaser/postcode/{postcode}", ctx -> {
            String pc = ctx.pathParam("postcode");
            Response r = svc.purchaserGet("/purchaser/postcode/" + urlEncode(pc));
            if (r.ok() && hasItems(r)) svc.bumpPostcodeSearch(pc);
            relay(ctx, r);
        });
        app.post("/purchaser/{purchaserID}/interest", ctx ->
                relay(ctx, svc.purchaserPost(
                        "/purchaser/" + urlEncode(ctx.pathParam("purchaserID")) + "/interest", rawBody(ctx))));
        app.delete("/purchaser/{purchaserID}/interest/{postcode}", ctx ->
                relay(ctx, svc.purchaserDelete(
                        "/purchaser/" + urlEncode(ctx.pathParam("purchaserID")) +
                        "/interest/" + urlEncode(ctx.pathParam("postcode")))));
        app.post("/purchaser/seed", ctx -> {
            String qs = buildQueryString(ctx, "count");
            relay(ctx, svc.purchaserPost("/purchaser/seed" + qs, null));
        });
    }

    // ── stats: analytics-only

    private static void registerStatsRoutes(Javalin app, ServiceClient svc) {
        app.get("/stats/postcode/{postcode}", ctx ->
                relay(ctx, svc.analyticsGet("/searches/postcode/" + urlEncode(ctx.pathParam("postcode")))));
        app.get("/stats/property/{propertyID}", ctx ->
                relay(ctx, svc.analyticsGet("/views/property/" + urlEncode(ctx.pathParam("propertyID")))));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void relay(Context ctx, Response r) {
        ctx.status(r.status());
        // Preserve JSON content-type when the downstream returned JSON.
        String body = r.body() == null ? "" : r.body();
        if (looksLikeJson(body)) ctx.contentType("application/json");
        ctx.result(body);
    }

    private static boolean looksLikeJson(String s) {
        if (s == null || s.isEmpty()) return false;
        char c = s.charAt(0);
        return c == '{' || c == '[';
    }

    /** Treat a JSON array body as "items present" if it's not "[]". */
    private static boolean hasItems(Response r) {
        String b = r.body();
        if (b == null) return false;
        String trimmed = b.trim();
        return !trimmed.equals("[]");
    }

    private static Object rawBody(Context ctx) {
        String b = ctx.body();
        return (b == null || b.isEmpty()) ? null : ctx.bodyAsClass(Map.class);
    }

    private static String buildQueryString(Context ctx, String... keys) {
        StringBuilder qs = new StringBuilder();
        for (String k : keys) {
            String v = ctx.queryParam(k);
            if (v == null) continue;
            qs.append(qs.length() == 0 ? '?' : '&').append(k).append('=').append(urlEncode(v));
        }
        return qs.toString();
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
