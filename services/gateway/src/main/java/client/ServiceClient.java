package client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around java.net.http.HttpClient. Knows how to call each
 * downstream service and (de)serialize JSON. One instance per gateway is
 * enough — HttpClient is thread-safe and pools connections.
 *
 * Each public method maps to one specific downstream endpoint. Routing logic
 * (which service to call for which gateway endpoint) lives in the gateway
 * route handlers, not here.
 */
public class ServiceClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper json = new ObjectMapper();

    private final String propertyBase;
    private final String purchaserBase;
    private final String analyticsBase;

    public ServiceClient(String propertyBase, String purchaserBase, String analyticsBase) {
        this.propertyBase = propertyBase;
        this.purchaserBase = purchaserBase;
        this.analyticsBase = analyticsBase;
    }

    // ── property-server ───────────────────────────────────────────────────────

    public Response propertyGet(String path) { return get(propertyBase + path); }
    public Response propertyPost(String path, Object body) { return postJson(propertyBase + path, body); }

    // ── purchaser-server ──────────────────────────────────────────────────────

    public Response purchaserGet(String path) { return get(purchaserBase + path); }
    public Response purchaserPost(String path, Object body) { return postJson(purchaserBase + path, body); }
    public Response purchaserDelete(String path) { return delete(purchaserBase + path); }

    // ── analytics-server ──────────────────────────────────────────────────────

    /** Fire-and-forget counter bump; ignores response body (only the side-effect matters). */
    public void bumpPropertyView(String propertyId) {
        try { postJson(analyticsBase + "/views/property/" + enc(propertyId), null); } catch (Exception ignored) {}
    }

    public void bumpPostcodeSearch(String postcode) {
        try { postJson(analyticsBase + "/searches/postcode/" + enc(postcode), null); } catch (Exception ignored) {}
    }

    public Response analyticsGet(String path) { return get(analyticsBase + path); }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    public <T> T deserialize(String body, Class<T> type) {
        try { return json.readValue(body, type); }
        catch (IOException e) { throw new RuntimeException("Failed to deserialize: " + body, e); }
    }

    public Map<String, Object> deserializeMap(String body) {
        try { return json.readValue(body, new TypeReference<Map<String, Object>>() {}); }
        catch (IOException e) { throw new RuntimeException("Failed to deserialize map: " + body, e); }
    }

    public List<Map<String, Object>> deserializeList(String body) {
        try { return json.readValue(body, new TypeReference<List<Map<String, Object>>>() {}); }
        catch (IOException e) { throw new RuntimeException("Failed to deserialize list: " + body, e); }
    }

    // ── lower-level send ──────────────────────────────────────────────────────

    private Response get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    private Response delete(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).DELETE());
    }

    private Response postJson(String url, Object body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json");
        String payload;
        try { payload = body == null ? "{}" : json.writeValueAsString(body); }
        catch (IOException e) { throw new RuntimeException("Failed to serialize body", e); }
        return send(b.POST(HttpRequest.BodyPublishers.ofString(payload)));
    }

    private Response send(HttpRequest.Builder b) {
        HttpRequest req = b.timeout(Duration.ofSeconds(10)).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        } catch (Exception e) {
            return new Response(502, "Upstream call failed: " + e.getMessage());
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    public record Response(int status, String body) {
        public boolean ok() { return status >= 200 && status < 300; }
    }
}
