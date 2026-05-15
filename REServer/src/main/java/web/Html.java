package web;

/** Shared HTML helpers used by all controllers. */
public final class Html {

    private Html() {}

    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static String errorPage(String msg) {
        return "<!DOCTYPE html><html><head><title>Error</title></head><body>"
                + "<h1>Error</h1><p>" + escape(msg) + "</p></body></html>";
    }
}
