package events;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

/** Same exchange/topic publisher pattern as property-server. */
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
            f.setUri(System.getenv().getOrDefault("AMQP_URI", "amqp://guest:guest@localhost:5672"));
            this.connection = f.newConnection("analytics-server");
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
            System.err.println("[analytics-server] failed to publish " + routingKey + ": " + e.getMessage());
        }
    }

    private void close() {
        try { channel.close(); } catch (IOException | TimeoutException ignored) {}
        try { connection.close(); } catch (IOException ignored) {}
    }
}
