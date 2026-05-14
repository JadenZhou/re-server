package purchaser;

import app.Html;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

import java.util.List;
import java.util.Optional;

public class PurchaserController {

    private static final int TABLE_HTML_INITIAL = 1024;
    private static final int DETAIL_HTML_INITIAL = 320;
    private static final int DEFAULT_SEED_COUNT = 10_000;
    private static final int MAX_SEED_COUNT = 100_000;

    private static final String MSG_PURCHASER_NOT_FOUND = "Purchaser not found";

    private final PurchaserDAO dao;

    public PurchaserController(PurchaserDAO dao) {
        this.dao = dao;
    }

    @OpenApi(
            path = "/purchaser",
            methods = HttpMethod.POST,
            summary = "Register a new purchaser (max 5 postcodes)",
            tags = {"Purchaser"},
            requestBody = @OpenApiRequestBody(
                    content = @OpenApiContent(from = CreateRequest.class), required = true),
            responses = {
                    @OpenApiResponse(status = "201", description = "Purchaser created",
                            content = @OpenApiContent(from = IdResponse.class)),
                    @OpenApiResponse(status = "400", description = "Invalid payload")
            })
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

    @OpenApi(
            path = "/purchaser",
            methods = HttpMethod.GET,
            summary = "List purchasers (capped)",
            tags = {"Purchaser"},
            responses = {
                    @OpenApiResponse(status = "200", description = "Purchasers"),
                    @OpenApiResponse(status = "404", description = "No purchasers found")
            })
    public void getAllPurchasers(Context ctx) {
        List<Purchaser> purchasers = dao.getAllPurchasers();
        renderListOr404(ctx, "All Purchasers", "No purchasers found", purchasers);
    }

    @OpenApi(
            path = "/purchaser/{purchaserID}",
            methods = HttpMethod.GET,
            summary = "Look up a purchaser by ObjectId",
            tags = {"Purchaser"},
            pathParams = @OpenApiParam(name = "purchaserID", required = true),
            responses = {
                    @OpenApiResponse(status = "200", description = "Purchaser found"),
                    @OpenApiResponse(status = "404", description = "Purchaser not found")
            })
    public void getPurchaserById(Context ctx, String id) {
        Optional<Purchaser> p = dao.getPurchaserById(id);
        if (p.isEmpty()) {
            ctx.html(Html.errorPage("Error", MSG_PURCHASER_NOT_FOUND));
            ctx.status(404);
            return;
        }
        ctx.html(purchaserDetailHtml(p.get()));
        ctx.status(200);
    }

    @OpenApi(
            path = "/purchaser/postcode/{postcode}",
            methods = HttpMethod.GET,
            summary = "List purchasers watching a postcode",
            tags = {"Purchaser"},
            pathParams = @OpenApiParam(name = "postcode", required = true),
            responses = {
                    @OpenApiResponse(status = "200", description = "Matching purchasers"),
                    @OpenApiResponse(status = "404", description = "No interest in postcode")
            })
    public void getPurchasersByPostcode(Context ctx, String postcode) {
        List<Purchaser> list = dao.getPurchasersByPostcode(postcode);
        renderListOr404(ctx,
                "Purchasers interested in " + postcode,
                "No purchasers interested in postcode " + postcode,
                list);
    }

    @OpenApi(
            path = "/purchaser/{purchaserID}/interest",
            methods = HttpMethod.POST,
            summary = "Register interest in a postcode (max 5 per purchaser)",
            tags = {"Purchaser"},
            pathParams = @OpenApiParam(name = "purchaserID", required = true),
            requestBody = @OpenApiRequestBody(
                    content = @OpenApiContent(from = InterestRequest.class), required = true),
            responses = {
                    @OpenApiResponse(status = "201", description = "Interest added"),
                    @OpenApiResponse(status = "400", description = "Invalid postcode"),
                    @OpenApiResponse(status = "404", description = "Purchaser not found"),
                    @OpenApiResponse(status = "409", description = "Duplicate or limit reached")
            })
    public void addInterest(Context ctx, String purchaserId) {
        InterestRequest req = ctx.bodyValidator(InterestRequest.class)
                .check(r -> r.postcode != null && !r.postcode.isBlank(), "postcode required")
                .get();

        PurchaserDAO.AddResult r = dao.addInterest(purchaserId, req.postcode);
        switch (r) {
            case OK:
                ctx.result("Interest added");
                ctx.status(201);
                break;
            case DUPLICATE:
                ctx.result("Already interested in postcode");
                ctx.status(409);
                break;
            case LIMIT_REACHED:
                ctx.result("Max 5 postcodes per purchaser");
                ctx.status(409);
                break;
            case INVALID_POSTCODE:
                ctx.result("Postcode is not a valid NSW postcode");
                ctx.status(400);
                break;
            case NOT_FOUND:
            default:
                ctx.result(MSG_PURCHASER_NOT_FOUND);
                ctx.status(404);
                break;
        }
    }

