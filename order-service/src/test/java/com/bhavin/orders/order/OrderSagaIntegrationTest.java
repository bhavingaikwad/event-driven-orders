package com.bhavin.orders.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhavin.orders.contracts.EventEnvelope;
import com.bhavin.orders.contracts.EventType;
import com.bhavin.orders.contracts.Topics;
import com.bhavin.orders.contracts.events.PaymentFailed;
import com.bhavin.orders.contracts.events.PaymentSucceeded;
import com.bhavin.orders.contracts.events.StockReserved;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Real Postgres and real Kafka in containers, the real order-service context on top.
 * The test plays inventory-service and payment-service by publishing their events to the topics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "consumer.retry.max-retries=3",
        "consumer.retry.initial-interval-ms=50",
        "consumer.retry.max-interval-ms=100",
        "management.tracing.sampling.probability=0",
        "logging.file.name=target/order-service-it.log"
})
class OrderSagaIntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.0")
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094");

    static {
        POSTGRES.start();
        KAFKA.start();
        createTopics();
    }

    private static void createTopics() {
        List<NewTopic> topics = List.of(
                new NewTopic(Topics.ORDER_EVENTS, 1, (short) 1),
                new NewTopic(Topics.INVENTORY_EVENTS, 1, (short) 1),
                new NewTopic(Topics.PAYMENT_EVENTS, 1, (short) 1),
                new NewTopic(Topics.INVENTORY_EVENTS + ".DLT", 1, (short) 1),
                new NewTopic(Topics.PAYMENT_EVENTS + ".DLT", 1, (short) 1));
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of(
                "bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not create test topics", e);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    OrderService orderService;
    @Autowired
    KafkaTemplate<String, String> template;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;

    @Test
    void happyPathGoesThroughOutboxAndRealKafka() throws Exception {
        Order order = newOrder();
        UUID id = order.getId();

        ConsumerRecord<String, String> created = awaitRecord(Topics.ORDER_EVENTS,
                v -> v.contains(id.toString()) && v.contains("OrderCreated"));
        assertThat(created.key()).isEqualTo(id.toString());

        send(Topics.INVENTORY_EVENTS, id, event(EventType.STOCK_RESERVED, order,
                new StockReserved(id, UUID.randomUUID())));
        send(Topics.PAYMENT_EVENTS, id, event(EventType.PAYMENT_SUCCEEDED, order,
                new PaymentSucceeded(id, UUID.randomUUID(), order.getTotalAmount(), "USD")));

        awaitStatus(id, OrderStatus.CONFIRMED);
        awaitRecord(Topics.ORDER_EVENTS, v -> v.contains(id.toString()) && v.contains("OrderConfirmed"));
        assertThat(outboxCount(id, "OrderCreated")).isEqualTo(1);
        assertThat(outboxCount(id, "OrderConfirmed")).isEqualTo(1);
    }

    @Test
    void duplicatePaymentSucceededIsAppliedOnce() throws Exception {
        Order order = newOrder();
        UUID id = order.getId();

        send(Topics.INVENTORY_EVENTS, id, event(EventType.STOCK_RESERVED, order,
                new StockReserved(id, UUID.randomUUID())));
        PaymentSucceeded payment = new PaymentSucceeded(id, UUID.randomUUID(), order.getTotalAmount(), "USD");
        send(Topics.PAYMENT_EVENTS, id, event(EventType.PAYMENT_SUCCEEDED, order, payment));
        send(Topics.PAYMENT_EVENTS, id, event(EventType.PAYMENT_SUCCEEDED, order, payment));

        awaitStatus(id, OrderStatus.CONFIRMED);
        await("duplicate to be counted", () -> duplicateCount(id, "PaymentSucceeded") == 1);
        assertThat(outboxCount(id, "OrderConfirmed")).isEqualTo(1);
    }

    @Test
    void paymentFailureCancelsOrderAndPublishesCompensation() throws Exception {
        Order order = newOrder();
        UUID id = order.getId();

        send(Topics.INVENTORY_EVENTS, id, event(EventType.STOCK_RESERVED, order,
                new StockReserved(id, UUID.randomUUID())));
        send(Topics.PAYMENT_EVENTS, id, event(EventType.PAYMENT_FAILED, order,
                new PaymentFailed(id, "card declined")));

        awaitStatus(id, OrderStatus.CANCELLED);
        ConsumerRecord<String, String> cancelled = awaitRecord(Topics.ORDER_EVENTS,
                v -> v.contains(id.toString()) && v.contains("OrderCancelled"));
        assertThat(cancelled.value()).contains("Payment failed: card declined");
        assertThat(outboxCount(id, "OrderConfirmed")).isZero();
    }

    @Test
    void unparseableMessageGoesStraightToDeadLetterTopic() throws Exception {
        String poison = "this is not json " + UUID.randomUUID();
        template.send(Topics.INVENTORY_EVENTS, "poison", poison).get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, String> dead = awaitRecord(Topics.INVENTORY_EVENTS + ".DLT",
                v -> v.equals(poison));
        assertThat(dead.value()).isEqualTo(poison);
    }

    @Test
    void eventForUnknownOrderExhaustsRetriesThenGoesToDeadLetterTopic() throws Exception {
        UUID ghost = UUID.randomUUID();
        EventEnvelope<StockReserved> envelope = EventEnvelope.of(EventType.STOCK_RESERVED,
                UUID.randomUUID(), "test", new StockReserved(ghost, UUID.randomUUID()));
        template.send(Topics.INVENTORY_EVENTS, ghost.toString(), mapper.writeValueAsString(envelope))
                .get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, String> dead = awaitRecord(Topics.INVENTORY_EVENTS + ".DLT",
                v -> v.contains(ghost.toString()));
        assertThat(dead.key()).isEqualTo(ghost.toString());
    }

    private Order newOrder() {
        return orderService.create(new CreateOrderRequest("cust-1", "US", "USD",
                List.of(new CreateOrderRequest.Item("sku-1", 2, new BigDecimal("10.00")))));
    }

    private String event(EventType type, Order order, Object payload) {
        return mapper.writeValueAsString(EventEnvelope.of(type, order.getCorrelationId(), "test", payload));
    }

    private void send(String topic, UUID orderId, String json) throws Exception {
        template.send(topic, orderId.toString(), json).get(10, TimeUnit.SECONDS);
    }

    private void awaitStatus(UUID id, OrderStatus expected) throws InterruptedException {
        await("order " + id + " to reach " + expected, () -> orderService.get(id).getStatus() == expected);
    }

    private long outboxCount(UUID orderId, String eventType) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE aggregate_id = :id AND event_type = :t")
                .param("id", orderId)
                .param("t", eventType)
                .query(Long.class)
                .single();
    }

    private int duplicateCount(UUID orderId, String eventType) {
        return jdbc.sql("""
                SELECT duplicate_count FROM processed_events
                WHERE consumer = 'order-service' AND event_type = :t AND aggregate_id = :id
                """)
                .param("id", orderId)
                .param("t", eventType)
                .query(Integer.class)
                .optional()
                .orElse(-1);
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private static ConsumerRecord<String, String> awaitRecord(String topic, Predicate<String> matches) {
        Map<String, Object> config = Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", "it-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest",
                "enable.auto.commit", "false",
                "key.deserializer", StringDeserializer.class.getName(),
                "value.deserializer", StringDeserializer.class.getName());
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (matches.test(record.value())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No matching record on " + topic + " within 30 s");
    }
}
