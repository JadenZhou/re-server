package app;

import io.javalin.Javalin;
import io.javalin.http.Context;
import purchaser.Purchaser;
import purchaser.PurchaserDAO;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Purchaser microservice. Owns accounts + postcode_interest + purchases.
 *
 * Internal JSON API consumed by the gateway:
 *   POST   /purchaser
 *   GET    /purchaser
 *   GET    /purchaser/{id}
 *   GET    /purchaser/postcode/{pc}
 *   POST   /purchaser/{id}/interest          body = {postcode}
 *   DELETE /purchaser/{id}/interest/{pc}
 *   POST   /purchaser/seed                   ?count=N
 *
 * Default port 7072; override with PURCHASER_PORT.
 */
public class PurchaserServer {

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PURCHASER_PORT", "7072"));
        PurchaserDAO dao = new PurchaserDAO();

        Javalin app = Javalin.create()
                .get("/", ctx -> ctx.result("purchaser-server up"))
                .start(port);

        app.post("/purchaser", ctx -> createPurchaser(ctx, dao));
        app.get("/purchaser", ctx -> ctx.json(dao.getAllPurchasers()));
        app.get("/purchaser/{id}", ctx -> {
            Optional<Purchaser> p = dao.getPurchaserById(ctx.pathParam("id"));
            if (p.isPresent()) ctx.json(p.get()); else ctx.status(404).result("Purchaser not found");
        });
        app.get("/purchaser/postcode/{pc}", ctx -> ctx.json(dao.getPurchasersByPostcode(ctx.pathParam("pc"))));
        app.post("/purchaser/{id}/interest", ctx -> addInterest(ctx, dao));
        app.delete("/purchaser/{id}/interest/{pc}", ctx -> {
            if (dao.removeInterest(ctx.pathParam("id"), ctx.pathParam("pc"))) ctx.result("Interest removed");
            else ctx.status(404).result("Purchaser or postcode not found");
        });
        app.post("/purchaser/seed", ctx -> {
            int count = parseCount(ctx.queryParam("count"));
            int n = dao.seedPurchasers(count);
            ctx.status(201).json(Map.of("seeded", n));
        });
    }

    private static void createPurchaser(Context ctx, PurchaserDAO dao) {
        CreateRequest req = ctx.bodyValidator(CreateRequest.class)
                .check(r -> r.name != null && !r.name.isBlank(), "name required")
                .check(r -> r.email != null && !r.email.isBlank(), "email required")
                .check(r -> r.postcodes == null || r.postcodes.size() <= PurchaserDAO.MAX_POSTCODES,
                        "at most 5 postcodes allowed")
                .get();
        String id = dao.createPurchaser(req.name, req.email, req.postcodes);
        if (id == null) ctx.status(400).result("Invalid purchaser: postcodes must be NSW (max 5)");
        else ctx.status(201).json(Map.of("purchaserId", id));
    }

    private static void addInterest(Context ctx, PurchaserDAO dao) {
        InterestRequest req = ctx.bodyValidator(InterestRequest.class)
                .check(r -> r.postcode != null && !r.postcode.isBlank(), "postcode required")
                .get();
        PurchaserDAO.AddResult r = dao.addInterest(ctx.pathParam("id"), req.postcode);
        switch (r) {
            case OK -> ctx.status(201).result("Interest added");
            case DUPLICATE -> ctx.status(409).result("Already interested in postcode");
            case LIMIT_REACHED -> ctx.status(409).result("Max 5 postcodes per purchaser");
            case INVALID_POSTCODE -> ctx.status(400).result("Postcode is not a valid NSW postcode");
            case NOT_FOUND -> ctx.status(404).result("Purchaser not found");
        }
    }

    private static int parseCount(String s) {
        if (s == null) return 10000;
        try { return Math.max(1, Math.min(100000, Integer.parseInt(s))); }
        catch (NumberFormatException ignored) { return 10000; }
    }

    public static class CreateRequest {
        public String name;
        public String email;
        public List<String> postcodes;
    }
    public static class InterestRequest { public String postcode; }
}
