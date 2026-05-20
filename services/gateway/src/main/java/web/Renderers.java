package web;

import client.ServiceClient;

import java.util.List;
import java.util.Map;

import static web.Html.escape;
import static web.Html.shell;

/**
 * Turns downstream JSON payloads into the same kind of HTML pages the
 * monolith used to serve. Each render method takes the raw response body
 * from a service and returns a self-contained HTML document.
 */
public final class Renderers {

    private Renderers() {}

    // ── properties ────────────────────────────────────────────────────────────

    public static String propertyList(String title, String json, ServiceClient client) {
        List<Map<String, Object>> list = client.deserializeList(json);
        if (list.isEmpty()) return shell(title, "<h1>" + escape(title) + "</h1>"
                + "<p class=\"empty\">No properties found.</p>");
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(escape(title)).append("</h1>");
        body.append("<table><tr>")
                .append("<th>Property ID</th><th>Postcode</th><th>Address</th>")
                .append("<th>Council</th><th>Type</th><th>Last Sale</th>")
                .append("<th>Price</th><th>For Sale</th></tr>");
        for (Map<String, Object> p : list) appendPropertyRow(body, p);
        body.append("</table>");
        return shell(title, body.toString());
    }

    public static String propertyDetail(String id, String json, ServiceClient client) {
        Map<String, Object> p = client.deserializeMap(json);
        StringBuilder body = new StringBuilder();
        body.append("<h1>Property ").append(escape(id)).append("</h1>");
        body.append("<table><tr>")
                .append("<th>Property ID</th><th>Postcode</th><th>Address</th>")
                .append("<th>Council</th><th>Type</th><th>Last Sale</th>")
                .append("<th>Price</th><th>For Sale</th></tr>");
        appendPropertyRow(body, p);
        body.append("</table>");
        return shell("Property " + id, body.toString());
    }

    private static void appendPropertyRow(StringBuilder sb, Map<String, Object> p) {
        sb.append("<tr>")
                .append("<td>").append(escape(p.get("propertyID"))).append("</td>")
                .append("<td>").append(escape(p.get("postcode"))).append("</td>")
                .append("<td>").append(escape(p.get("address"))).append("</td>")
                .append("<td>").append(escape(p.get("councilName"))).append("</td>")
                .append("<td>").append(escape(p.get("propertyType"))).append("</td>")
                .append("<td>").append(escape(p.get("contractDate"))).append("</td>")
                .append("<td>").append(escape(p.get("propertyPrice"))).append("</td>")
                .append("<td>").append(escape(p.get("forSale"))).append("</td>")
                .append("</tr>");
    }

    // ── listings ──────────────────────────────────────────────────────────────

    public static String listingList(String json, ServiceClient client) {
        List<Map<String, Object>> list = client.deserializeList(json);
        if (list.isEmpty()) return shell("All Listings", "<h1>All Listings</h1>"
                + "<p class=\"empty\">No listings found.</p>");
        StringBuilder body = new StringBuilder("<h1>All Listings</h1>");
        body.append("<table><tr>")
                .append("<th>Listing ID</th><th>Property ID</th><th>Discounted</th>")
                .append("<th>Date Added</th><th>Latest Price</th></tr>");
        for (Map<String, Object> l : list) {
            body.append("<tr>")
                    .append("<td>").append(escape(l.get("listingId"))).append("</td>")
                    .append("<td>").append(escape(l.get("propertyObjId"))).append("</td>")
                    .append("<td>").append(escape(l.get("isDiscounted"))).append("</td>")
                    .append("<td>").append(escape(l.get("dateAdded"))).append("</td>")
                    .append("<td>$").append(formatPrice(l.get("latestPrice"))).append("</td>")
                    .append("</tr>");
        }
        body.append("</table>");
        return shell("All Listings", body.toString());
    }

    public static String listingDetail(String id, String json, ServiceClient client) {
        Map<String, Object> w = client.deserializeMap(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> l = (Map<String, Object>) w.get("listing");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) w.getOrDefault("history", List.of());

        StringBuilder body = new StringBuilder();
        body.append("<h1>Listing ").append(escape(id)).append("</h1>");
        body.append("<p><b>Property:</b> ").append(escape(l.get("propertyObjId"))).append("</p>");
        body.append("<p><b>Discounted:</b> ").append(escape(l.get("isDiscounted"))).append("</p>");
        body.append("<p><b>Listed:</b> ").append(escape(l.get("dateAdded"))).append("</p>");
        body.append("<h3>Price History</h3>");
        if (history.isEmpty()) {
            body.append("<p class=\"empty\">No price entries.</p>");
        } else {
            body.append("<table><tr><th>Date</th><th>Price</th></tr>");
            for (Map<String, Object> e : history) {
                body.append("<tr><td>").append(escape(e.get("date"))).append("</td>")
                        .append("<td>$").append(formatPrice(e.get("price"))).append("</td></tr>");
            }
            body.append("</table>");
        }
        return shell("Listing " + id, body.toString());
    }

