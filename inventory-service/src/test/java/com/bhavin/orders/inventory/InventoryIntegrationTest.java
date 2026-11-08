package com.bhavin.orders.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhavin.orders.contracts.EventEnvelope;
import com.bhavin.orders.contracts.EventType;
import com.bhavin.orders.contracts.Topics;
import com.bhavin.orders.contracts.events.OrderCreated;
import com.bhavin.orders.contracts.events.PaymentFailed;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Real DynamoDB Local and real Kafka in containers, the real inventory-service context on top.
 * The test plays order-service and payment-service by publishing their events to the topics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "consumer.retry.max-retries=3",
        "consumer.retry.initial-interval-ms=50",
        "consumer.retry.max-interval-ms=100",
        "management.tracing.sampling.probability=0",
        "logging.file.name=target/inventory-service-it.log"
})
class InventoryIntegrationTest {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.0")
            .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094");
    static final GenericContainer<?> DYNAMO = new GenericContainer<>("amazon/dynamodb-local:latest")
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

    static {
        KAFKA.start();
        DYNAMO.start();
        createTopics();
    }

    private static void createTopics() {
        List<NewTopic> topics = List.of(
                new NewTopic(Topics.ORDER_EVENTS, 1, (short) 1),
                new NewTopic(Topics.INVENTORY_EVENTS, 1, (short) 1),
                new NewTopic(Topics.PAYMENT_EVENTS, 1, (short) 1));
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of(
                "bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not create test topics", e);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("inventory.dynamodb.endpoint",
                () -> "http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000));
    }

    @Autowired
    StockService stock;
    @Autowired
    StockSeeder seeder;
    @Autowired
    ReservationReleaser releaser;
    @Autowired
    KafkaTemplate<String, String> template;
    @Autowired
    ObjectMapper mapper;

    @Test
    void orderCreatedReservesStockAndPublishesStockReserved() throws Exception {
        String sku = newSku(5);
        UUID orderId = UUID.randomUUID();
        send(Topics.ORDER_EVENTS, orderId, orderCreatedJson(orderId, sku, 2));

        awaitRecords(Topics.INVENTORY_EVENTS, stockReservedFor(orderId), 1);
        assertThat(stock.availableStock(sku)).contains(3L);
    }

    @Test
    void insufficientStockPublishesStockRejectedAndLeavesStockUntouched() throws Exception {
        String sku = newSku(1);
        UUID orderId = UUID.randomUUID();
        send(Topics.ORDER_EVENTS, orderId, orderCreatedJson(orderId, sku, 2));

        ConsumerRecord<String, String> rejected = awaitRecords(Topics.INVENTORY_EVENTS,
                v -> v.contains(orderId.toString()) && v.contains("StockRejected"), 1).get(0);
        assertThat(rejected.value()).contains("Insufficient stock");
        assertThat(stock.availableStock(sku)).contains(1L);
    }

    @Test
    void duplicateOrderCreatedReservesStockOnlyOnce() throws Exception {
        String sku = newSku(5);
        UUID orderId = UUID.randomUUID();
        String json = orderCreatedJson(orderId, sku, 2);
        send(Topics.ORDER_EVENTS, orderId, json);
        send(Topics.ORDER_EVENTS, orderId, json);

        awaitRecords(Topics.INVENTORY_EVENTS, stockReservedFor(orderId), 2);
        assertThat(stock.availableStock(sku)).contains(3L);
        assertThat(stock.describeReservation(orderId)).isPresent();
        assertThat(stock.describeReservation(orderId).get()).containsEntry("status", "RESERVED");
    }

    @Test
    void paymentFailedReleasesReservationExactlyOnce() throws Exception {
        String sku = newSku(5);
        UUID orderId = UUID.randomUUID();
        send(Topics.ORDER_EVENTS, orderId, orderCreatedJson(orderId, sku, 2));
        awaitRecords(Topics.INVENTORY_EVENTS, stockReservedFor(orderId), 1);
        assertThat(stock.availableStock(sku)).contains(3L);

        send(Topics.PAYMENT_EVENTS, orderId, paymentFailedJson(orderId));
        send(Topics.PAYMENT_EVENTS, orderId, paymentFailedJson(orderId));

        await("stock to be restored", () -> stock.availableStock(sku).orElse(-1L) == 5L);
        assertThat(stock.describeReservation(orderId).get()).containsEntry("status", "RELEASED");
        assertThat(releaser.release(orderId)).isEqualTo(ReservationReleaser.Result.ALREADY_RELEASED);
        assertThat(stock.availableStock(sku)).contains(5L);
    }

    @Test
    void concurrentReservationsNeverOversell() throws Exception {
        int units = 5;
        int attempts = 20;
        String sku = newSku(units);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            UUID orderId = UUID.randomUUID();
            results.add(pool.submit(() -> {
                go.await();
                try {
                    ReservationOutcome outcome = stock.reserve(orderId, List.of(new StockLine(sku, 1)));
                    return outcome instanceof ReservationOutcome.Reserved ? "reserved" : "rejected";
                } catch (RuntimeException e) {
                    return "error";
                }
            }));
        }
        go.countDown();

        int reserved = 0;
        int rejected = 0;
        int errors = 0;
        for (Future<String> result : results) {
            switch (result.get(60, TimeUnit.SECONDS)) {
                case "reserved" -> reserved++;
                case "rejected" -> rejected++;
                default -> errors++;
            }
        }
        pool.shutdown();
        long available = stock.availableStock(sku).orElseThrow();
        System.out.printf("CONCURRENCY RESULT: units=%d attempts=%d reserved=%d rejected=%d errors=%d remaining=%d%n",
                units, attempts, reserved, rejected, errors, available);

        assertThat(reserved + available).isEqualTo(units);
        assertThat(available).isGreaterThanOrEqualTo(0L);
        assertThat(reserved + rejected + errors).isEqualTo(attempts);
    }

