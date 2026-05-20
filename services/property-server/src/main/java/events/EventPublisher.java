package events;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

/**
 * Fire-and-forget publisher to the `property.events` topic exchange. Each
 * call serializes a JSON string into a message body and publishes with the
 * given routing key. The service that owns the event (property-server, in
 * this jar) has no idea what consumers do with it — that's the whole point.
 */
public class EventPublisher {

    public static final String EXCHANGE = "property.events";

    private static volatile EventPublisher instance;

    public static synchronized EventPublisher get() {
        if (instance == null) instance = new EventPublisher();
        return instance;
    }

    private final Connection connection;
    private final Channel channel;

    private EventPublisher() {
        try {
            ConnectionFactory f = new ConnectionFactory();
            String uri = System.getenv().getOrDefault("AMQP_URI", "amqp://guest:guest@localhost:5672");
            f.setUri(uri);
            this.connection = f.newConnection("property-server");
            this.channel = connection.createChannel();
            channel.exchangeDeclare(EXCHANGE, "topic", true);
            Runtime.getRuntime().addShutdownHook(new Thread(this::close));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to connect to RabbitMQ", e);
        }
    }

    public void publish(String routingKey, String jsonBody) {
        try {
            channel.basicPublish(EXCHANGE, routingKey, null, jsonBody.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Event publishing must not crash the business path — log and move on.
            System.err.println("[property-server] failed to publish " + routingKey + ": " + e.getMessage());
        }
    }

    private void close() {
        try { channel.close(); } catch (IOException | TimeoutException ignored) {}
        try { connection.close(); } catch (IOException ignored) {}
    }
}
