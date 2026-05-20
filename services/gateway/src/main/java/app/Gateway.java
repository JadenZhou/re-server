package app;

import client.ServiceClient;
import client.ServiceClient.Response;
import io.javalin.Javalin;
import io.javalin.http.Context;
import notify.Notifier;
import web.Html;
import web.Renderers;

import java.util.List;
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
        app.get("/notify", ctx -> {
            List<Map<String, Object>> notes = notifier.buildNotifications();
            if (Html.wantsHtml(ctx)) ctx.contentType("text/html").result(Renderers.notify(notes));
            else ctx.json(notes);
        });
    }

    // ── property: gateway fans out to property-server (record) + analytics (bump)

    private static void registerPropertyRoutes(Javalin app, ServiceClient svc) {
        app.get("/property", ctx -> {
            String qs = buildQueryString(ctx, "minPrice", "maxPrice");
            Response r = svc.propertyGet("/property" + qs);
            respondHtmlOrJson(ctx, r, body -> Renderers.propertyList("All Properties", body, svc));
        });
        app.post("/property", ctx -> relay(ctx, svc.propertyPost("/property", rawBody(ctx))));
        app.get("/property/{propertyID}", ctx -> {
            String id = ctx.pathParam("propertyID");
            Response r = svc.propertyGet("/property/" + urlEncode(id));
            if (r.ok()) svc.bumpPropertyView(id);   // only bump on hit
            respondHtmlOrJson(ctx, r, body -> Renderers.propertyDetail(id, body, svc));
        });
        app.get("/property/postcode/{postcode}", ctx -> {
            String pc = ctx.pathParam("postcode");
            Response r = svc.propertyGet("/property/postcode/" + urlEncode(pc));
            if (r.ok() && hasItems(r)) svc.bumpPostcodeSearch(pc);
            respondHtmlOrJson(ctx, r, body -> Renderers.propertyList("Properties in Postcode " + pc, body, svc));
        });
    }

    // ── listings: pure passthrough — single-service responsibility

    private static void registerListingRoutes(Javalin app, ServiceClient svc) {
        app.post("/listing", ctx -> relay(ctx, svc.propertyPost("/listing", rawBody(ctx))));
        app.post("/listing/seed", ctx -> relay(ctx, svc.propertyPost("/listing/seed", null)));
        app.get("/listing", ctx -> {
            Response r = svc.propertyGet("/listing");
            respondHtmlOrJson(ctx, r, body -> Renderers.listingList(body, svc));
        });
        app.get("/listing/{listingID}", ctx -> {
            String id = ctx.pathParam("listingID");
            Response r = svc.propertyGet("/listing/" + urlEncode(id) + "?withHistory=true");
            respondHtmlOrJson(ctx, r, body -> Renderers.listingDetail(id, body, svc));
        });
        app.post("/listing/{listingID}/price", ctx ->
                relay(ctx, svc.propertyPost("/listing/" + urlEncode(ctx.pathParam("listingID")) + "/price", rawBody(ctx))));
    }

    // ── purchasers: mostly passthrough; postcode-search bumps analytics

    private static void registerPurchaserRoutes(Javalin app, ServiceClient svc) {
        app.post("/purchaser", ctx -> relay(ctx, svc.purchaserPost("/purchaser", rawBody(ctx))));
        app.get("/purchaser", ctx -> {
            Response r = svc.purchaserGet("/purchaser");
            respondHtmlOrJson(ctx, r, body -> Renderers.purchaserList("All Purchasers", body, svc));
        });
        app.get("/purchaser/{purchaserID}", ctx -> {
            String id = ctx.pathParam("purchaserID");
            Response r = svc.purchaserGet("/purchaser/" + urlEncode(id));
            respondHtmlOrJson(ctx, r, body -> Renderers.purchaserDetail(id, body, svc));
        });
        app.get("/purchaser/postcode/{postcode}", ctx -> {
            String pc = ctx.pathParam("postcode");
            Response r = svc.purchaserGet("/purchaser/postcode/" + urlEncode(pc));
            if (r.ok() && hasItems(r)) svc.bumpPostcodeSearch(pc);
            respondHtmlOrJson(ctx, r, body -> Renderers.purchaserList("Purchasers interested in " + pc, body, svc));
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

    /**
     * Content-negotiated response: if the caller wants HTML and the downstream
     * call succeeded, render via the provided renderer; otherwise relay the
     * JSON (and any non-2xx status) verbatim.
     */
    private static void respondHtmlOrJson(Context ctx, Response r, java.util.function.Function<String, String> htmlRenderer) {
        if (r.ok() && Html.wantsHtml(ctx)) {
            ctx.contentType("text/html").status(r.status()).result(htmlRenderer.apply(r.body()));
        } else if (!r.ok() && Html.wantsHtml(ctx)) {
            ctx.contentType("text/html").status(r.status()).result(Html.errorPage(r.body()));
        } else {
            relay(ctx, r);
        }
    }

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
