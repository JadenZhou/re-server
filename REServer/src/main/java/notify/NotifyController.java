package notify;

import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

import java.util.List;
import java.util.Map;

/**
 * GET /notify
 *   ?format=html (default) — table per purchaser
 *   ?format=text           — plain text, one block per purchaser (large output friendly)
 */
public class NotifyController {

    private static final int TEXT_REPORT_INITIAL = 512;
    private static final int HTML_REPORT_INITIAL = 1024;
    private static final int DIVIDER_WIDTH = 72;

    private final NotifyDAO dao;
    private final NotifyService service;

    public NotifyController(NotifyDAO dao, NotifyService service) {
        this.dao = dao;
        this.service = service;
    }

    @OpenApi(
            path = "/notify",
            methods = HttpMethod.GET,
            summary = "Notification report — for-sale properties in each buyer's watched postcodes",
            tags = {"Notification"},
            queryParams = @OpenApiParam(
                    name = "format", type = String.class,
                    description = "html (default) | text"),
            responses = @OpenApiResponse(status = "200", description = "Report rendered"))
    public void notify(Context ctx) {
        Map<String, List<PropertyForSale>> index = dao.buildPostcodeIndex();
        List<PurchaserSummary> purchasers = dao.fetchPurchasers();
        List<Notification> notifications = service.notify(index, purchasers);

        String format = ctx.queryParam("format");
        if ("text".equalsIgnoreCase(format)) {
            ctx.contentType("text/plain");
            ctx.result(renderText(notifications));
        } else {
            ctx.html(renderHtml(notifications));
        }
    }

    static String renderText(List<Notification> notifications) {
        StringBuilder sb = new StringBuilder(TEXT_REPORT_INITIAL);
        sb.append("Notification report — ").append(notifications.size())
          .append(" purchaser(s) with matching for-sale properties\n")
          .append("=".repeat(DIVIDER_WIDTH)).append("\n\n");
        for (Notification n : notifications) {
            sb.append(n.purchaserName == null ? "(unnamed)" : n.purchaserName)
              .append("  <").append(n.purchaserEmail == null ? "?" : n.purchaserEmail).append(">")
              .append("  id=").append(n.purchaserId).append('\n')
              .append("  watching: ").append(String.join(", ", n.watchedPostcodes)).append('\n');
            for (PropertyForSale p : n.matches) {
                sb.append(String.format("    property_id=%-12d  postcode=%s  price=$%,.0f%n",
                        p.propertyId, p.postcode, p.price));
            }
            sb.append('\n');
        }
        if (notifications.isEmpty()) {
            sb.append("(no matches)\n");
        }
        return sb.toString();
    }

    static String renderHtml(List<Notification> notifications) {
        StringBuilder sb = new StringBuilder(HTML_REPORT_INITIAL);
        sb.append("<!DOCTYPE html><html><head><title>Notifications</title>")
          .append("<style>body{font-family:system-ui,sans-serif;max-width:900px;margin:2em auto;padding:0 1em}")
          .append("h1{margin-bottom:.25em}h2{margin-top:1.5em;border-top:1px solid #ddd;padding-top:1em}")
          .append(".meta{color:#666;font-size:.9em;margin-bottom:.5em}")
          .append("table{border-collapse:collapse;width:100%}th,td{border:1px solid #ccc;padding:.4em .7em;text-align:left}")
          .append("th{background:#f4f4f4}.empty{color:#999;font-style:italic}</style></head><body>")
          .append("<h1>Notification report</h1>")
          .append("<p class=\"meta\">").append(notifications.size())
          .append(" purchaser(s) with matching for-sale properties.</p>");
        if (notifications.isEmpty()) {
            sb.append("<p class=\"empty\">No purchaser had a matching property in their watched postcodes.</p>");
        }
        for (Notification n : notifications) {
            appendNotificationSection(sb, n);
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void appendNotificationSection(StringBuilder sb, Notification n) {
        sb.append("<h2>").append(escape(n.purchaserName)).append("</h2>")
          .append("<p class=\"meta\">")
          .append(escape(n.purchaserEmail))
          .append(" · id ").append(escape(n.purchaserId))
          .append(" · watching ").append(escape(String.join(", ", n.watchedPostcodes)))
          .append("</p>")
          .append("<table><tr><th>Property ID</th><th>Postcode</th><th>Price</th></tr>");
        for (PropertyForSale p : n.matches) {
            sb.append("<tr><td>").append(p.propertyId)
              .append("</td><td>").append(escape(p.postcode))
              .append("</td><td>$").append(String.format("%,.0f", p.price))
              .append("</td></tr>");
        }
        sb.append("</table>");
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
