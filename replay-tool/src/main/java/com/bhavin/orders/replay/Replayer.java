package com.bhavin.orders.replay;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends real orders to order-service at their original arrival times divided by the speed factor,
 * then waits for every saga to finish and prints what was measured.
 */
public final class Replayer {

    public record Config(String orderUrl, String inventoryUrl, String paymentUrl, double speed,
                         double stockFactor, Duration waitForSagas, Duration pollInterval, int maxInFlight) {
    }

    public record Summary(int orders, int accepted, int failedRequests, int confirmed, int cancelled,
                          int unfinished) {
    }

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([A-Z_]+)\"");
    private static final String UNKNOWN_ORDER = "00000000-0000-0000-0000-000000000000";
    private static final int STOCK_CHUNK = 500;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final class Tracked {
        final ReplayOrder order;
        volatile int httpStatus = -1;
        volatile String id;
        volatile long sentAt;
        volatile long lagNanos;
        volatile long latencyNanos;
        volatile String finalStatus;
        volatile long finalAt;

        Tracked(ReplayOrder order) {
            this.order = order;
        }
    }

    private final Config config;
    private final PrintStream out;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final Semaphore inFlight;
    private final Semaphore pollPermits;
    private final AtomicReference<String> firstError = new AtomicReference<>();
    private final Queue<Tracked> open = new ConcurrentLinkedQueue<>();
    private volatile boolean sendingDone;
    private volatile long deadline = Long.MAX_VALUE;
    private volatile long t0;

    public Replayer(Config config, PrintStream out) {
        this.config = config;
        this.out = out;
        this.inFlight = new Semaphore(config.maxInFlight());
        this.pollPermits = new Semaphore(Math.max(1, config.maxInFlight() / 2));
    }

    /** Real time after the window start at which an order is due, shortened by the speed factor. */
    static long dueNanos(LocalDateTime windowStart, LocalDateTime purchasedAt, double speed) {
        return (long) (Duration.between(windowStart, purchasedAt).toNanos() / speed);
    }

    public Summary run(List<ReplayOrder> orders, LocalDateTime windowStart) throws Exception {
        preflight();
        seedStock(orders);
        ExecutorService poller = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "status-poller");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<?> polling = poller.submit(this::pollLoop);
            List<Tracked> tracked = send(orders, windowStart);
            deadline = System.nanoTime() + config.waitForSagas().toNanos();
            sendingDone = true;
            polling.get();
            return report(tracked, orders, windowStart);
        } finally {
            poller.shutdownNow();
        }
    }

    private void preflight() throws InterruptedException {
        try {
            http.send(get(config.orderUrl() + "/orders/" + UNKNOWN_ORDER), BodyHandlers.discarding());
        } catch (IOException e) {
            throw new IllegalStateException("order-service is not reachable at " + config.orderUrl()
                    + " (is it running?)");
        }
        try {
            HttpResponse<String> sim = http.send(get(config.paymentUrl() + "/simulation"), BodyHandlers.ofString());
            out.println("Payment simulation (SIMULATED): " + sim.body().trim());
        } catch (IOException e) {
            out.println("Payment simulation: payment-service not reachable at " + config.paymentUrl());
        }
    }

    private void seedStock(List<ReplayOrder> orders) throws IOException, InterruptedException {
        Map<String, Integer> demand = new TreeMap<>();
        for (ReplayOrder order : orders) {
            for (ReplayOrder.Line line : order.lines()) {
                demand.merge(line.sku(), line.quantity(), Integer::sum);
            }
        }
        long units = 0;
        StringBuilder body = new StringBuilder("{");
        int inChunk = 0;
        for (Map.Entry<String, Integer> entry : demand.entrySet()) {
            int stock = Math.max(1, (int) Math.floor(entry.getValue() * config.stockFactor()));
            units += stock;
            if (inChunk > 0) {
                body.append(',');
            }
            body.append('"').append(entry.getKey()).append("\":").append(stock);
            if (++inChunk == STOCK_CHUNK) {
                putStock(body.append('}').toString());
                body = new StringBuilder("{");
                inChunk = 0;
            }
        }
        if (inChunk > 0) {
            putStock(body.append('}').toString());
        }
        out.printf(Locale.ROOT, "Seeded %,d SKUs with %,d units of SIMULATED stock (stock factor %.2f of the window's demand)%n",
                demand.size(), units, config.stockFactor());
    }

    private void putStock(String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.inventoryUrl() + "/admin/stock"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("inventory-service is not reachable at " + config.inventoryUrl()
                    + " (is it running?)");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Stock seeding failed: HTTP " + response.statusCode() + " " + response.body());
        }
    }

    private List<Tracked> send(List<ReplayOrder> orders, LocalDateTime windowStart) throws InterruptedException {
        List<Tracked> tracked = new ArrayList<>(orders.size());
        List<CompletableFuture<?>> pending = new ArrayList<>(orders.size());
        int step = Math.max(1, orders.size() / 10);
        out.println("Sending " + orders.size() + " orders ...");
        t0 = System.nanoTime();
        for (int i = 0; i < orders.size(); i++) {
            ReplayOrder order = orders.get(i);
            Tracked t = new Tracked(order);
            tracked.add(t);
            long due = dueNanos(windowStart, order.purchasedAt(), config.speed());
            long wait = due - (System.nanoTime() - t0);
            if (wait > 0) {
                TimeUnit.NANOSECONDS.sleep(wait);
            }
            inFlight.acquire();
            long started = System.nanoTime();
            t.sentAt = started - t0;
            t.lagNanos = Math.max(0, t.sentAt - due);
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.orderUrl() + "/orders"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(order.toJson()))
                    .build();
            pending.add(http.sendAsync(request, BodyHandlers.ofString()).whenComplete((response, error) -> {
                inFlight.release();
                t.latencyNanos = System.nanoTime() - started;
                if (error != null) {
                    firstError.compareAndSet(null, String.valueOf(error.getMessage()));
                    return;
                }
                t.httpStatus = response.statusCode();
                if (response.statusCode() == 201) {
                    t.id = first(ID, response.body());
                    if (t.id != null) {
                        open.add(t);
                    }
                } else {
                    firstError.compareAndSet(null, "HTTP " + response.statusCode() + " " + response.body());
                }
            }).exceptionally(error -> null));
            if ((i + 1) % step == 0) {
                out.println("  sent " + (i + 1) + "/" + orders.size());
            }
        }
        CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0])).join();
        return tracked;
    }

    /** Runs next to the sender: keeps asking order-service for the status of every order that is not final yet. */
    private void pollLoop() {
        long lastReport = System.nanoTime();
        boolean waiting = false;
        try {
            while (!(sendingDone && (open.isEmpty() || System.nanoTime() > deadline))) {
                List<Tracked> snapshot = new ArrayList<>(open);
                List<CompletableFuture<?>> polls = new ArrayList<>(snapshot.size());
                for (Tracked t : snapshot) {
                    pollPermits.acquire();
                    polls.add(http.sendAsync(get(config.orderUrl() + "/orders/" + t.id), BodyHandlers.ofString())
                            .whenComplete((response, error) -> {
                                pollPermits.release();
                                if (error == null && response.statusCode() == 200) {
                                    String status = first(STATUS, response.body());
                                    if ("CONFIRMED".equals(status) || "CANCELLED".equals(status)) {
                                        t.finalAt = System.nanoTime() - t0;
                                        t.finalStatus = status;
                                    }
                                }
                            }).exceptionally(error -> null));
                }
                CompletableFuture.allOf(polls.toArray(new CompletableFuture<?>[0])).join();
                open.removeIf(t -> t.finalStatus != null);
                if (sendingDone && !waiting) {
                    waiting = true;
                    lastReport = System.nanoTime();
                } else if (waiting && !open.isEmpty() && System.nanoTime() - lastReport > TimeUnit.SECONDS.toNanos(5)) {
                    out.println("  waiting for " + open.size() + " orders to finish ...");
                    lastReport = System.nanoTime();
                }
                TimeUnit.NANOSECONDS.sleep(config.pollInterval().toNanos());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Summary report(List<Tracked> tracked, List<ReplayOrder> orders, LocalDateTime windowStart) {
        int accepted = 0;
        int confirmed = 0;
        int cancelled = 0;
        int unfinished = 0;
        Map<Integer, Integer> httpCodes = new TreeMap<>();
        List<Long> latencies = new ArrayList<>();
        List<Long> lags = new ArrayList<>();
        List<Long> toFinal = new ArrayList<>();
        long lastSent = 0;
        for (Tracked t : tracked) {
            httpCodes.merge(t.httpStatus, 1, Integer::sum);
            latencies.add(t.latencyNanos);
            lags.add(t.lagNanos);
            lastSent = Math.max(lastSent, t.sentAt);
            if (t.httpStatus != 201 || t.id == null) {
                continue;
            }
            accepted++;
            if ("CONFIRMED".equals(t.finalStatus)) {
                confirmed++;
            } else if ("CANCELLED".equals(t.finalStatus)) {
                cancelled++;
            } else {
                unfinished++;
            }
            if (t.finalStatus != null) {
                toFinal.add(t.finalAt - t.sentAt);
            }
        }
        int failed = tracked.size() - accepted;
        long ideal = orders.isEmpty() ? 0
                : dueNanos(windowStart, orders.get(orders.size() - 1).purchasedAt(), config.speed());

        long[] lat = Metrics.sorted(latencies);
        long[] lag = Metrics.sorted(lags);
        long[] fin = Metrics.sorted(toFinal);
        out.println();
        out.println("=== Replay summary (order times are REAL, stock and payment outcomes are SIMULATED) ===");
        out.printf(Locale.ROOT, "orders sent        %,d at %sx%n", tracked.size(), trim(config.speed()));
        out.printf(Locale.ROOT, "send duration      %.1f s (ideal %.1f s)%n", lastSent / 1e9, ideal / 1e9);
        out.printf(Locale.ROOT, "http results       %s%n", describeCodes(httpCodes));
        out.printf(Locale.ROOT, "request latency    p50 %d ms, p95 %d ms, p99 %d ms, max %d ms%n",
                ms(Metrics.percentile(lat, 50)), ms(Metrics.percentile(lat, 95)),
                ms(Metrics.percentile(lat, 99)), ms(Metrics.percentile(lat, 100)));
        out.printf(Locale.ROOT, "send lag           p95 %d ms, max %d ms (how late the tool sent versus the plan)%n",
                ms(Metrics.percentile(lag, 95)), ms(Metrics.percentile(lag, 100)));
        out.printf(Locale.ROOT, "final status       CONFIRMED %,d, CANCELLED %,d, unfinished %,d%n", confirmed, cancelled, unfinished);
        out.printf(Locale.ROOT, "time to final      p50 %.2f s, p95 %.2f s, p99 %.2f s, max %.2f s (from send to the poll that saw the final status, +/- one poll cycle)%n",
                Metrics.percentile(fin, 50) / 1e9, Metrics.percentile(fin, 95) / 1e9,
                Metrics.percentile(fin, 99) / 1e9, Metrics.percentile(fin, 100) / 1e9);
        if (firstError.get() != null) {
            out.println("first error        " + firstError.get());
        }
        return new Summary(tracked.size(), accepted, failed, confirmed, cancelled, unfinished);
    }

    private static String describeCodes(Map<Integer, Integer> codes) {
        Map<String, Integer> labeled = new LinkedHashMap<>();
        codes.forEach((code, count) -> labeled.put(code == -1 ? "no response" : String.valueOf(code), count));
        StringBuilder text = new StringBuilder();
        labeled.forEach((code, count) -> text.append(text.length() == 0 ? "" : ", ").append(code).append(" x").append(count));
        return text.toString();
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static long ms(long nanos) {
        return Math.round(nanos / 1e6);
    }

    private static String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
    }
}
