package app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Simulates a delivery mechanism: pulls from purchaser.messages and prints
 * the formatted text to stdout (the brief: "a consumer that removes
 * messages and prints the formatted text to an output window").
 *
 * In a real system this would be a worker that sends email / SMS / push.
 * Keeping it standalone makes the demo trivially observable — start it in
 * a separate terminal and watch lines appear as events fire.
 */
public class NotificationConsumer {

    public static final String EXCHANGE = "purchaser.messages";
    public static final String QUEUE = "purchaser.delivery";
    public static final String BINDING = "purchaser.#";

    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) throws Exception {
        ConnectionFactory f = new ConnectionFactory();
        f.setUri(System.getenv().getOrDefault("AMQP_URI", "amqp://guest:guest@localhost:5672"));
        Connection conn = f.newConnection("notification-consumer");
        Channel channel = conn.createChannel();

        channel.exchangeDeclare(EXCHANGE, "topic", true);
        channel.queueDeclare(QUEUE, true, false, false, null);
        channel.queueBind(QUEUE, EXCHANGE, BINDING);

        ObjectMapper json = new ObjectMapper();
        System.out.println("┌─ notification-consumer ─────────────────────────────────");
        System.out.println("│ queue=" + QUEUE + "  binding=" + BINDING);
        System.out.println("│ ready. messages will appear below as they arrive.");
        System.out.println("└──────────────────────────────────────────────────────────");

        DeliverCallback cb = (tag, delivery) -> {
            try {
                String body = new String(delivery.getBody(), StandardCharsets.UTF_8);
                Map<String, Object> env = json.readValue(body, new TypeReference<>() {});
                String now = LocalTime.now().format(HMS);
                System.out.printf("[%s] → %s <%s>%n   %s%n   (eventType=%s, routingKey=%s)%n%n",
                        now, env.get("purchaserName"), env.get("purchaserEmail"),
                        env.get("message"), env.get("eventType"),
                        delivery.getEnvelope().getRoutingKey());
            } catch (Exception e) {
                System.err.println("[consumer] decode failed: " + e.getMessage());
            }
        };
        channel.basicConsume(QUEUE, true, cb, t -> {});
    }
}
