package notification;

import io.javalin.http.Context;

import java.util.List;

public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    public void notify(Context ctx) {
        List<NotificationService.PurchaserNotification> notifications = service.generateNotifications();

        if (notifications.isEmpty()) {
            ctx.html("<html><body><h2>No notifications — no purchasers have matching for-sale properties in their preferred postcodes.</h2></body></html>");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head>")
          .append("<style>")
          .append("body{font-family:sans-serif;padding:20px;max-width:1000px;margin:auto}")
          .append("h1{color:#2c3e50}")
          .append(".purchaser{margin-bottom:28px;border:1px solid #ccc;padding:16px;border-radius:6px;background:#fafafa}")
          .append(".purchaser h2{margin-top:0;color:#2980b9;font-size:1.1em}")
          .append(".meta{color:#555;font-size:.9em;margin-bottom:8px}")
          .append("table{border-collapse:collapse;width:100%;margin-top:8px}")
          .append("th,td{border:1px solid #ddd;padding:7px 10px;text-align:left}")
          .append("th{background:#f2f2f2;font-weight:600}")
          .append("tr:nth-child(even){background:#f9f9f9}")
          .append("</style>")
          .append("</head><body>")
          .append("<h1>Property Notifications</h1>")
          .append("<p><strong>").append(notifications.size())
          .append("</strong> purchaser(s) have matching for-sale properties.</p>");

        for (NotificationService.PurchaserNotification n : notifications) {
            sb.append("<div class='purchaser'>")
              .append("<h2>").append(esc(n.purchaser.name)).append("</h2>")
              .append("<p class='meta'>Email: ").append(esc(n.purchaser.email))
              .append(" &nbsp;|&nbsp; Interested postcodes: ")
              .append(esc(String.join(", ", n.purchaser.postcodes))).append("</p>")
              .append("<table>")
              .append("<tr><th>Property ID</th><th>Postcode</th><th>Listing Price (AUD)</th></tr>");

            for (NotificationService.PropertyMatch m : n.matches) {
                String priceStr = m.price > 0
                        ? "$" + String.format("%,.2f", m.price)
                        : "N/A";
                sb.append("<tr>")
                  .append("<td>").append(esc(m.propertyId)).append("</td>")
                  .append("<td>").append(esc(m.postcode)).append("</td>")
                  .append("<td>").append(priceStr).append("</td>")
                  .append("</tr>");
            }
            sb.append("</table></div>");
        }

        sb.append("</body></html>");
        ctx.html(sb.toString());
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
