package app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Notification microservice.
 *
 *   in    : property.events  (topic exchange, binding "property.#")
 *   out   : purchaser.messages (topic exchange, routing key "purchaser.&lt;id&gt;")
 *
 * For each property.* event:
 *   1. resolve the postcode (events from property-server already carry it;
 *      property.hot from analytics-server doesn't — we GET /property/{id} for that)
 *   2. ask purchaser-server "who watches that postcode?"
 *   3. publish one message per matching purchaser onto purchaser.messages
 *
 * Single-purpose service — has NO database of its own. It learns about the
 * world only through HTTP and the broker.
 */
public class NotificationService {

    public static final String IN_EXCHANGE  = "property.events";
    public static final String OUT_EXCHANGE = "purchaser.messages";
    public static final String IN_QUEUE     = "notification.in";
    public static final String BINDING      = "property.#";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    private final String propertyBase;
    private final String purchaserBase;
    private Channel channel;

    public NotificationService(String propertyBase, String purchaserBase) {
        this.propertyBase = propertyBase;
        this.purchaserBase = purchaserBase;
    }

    public static void main(String[] args) throws Exception {
        String propertyBase  = System.getenv().getOrDefault("PROPERTY_URL",  "http://localhost:7071");
        String purchaserBase = System.getenv().getOrDefault("PURCHASER_URL", "http://localhost:7072");
        new NotificationService(propertyBase, purchaserBase).run();
    }

    public void run() throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setUri(System.getenv().getOrDefault("AMQP_URI", "amqp://guest:guest@localhost:5672"));
        Connection conn = f.newConnection("notification-service");
        channel = conn.createChannel();

        channel.exchangeDeclare(IN_EXCHANGE, "topic", true);
        channel.exchangeDeclare(OUT_EXCHANGE, "topic", true);
        channel.queueDeclare(IN_QUEUE, true, false, false, null);
        channel.queueBind(IN_QUEUE, IN_EXCHANGE, BINDING);

        DeliverCallback cb = (tag, delivery) -> {
            String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
            String routingKey = delivery.getEnvelope().getRoutingKey();
            try {
                handle(routingKey, body);
            } catch (Exception e) {
                System.err.println("[notify] handler failed for " + routingKey + ": " + e.getMessage());
            }
        };
        channel.basicConsume(IN_QUEUE, true, cb, t -> {});
        System.out.println("[notify] listening on " + IN_QUEUE + " (binding " + BINDING + ")");
    }

    private void handle(String routingKey, String body) throws Exception {
        Map<String, Object> event = json.readValue(body, new TypeReference<>() {});
        String type = (String) event.get("type");
        String propertyId = (String) event.get("property_id");
        String postcode = (String) event.get("postcode");

        // property.hot doesn't carry a postcode — fetch it.
        if (postcode == null && propertyId != null) {
            postcode = lookupPostcode(propertyId);
            // Make the enriched postcode visible to the message formatter.
            if (postcode != null) event.put("postcode", postcode);
        }
        if (postcode == null) {
            System.out.println("[notify] " + routingKey + " has no resolvable postcode; dropping");
            return;
        }

        List<Map<String, Object>> buyers = lookupBuyers(postcode);
        if (buyers.isEmpty()) {
            System.out.println("[notify] " + routingKey + " in " + postcode + " → no interested buyers");
            return;
        }

        for (Map<String, Object> buyer : buyers) {
            String buyerId = (String) buyer.get("purchaserId");
            String message = formatMessage(type, event, buyer);
            String envelope = json.writeValueAsString(Map.of(
                    "purchaserId", buyerId,
                    "purchaserName", buyer.get("name"),
                    "purchaserEmail", buyer.get("email"),
                    "eventType", type,
                    "message", message,
                    "event", event));
            channel.basicPublish(OUT_EXCHANGE, "purchaser." + buyerId, null,
                    envelope.getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("[notify] " + routingKey + " in " + postcode + " → fanned out to " + buyers.size() + " buyer(s)");
    }

    private String formatMessage(String type, Map<String, Object> event, Map<String, Object> buyer) {
        String name = (String) buyer.get("name");
        String pc = (String) event.get("postcode");
        return switch (type == null ? "" : type) {
            case "listed" -> String.format(
                    "Hi %s — a new property in postcode %s just went on sale (id %s) at $%s.",
                    name, pc, event.get("property_id"), event.get("price"));
            case "price-changed" -> String.format(
                    "Hi %s — price update on property %s in %s: $%s → $%s.",
                    name, event.get("property_id"), pc, event.get("old_price"), event.get("new_price"));
            case "hot" -> String.format(
                    "Hi %s — property %s in postcode %s is HOT (%s views and counting).",
                    name, event.get("property_id"), pc, event.get("view_count"));
            default -> String.format("Hi %s — event '%s' on property %s.", name, type, event.get("property_id"));
        };
    }

    @SuppressWarnings("unchecked")
    private String lookupPostcode(String propertyId) throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(propertyBase + "/property/" + propertyId))
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 200 && r.statusCode() < 300) {
            Map<String, Object> p = json.readValue(r.body(), new TypeReference<>() {});
            return (String) p.get("postcode");
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lookupBuyers(String postcode) throws Exception {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(purchaserBase + "/purchaser/postcode/" + postcode))
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 200 && r.statusCode() < 300) {
            return json.readValue(r.body(), new TypeReference<>() {});
        }
        return List.of();
    }
}