    @OpenApi(
            path = "/purchaser/{purchaserID}/interest/{postcode}",
            methods = HttpMethod.DELETE,
            summary = "Remove a postcode from a purchaser's interest list",
            tags = {"Purchaser"},
            pathParams = {
                    @OpenApiParam(name = "purchaserID", required = true),
                    @OpenApiParam(name = "postcode", required = true)
            },
            responses = {
                    @OpenApiResponse(status = "200", description = "Removed"),
                    @OpenApiResponse(status = "404", description = "Not found")
            })
    public void removeInterest(Context ctx, String purchaserId, String postcode) {
        if (dao.removeInterest(purchaserId, postcode)) {
            ctx.result("Interest removed");
            ctx.status(200);
        } else {
            ctx.result("Purchaser or postcode not found");
            ctx.status(404);
        }
    }

    @OpenApi(
            path = "/purchaser/seed",
            methods = HttpMethod.POST,
            summary = "Seed N synthetic purchasers with random NSW postcode interests",
            tags = {"Purchaser"},
            queryParams = @OpenApiParam(
                    name = "count", type = Integer.class,
                    description = "Number of purchasers to seed (default 10000, max 100000)"),
            responses = @OpenApiResponse(status = "201", description = "Seeded"))
    public void seedPurchasers(Context ctx) {
        int count = parseSeedCount(ctx.queryParam("count"));
        int inserted = dao.seedPurchasers(count);
        ctx.result("Seeded " + inserted + " purchasers");
        ctx.status(201);
    }

    private static int parseSeedCount(String raw) {
        if (raw == null) {
            return DEFAULT_SEED_COUNT;
        }
        try {
            return Math.max(1, Math.min(MAX_SEED_COUNT, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return DEFAULT_SEED_COUNT;
        }
    }

    private static void renderListOr404(Context ctx, String title, String emptyMsg, List<Purchaser> list) {
        if (list.isEmpty()) {
            ctx.html(Html.errorPage("Error", emptyMsg));
            ctx.status(404);
        } else {
            ctx.html(purchaserTableHtml(title, list));
            ctx.status(200);
        }
    }

    private static String purchaserTableHtml(String title, List<Purchaser> list) {
        StringBuilder sb = new StringBuilder(TABLE_HTML_INITIAL);
        sb.append("<!DOCTYPE html><html><head><title>").append(Html.escape(title))
          .append("</title></head><body><h1>").append(Html.escape(title)).append("</h1>")
          .append("<table border='1' cellpadding='6' cellspacing='0'>")
          .append("<tr><th>Purchaser ID</th><th>Name</th><th>Email</th><th>Postcodes</th></tr>");
        for (Purchaser p : list) {
            sb.append("<tr><td>").append(p.purchaserId)
              .append("</td><td>").append(Html.escape(p.name))
              .append("</td><td>").append(Html.escape(p.email))
              .append("</td><td>").append(Html.escape(String.join(", ", p.postcodes)))
              .append("</td></tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private static String purchaserDetailHtml(Purchaser p) {
        StringBuilder sb = new StringBuilder(DETAIL_HTML_INITIAL);
        sb.append("<!DOCTYPE html><html><body>")
          .append("<h1>Purchaser ").append(p.purchaserId).append("</h1>")
          .append("<p><b>Name:</b> ").append(Html.escape(p.name)).append("</p>")
          .append("<p><b>Email:</b> ").append(Html.escape(p.email)).append("</p>")
          .append("<h3>Postcodes of interest</h3>");
        if (p.postcodes.isEmpty()) {
            sb.append("<p>None</p>");
        } else {
            sb.append("<ul>");
            for (String pc : p.postcodes) {
                sb.append("<li>").append(Html.escape(pc)).append("</li>");
            }
            sb.append("</ul>");
        }
        sb.append("</body></html>");
        return sb.toString();
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

        public IdResponse(String purchaserId) {
            this.purchaserId = purchaserId;
        }
    }
}
