package app;

/** Shared HTML helpers used by controllers when rendering HTML responses. */
public final class Html {

    private Html() { }

    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static String errorPage(String title, String message) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("<!DOCTYPE html><html><head><title>").append(escape(title)).append("</title></head><body>")
          .append("<h1>").append(escape(title)).append("</h1>")
          .append("<p>").append(escape(message)).append("</p>")
          .append("</body></html>");
        return sb.toString();
    }
}
