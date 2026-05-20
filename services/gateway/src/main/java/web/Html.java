package web;

import io.javalin.http.Context;

/** Shared HTML helpers + content-negotiation decision. */
public final class Html {

    private Html() {}

    public static String escape(Object v) {
        if (v == null) return "";
        return v.toString()
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * Browser-friendly response if the caller advertises text/html, or if they
     * explicitly request ?format=html. Default for everything else is JSON
     * (the API tools and other services consume JSON).
     */
    public static boolean wantsHtml(Context ctx) {
        String fmt = ctx.queryParam("format");
        if (fmt != null) return "html".equalsIgnoreCase(fmt);
        String accept = ctx.header("Accept");
        return accept != null && accept.toLowerCase().contains("text/html");
    }

    public static String errorPage(String msg) {
        return "<!DOCTYPE html><html><head><title>Error</title></head><body>"
                + "<h1>Error</h1><p>" + escape(msg) + "</p></body></html>";
    }

    public static String shell(String title, String body) {
        return "<!DOCTYPE html><html><head><title>" + escape(title) + "</title>"
                + "<style>body{font-family:system-ui,sans-serif;max-width:1100px;margin:2em auto;padding:0 1em}"
                + "h1{margin-bottom:.25em}h2{margin-top:1.5em;border-top:1px solid #ddd;padding-top:1em}"
                + ".meta{color:#666;font-size:.9em;margin-bottom:.5em}"
                + "table{border-collapse:collapse;width:100%}"
                + "th,td{border:1px solid #ccc;padding:.4em .7em;text-align:left;vertical-align:top}"
                + "th{background:#f4f4f4}.empty{color:#999;font-style:italic}</style></head><body>"
                + body + "</body></html>";
    }
}
