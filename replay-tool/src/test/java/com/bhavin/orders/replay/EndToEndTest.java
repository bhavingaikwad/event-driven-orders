package com.bhavin.orders.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Black-box test against the RUNNING system (infra, order-, inventory- and payment-service must be up).
 * Skipped unless E2E=true. Stock and payment outcomes are simulated.
 */
@EnabledIfEnvironmentVariable(named = "E2E", matches = "true")
class EndToEndTest {

    private static final String ORDER_URL = System.getenv().getOrDefault("ORDER_URL", "http://localhost:8081");
    private static final String INVENTORY_URL = System.getenv().getOrDefault("INVENTORY_URL", "http://localhost:8082");
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([A-Z_]+)\"");

    private static final int STOCK = 10;
    private static final int ORDERS = 30;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void neverConfirmsMoreOrdersThanThereIsStockAndEveryOrderFinishes() throws Exception {
        String sku = "e2e-" + UUID.randomUUID();

        HttpResponse<String> seeded = send(HttpRequest.newBuilder(URI.create(INVENTORY_URL + "/admin/stock"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"" + sku + "\":" + STOCK + "}")));
        assertThat(seeded.statusCode()).as("seed response: " + seeded.body()).isEqualTo(200);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<String>> created = new ArrayList<>();
        for (int i = 0; i < ORDERS; i++) {
            created.add(pool.submit(() -> createOrder(sku)));
        }
        List<String> ids = new ArrayList<>();
        for (Future<String> f : created) {
            ids.add(f.get());
        }
        pool.shutdown();

        Map<String, String> finalStatus = new HashMap<>();
        long deadline = System.currentTimeMillis() + 90_000;
        while (finalStatus.size() < ORDERS && System.currentTimeMillis() < deadline) {
            for (String id : ids) {
                if (finalStatus.containsKey(id)) {
                    continue;
                }
                String status = statusOf(id);
                if (status.equals("CONFIRMED") || status.equals("CANCELLED")) {
                    finalStatus.put(id, status);
                }
            }
            Thread.sleep(250);
        }

        long confirmed = finalStatus.values().stream().filter("CONFIRMED"::equals).count();
        long cancelled = finalStatus.values().stream().filter("CANCELLED"::equals).count();
        System.out.println("E2E RESULT: sku=" + sku + " stock=" + STOCK + " orders=" + ORDERS
                + " confirmed=" + confirmed + " cancelled=" + cancelled
                + " notFinished=" + (ORDERS - finalStatus.size()));

        assertThat(finalStatus).as("every order must reach CONFIRMED or CANCELLED").hasSize(ORDERS);
        assertThat(confirmed).as("confirmed orders must never exceed stock").isLessThanOrEqualTo(STOCK);
    }

    private String createOrder(String sku) throws Exception {
        String body = ("{\"customerId\":\"e2e-customer\",\"customerRegion\":\"US\",\"currency\":\"USD\","
                + "\"items\":[{\"sku\":\"%s\",\"quantity\":1,\"unitPrice\":10.00}]}").formatted(sku);
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(ORDER_URL + "/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        assertThat(response.statusCode()).as("create order response: " + response.body()).isEqualTo(201);
        Matcher m = ID.matcher(response.body());
        assertThat(m.find()).as("order id in: " + response.body()).isTrue();
        return m.group(1);
    }

    private String statusOf(String id) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(ORDER_URL + "/orders/" + id)).GET());
        Matcher m = STATUS.matcher(response.body());
        assertThat(m.find()).as("status in: " + response.body()).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