    private String newSku(int quantity) {
        String sku = "it-" + UUID.randomUUID().toString().substring(0, 8);
        seeder.seed(Map.of(sku, quantity));
        return sku;
    }

    private String orderCreatedJson(UUID orderId, String sku, int quantity) {
        BigDecimal price = new BigDecimal("10.00");
        OrderCreated payload = new OrderCreated(orderId, "cust-1", "US",
                List.of(new OrderCreated.Item(sku, quantity, price)),
                price.multiply(BigDecimal.valueOf(quantity)), "USD");
        return mapper.writeValueAsString(
                EventEnvelope.of(EventType.ORDER_CREATED, UUID.randomUUID(), "test", payload));
    }

    private String paymentFailedJson(UUID orderId) {
        return mapper.writeValueAsString(EventEnvelope.of(EventType.PAYMENT_FAILED, UUID.randomUUID(),
                "test", new PaymentFailed(orderId, "card declined")));
    }

    private static Predicate<String> stockReservedFor(UUID orderId) {
        return v -> v.contains(orderId.toString()) && v.contains("StockReserved");
    }

    private void send(String topic, UUID orderId, String json) throws Exception {
        template.send(topic, orderId.toString(), json).get(10, TimeUnit.SECONDS);
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

    private static List<ConsumerRecord<String, String>> awaitRecords(
            String topic, Predicate<String> matches, int count) {
        Map<String, Object> config = Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", "it-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest",
                "enable.auto.commit", "false",
                "key.deserializer", StringDeserializer.class.getName(),
                "value.deserializer", StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (matches.test(record.value())) {
                        found.add(record);
                    }
                }
                if (found.size() >= count) {
                    return found;
                }
            }
        }
        throw new AssertionError("Expected " + count + " matching record(s) on " + topic
                + " within 30 s, found " + found.size());
    }
}
