package com.bhavin.orders.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the replayer against a small fake of order-service, inventory-service and payment-service. */
class ReplayerTest {

    private HttpServer server;
    private final List<String> stockBodies = new CopyOnWriteArrayList<>();
    private final List<String> orderBodies = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> polls = new ConcurrentHashMap<>();
    private final AtomicInteger created = new AtomicInteger();

    @BeforeEach
    void startFakeServices() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin/stock", exchange -> {
            stockBodies.add(body(exchange));
            reply(exchange, 200, "{\"seeded\":1}");
        });
        server.createContext("/simulation", exchange -> reply(exchange, 200, "{\"failureRate\":0.2}"));
        server.createContext("/orders", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (exchange.getRequestMethod().equals("POST")) {
                orderBodies.add(body(exchange));
                String id = UUID.randomUUID().toString();
                polls.put(id, new AtomicInteger());
                int number = created.incrementAndGet();
                reply(exchange, 201, "{\"id\":\"" + id + "\",\"customerId\":\"u\",\"status\":\"CREATED\",\"n\":" + number + "}");
                return;
            }
            AtomicInteger seen = polls.get(path.substring("/orders/".length()));
            if (seen == null) {
                reply(exchange, 404, "{}");
                return;
            }
            String status = seen.incrementAndGet() < 2 ? "CREATED" : "CONFIRMED";
            reply(exchange, 200, "{\"id\":\"x\",\"status\":\"" + status + "\"}");
        });
        server.start();
    }

    @AfterEach
    void stopFakeServices() {
        server.stop(0);
    }

    private static String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void reply(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static ReplayOrder order(String id, String time, String sku, int quantity) {
        return new ReplayOrder(id, LocalDateTime.parse(time), "u-" + id, "SP",
                List.of(new ReplayOrder.Line(sku, quantity, "10.00")));
    }

    @Test
    void seedsStockSendsEveryOrderAndWaitsForFinalStatuses() throws Exception {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Replayer.Config config = new Replayer.Config(base, base, base, 1_000_000, 0.5,
                Duration.ofSeconds(10), Duration.ofMillis(20), 8);
        List<ReplayOrder> orders = List.of(
                order("a", "2017-11-24T22:00:00", "sku-x", 2),
                order("b", "2017-11-24T22:00:01", "sku-x", 4),
                order("c", "2017-11-24T22:00:02", "sku-y", 1));
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        Replayer.Summary summary = new Replayer(config, new PrintStream(captured, true))
                .run(orders, LocalDateTime.parse("2017-11-24T22:00:00"));

        assertThat(summary.orders()).isEqualTo(3);
        assertThat(summary.accepted()).isEqualTo(3);
        assertThat(summary.confirmed()).isEqualTo(3);
        assertThat(summary.unfinished()).isZero();
        assertThat(orderBodies).hasSize(3);
        assertThat(orderBodies.get(0)).contains("\"sku\":\"sku-x\"").contains("\"quantity\":2");
        assertThat(stockBodies).hasSize(1);
        assertThat(stockBodies.get(0)).contains("\"sku-x\":3").contains("\"sku-y\":1");
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("Replay summary").contains("SIMULATED");
    }

    @Test
    void reportsAnUnreachableOrderServiceClearly() {
        Replayer.Config config = new Replayer.Config("http://127.0.0.1:1", "http://127.0.0.1:1", "http://127.0.0.1:1",
                1, 0.9, Duration.ofSeconds(1), Duration.ofMillis(20), 4);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new Replayer(config, new PrintStream(new ByteArrayOutputStream()))
                        .run(List.of(order("a", "2017-11-24T22:00:00", "s", 1)), LocalDateTime.parse("2017-11-24T22:00:00")));
    }
}
