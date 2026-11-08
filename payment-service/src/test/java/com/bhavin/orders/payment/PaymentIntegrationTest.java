package com.bhavin.orders.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhavin.orders.contracts.EventEnvelope;
import com.bhavin.orders.contracts.EventType;
import com.bhavin.orders.contracts.Topics;
import com.bhavin.orders.contracts.events.OrderCreated;
import com.bhavin.orders.contracts.events.StockReserved;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Real Postgres and real Kafka in containers, the real payment-service context on top.
 * The test plays order-service and inventory-service by publishing their events to the topics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "consumer.retry.max-retries=3",
        "consumer.retry.initial-interval-ms=50",
        "consumer.retry.max-interval-ms=100",
        "payment.breaker.wait-open-ms=10000",
        "management.tracing.sampling.probability=0",
        "logging.file.name=target/payment-service-it.log"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentIntegrationTest {

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
                new NewTopic(Topics.INVENTORY_EVENTS + ".DLT", 1, (short) 1));
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
    PaymentService payments;
    @Autowired
    PaymentRepository repository;
    @Autowired
    PaymentSimulator simulator;
    @Autowired
    GatewayCircuitBreaker breaker;
    @Autowired
    KafkaTemplate<String, String> template;
    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void resetSimulation() {
        simulator.setFailureRate(0.0);
        simulator.setGatewayDown(false);
        simulator.setGatewayLatencyMs(0);
        simulator.setTransientFailures(0);
    }

    @Test
    @Order(1)
    void approvedPaymentPublishesPaymentSucceeded() throws Exception {
        UUID orderId = UUID.randomUUID();
        orderCreated(orderId);

        send(Topics.INVENTORY_EVENTS, orderId, stockReservedJson(orderId));

        awaitRecords(Topics.PAYMENT_EVENTS, paymentEvent(orderId, "PaymentSucceeded"), 1);
        Payment payment = payments.find(orderId).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.amount()).isEqualByComparingTo("20.00");
        assertThat(payment.currency()).isEqualTo("USD");
    }

    @Test
    @Order(2)
    void declinedPaymentPublishesPaymentFailed() throws Exception {
        simulator.setFailureRate(1.0);
        UUID orderId = UUID.randomUUID();
        orderCreated(orderId);

        send(Topics.INVENTORY_EVENTS, orderId, stockReservedJson(orderId));

        ConsumerRecord<String, String> failed =
                awaitRecords(Topics.PAYMENT_EVENTS, paymentEvent(orderId, "PaymentFailed"), 1).get(0);
        assertThat(failed.value()).contains("Card declined (simulated)");
        assertThat(payments.find(orderId).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @Order(3)
    void duplicateStockReservedChargesOnlyOnce() throws Exception {
        UUID orderId = UUID.randomUUID();
        orderCreated(orderId);
        String json = stockReservedJson(orderId);
        send(Topics.INVENTORY_EVENTS, orderId, json);
        send(Topics.INVENTORY_EVENTS, orderId, json);

        List<ConsumerRecord<String, String>> published =
                awaitRecords(Topics.PAYMENT_EVENTS, paymentEvent(orderId, "PaymentSucceeded"), 2);
        String paymentId = payments.find(orderId).orElseThrow().paymentId().toString();
        assertThat(published).allSatisfy(record -> assertThat(record.value()).contains(paymentId));
    }

    @Test
    @Order(4)
    void transientFailuresRecoverThroughRetries() throws Exception {
        simulator.setTransientFailures(2);
        UUID orderId = UUID.randomUUID();
        orderCreated(orderId);

        send(Topics.INVENTORY_EVENTS, orderId, stockReservedJson(orderId));

        awaitRecords(Topics.PAYMENT_EVENTS, paymentEvent(orderId, "PaymentSucceeded"), 1);
        assertThat(simulator.transientFailuresLeft()).isZero();
    }

    @Test
    @Order(5)
    void exhaustedRetriesSendTheEventToTheDeadLetterTopic() throws Exception {
        simulator.setTransientFailures(100);
        UUID orderId = UUID.randomUUID();
        orderCreated(orderId);

        send(Topics.INVENTORY_EVENTS, orderId, stockReservedJson(orderId));

        awaitRecords(Topics.INVENTORY_EVENTS + ".DLT", v -> v.contains(orderId.toString()), 1);
        assertThat(payments.find(orderId)).isEmpty();
    }

    @Test
    @Order(6)
    void unknownOrderAmountExhaustsRetriesThenGoesToDeadLetterTopic() throws Exception {
        UUID orderId = UUID.randomUUID();

        send(Topics.INVENTORY_EVENTS, orderId, stockReservedJson(orderId));

        awaitRecords(Topics.INVENTORY_EVENTS + ".DLT", v -> v.contains(orderId.toString()), 1);
        assertThat(payments.find(orderId)).isEmpty();
    }

    @Test
    @Order(99)
    void gatewayOutageOpensTheBreakerAndFastFailsThePayment() throws Exception {
        simulator.setGatewayDown(true);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        orderCreated(first);
        orderCreated(second);

        send(Topics.INVENTORY_EVENTS, first, stockReservedJson(first));
        send(Topics.INVENTORY_EVENTS, second, stockReservedJson(second));

        ConsumerRecord<String, String> failed =
                awaitRecords(Topics.PAYMENT_EVENTS, paymentEvent(second, "PaymentFailed"), 1).get(0);
        assertThat(failed.value()).contains("circuit open");
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        Payment payment = payments.find(second).orElseThrow();
        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.failureReason()).contains("circuit open");
    }

    private void orderCreated(UUID orderId) throws Exception {
        BigDecimal price = new BigDecimal("10.00");
        OrderCreated payload = new OrderCreated(orderId, "cust-1", "US",
                List.of(new OrderCreated.Item("sku-1", 2, price)), new BigDecimal("20.00"), "USD");
        String json = mapper.writeValueAsString(
                EventEnvelope.of(EventType.ORDER_CREATED, UUID.randomUUID(), "test", payload));
        send(Topics.ORDER_EVENTS, orderId, json);
        await("order amount to be stored", () -> repository.findOrderAmount(orderId).isPresent());
    }

    private String stockReservedJson(UUID orderId) {
        return mapper.writeValueAsString(EventEnvelope.of(EventType.STOCK_RESERVED, UUID.randomUUID(),
                "test", new StockReserved(orderId, UUID.randomUUID())));
    }

    private static Predicate<String> paymentEvent(UUID orderId, String type) {
        return v -> v.contains(orderId.toString()) && v.contains(type);
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