    // ── purchasers ────────────────────────────────────────────────────────────

    public static String purchaserList(String title, String json, ServiceClient client) {
        List<Map<String, Object>> list = client.deserializeList(json);
        if (list.isEmpty()) return shell(title, "<h1>" + escape(title) + "</h1>"
                + "<p class=\"empty\">No purchasers found.</p>");
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(escape(title)).append("</h1>");
        body.append("<table><tr><th>Purchaser ID</th><th>Name</th><th>Email</th><th>Postcodes</th></tr>");
        for (Map<String, Object> p : list) appendPurchaserRow(body, p);
        body.append("</table>");
        return shell(title, body.toString());
    }

    public static String purchaserDetail(String id, String json, ServiceClient client) {
        Map<String, Object> p = client.deserializeMap(json);
        StringBuilder body = new StringBuilder();
        body.append("<h1>Purchaser ").append(escape(id)).append("</h1>");
        body.append("<p><b>Name:</b> ").append(escape(p.get("name"))).append("</p>");
        body.append("<p><b>Email:</b> ").append(escape(p.get("email"))).append("</p>");
        body.append("<h3>Postcodes of interest</h3>");
        @SuppressWarnings("unchecked")
        List<Object> postcodes = (List<Object>) p.getOrDefault("postcodes", List.of());
        if (postcodes.isEmpty()) {
            body.append("<p class=\"empty\">None</p>");
        } else {
            body.append("<ul>");
            for (Object pc : postcodes) body.append("<li>").append(escape(pc)).append("</li>");
            body.append("</ul>");
        }
        return shell("Purchaser " + id, body.toString());
    }

    private static void appendPurchaserRow(StringBuilder sb, Map<String, Object> p) {
        @SuppressWarnings("unchecked")
        List<Object> postcodes = (List<Object>) p.getOrDefault("postcodes", List.of());
        StringBuilder pcs = new StringBuilder();
        for (int i = 0; i < postcodes.size(); i++) {
            if (i > 0) pcs.append(", ");
            pcs.append(escape(postcodes.get(i)));
        }
        sb.append("<tr>")
                .append("<td>").append(escape(p.get("purchaserId"))).append("</td>")
                .append("<td>").append(escape(p.get("name"))).append("</td>")
                .append("<td>").append(escape(p.get("email"))).append("</td>")
                .append("<td>").append(pcs).append("</td>")
                .append("</tr>");
    }

    // ── notify ────────────────────────────────────────────────────────────────

    public static String notify(List<Map<String, Object>> notifications) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>Notification report</h1>");
        body.append("<p class=\"meta\">").append(notifications.size())
                .append(" purchaser(s) with matching for-sale properties.</p>");
        if (notifications.isEmpty()) {
            body.append("<p class=\"empty\">No purchaser had a matching property in their watched postcodes.</p>");
        }
        for (Map<String, Object> n : notifications) {
            @SuppressWarnings("unchecked")
            List<Object> watched = (List<Object>) n.getOrDefault("watchedPostcodes", List.of());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> matches = (List<Map<String, Object>>) n.getOrDefault("matches", List.of());

            body.append("<h2>").append(escape(n.get("purchaserName"))).append("</h2>");
            body.append("<p class=\"meta\">")
                    .append(escape(n.get("purchaserEmail")))
                    .append(" · id ").append(escape(n.get("purchaserId")))
                    .append(" · watching ").append(escape(String.join(", ", listToStrings(watched))))
                    .append("</p>");
            body.append("<table><tr><th>Property ID</th><th>Postcode</th><th>Price</th></tr>");
            for (Map<String, Object> p : matches) {
                Object pid = p.get("nswPropertyId");
                if (pid == null) pid = p.get("propertyId");
                body.append("<tr><td>").append(escape(pid))
                        .append("</td><td>").append(escape(p.get("postcode")))
                        .append("</td><td>$").append(formatPrice(p.get("price")))
                        .append("</td></tr>");
            }
            body.append("</table>");
        }
        return shell("Notifications", body.toString());
    }

    // ── small helpers ─────────────────────────────────────────────────────────

    private static List<String> listToStrings(List<Object> in) {
        return in.stream().map(x -> x == null ? "" : x.toString()).toList();
    }

    private static String formatPrice(Object v) {
        if (v == null) return "0";
        try { return String.format("%,.0f", ((Number) v).doubleValue()); }
        catch (Exception e) { return v.toString(); }
    }
}
