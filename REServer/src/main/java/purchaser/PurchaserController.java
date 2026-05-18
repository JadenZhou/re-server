package purchaser;

import io.javalin.http.Context;
import stats.PostcodeStats;
import web.Html;

import java.util.List;
import java.util.Optional;

public class PurchaserController {

    private final PurchaserDAO dao;
    private final PostcodeStats postcodeStats;

    public PurchaserController(PurchaserDAO dao, PostcodeStats postcodeStats) {
        this.dao = dao;
        this.postcodeStats = postcodeStats;
    }

    public void createPurchaser(Context ctx) {
        CreateRequest req = ctx.bodyValidator(CreateRequest.class)
                .check(r -> r.name != null && !r.name.isBlank(), "name is required")
                .check(r -> r.email != null && !r.email.isBlank(), "email is required")
                .check(r -> r.postcodes == null || r.postcodes.size() <= PurchaserDAO.MAX_POSTCODES,
                        "at most 5 postcodes allowed")
                .get();

        String id = dao.createPurchaser(req.name, req.email, req.postcodes);
        if (id == null) {
            ctx.result("Invalid purchaser: postcodes must be NSW (max 5)");
            ctx.status(400);
            return;
        }
        ctx.json(new IdResponse(id));
        ctx.status(201);
    }

    public void getAllPurchasers(Context ctx) {
        List<Purchaser> purchasers = dao.getAllPurchasers();
        if (purchasers.isEmpty()) {
            ctx.html(errorHtml("No purchasers found"));
            ctx.status(404);
        } else {
            ctx.html(purchaserTableHtml("All Purchasers", purchasers));
            ctx.status(200);
        }
    }

    public void getPurchaserById(Context ctx, String id) {
        Optional<Purchaser> p = dao.getPurchaserById(id);
        if (p.isEmpty()) {
            ctx.html(errorHtml("Purchaser not found"));
            ctx.status(404);
            return;
        }
        ctx.html(purchaserDetailHtml(p.get()));
        ctx.status(200);
    }

    public void getPurchasersByPostcode(Context ctx, String postcode) {
        List<Purchaser> list = dao.getPurchasersByPostcode(postcode);
        if (list.isEmpty()) {
            ctx.html(errorHtml("No purchasers interested in postcode " + postcode));
            ctx.status(404);
        } else {
            // Searching purchasers by postcode is also a signal of interest
            // in that postcode (someone asking "who watches this area?").
            postcodeStats.incrementSearch(postcode);
            ctx.html(purchaserTableHtml("Purchasers interested in " + postcode, list));
            ctx.status(200);
        }
    }

    public void addInterest(Context ctx, String purchaserId) {
        InterestRequest req = ctx.bodyValidator(InterestRequest.class)
                .check(r -> r.postcode != null && !r.postcode.isBlank(), "postcode required")
                .get();

        PurchaserDAO.AddResult r = dao.addInterest(purchaserId, req.postcode);
        switch (r) {
            case OK:
                ctx.result("Interest added"); ctx.status(201); break;
            case DUPLICATE:
                ctx.result("Already interested in postcode"); ctx.status(409); break;
            case LIMIT_REACHED:
                ctx.result("Max 5 postcodes per purchaser"); ctx.status(409); break;
            case INVALID_POSTCODE:
                ctx.result("Postcode is not a valid NSW postcode"); ctx.status(400); break;
            case NOT_FOUND:
                ctx.result("Purchaser not found"); ctx.status(404); break;
        }
    }

    public void removeInterest(Context ctx, String purchaserId, String postcode) {
        if (dao.removeInterest(purchaserId, postcode)) {
            ctx.result("Interest removed");
            ctx.status(200);
        } else {
            ctx.result("Purchaser or postcode not found");
            ctx.status(404);
        }
    }

    public void seedPurchasers(Context ctx) {
        String countParam = ctx.queryParam("count");
        int count = 10000;
        if (countParam != null) {
            try { count = Math.max(1, Math.min(100000, Integer.parseInt(countParam))); }
            catch (NumberFormatException ignored) {}
        }
        int inserted = dao.seedPurchasers(count);
        ctx.result("Seeded " + inserted + " purchasers");
        ctx.status(201);
    }

    // ── HTML helpers ──────────────────────────────────────────────────────────

    private static String purchaserTableHtml(String title, List<Purchaser> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><title>").append(Html.escape(title)).append("</title></head><body>");
        sb.append("<h1>").append(Html.escape(title)).append("</h1>");
        sb.append("<table border='1' cellpadding='6' cellspacing='0'>");
        sb.append("<tr><th>Purchaser ID</th><th>Name</th><th>Email</th><th>Postcodes</th></tr>");
        for (Purchaser p : list) {
            sb.append("<tr>")
                    .append("<td>").append(p.purchaserId).append("</td>")
                    .append("<td>").append(Html.escape(p.name)).append("</td>")
                    .append("<td>").append(Html.escape(p.email)).append("</td>")
                    .append("<td>").append(String.join(", ", p.postcodes)).append("</td>")
                    .append("</tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private static String purchaserDetailHtml(Purchaser p) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><body>");
        sb.append("<h1>Purchaser ").append(p.purchaserId).append("</h1>");
        sb.append("<p><b>Name:</b> ").append(Html.escape(p.name)).append("</p>");
        sb.append("<p><b>Email:</b> ").append(Html.escape(p.email)).append("</p>");
        sb.append("<h3>Postcodes of interest</h3>");
        if (p.postcodes.isEmpty()) {
            sb.append("<p>None</p>");
        } else {
            sb.append("<ul>");
            for (String pc : p.postcodes) sb.append("<li>").append(pc).append("</li>");
            sb.append("</ul>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String errorHtml(String msg) {
        return Html.errorPage(msg);
    }

    public static class CreateRequest {
        public String name;
        public String email;
        public List<String> postcodes;
    }

    public static class InterestRequest {
        public String postcode;
    }

    public static class IdResponse {
        public String purchaserId;
        public IdResponse(String purchaserId) { this.purchaserId = purchaserId; }
    }
}
