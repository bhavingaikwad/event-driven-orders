package com.bhavin.orders.replay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads the three Olist CSV files and joins them into replayable orders. */
public final class OlistLoader {

    /**
     * @param orders       replayable orders, oldest first
     * @param withoutItems orders that have no item rows and so cannot be replayed
     * @param malformed    rows or orders dropped because a field was missing or looked wrong
     */
    public record Loaded(List<ReplayOrder> orders, int withoutItems, int malformed) {
    }

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern STATE = Pattern.compile("[A-Z]{2}");
    private static final Pattern PRICE = Pattern.compile("\\d{1,10}(\\.\\d{1,4})?");

    private static final class Accumulator {
        int quantity;
        final String price;

        Accumulator(String price) {
            this.price = price;
        }
    }

    private OlistLoader() {
    }

    /** Splits one CSV line and removes the quotes. These files never contain a comma inside a field. */
    static String[] split(String line) {
        String[] parts = line.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].replace("\"", "").trim();
        }
        return parts;
    }

    public static Loaded load(Path dir) throws IOException {
        int[] malformed = {0};
        Map<String, String[]> customers = readCustomers(file(dir, "olist_customers_dataset.csv"), malformed);
        Map<String, Map<String, Accumulator>> items = readItems(file(dir, "olist_order_items_dataset.csv"), malformed);

        List<ReplayOrder> orders = new ArrayList<>();
        int withoutItems = 0;
        try (Stream<String> lines = Files.lines(file(dir, "olist_orders_dataset.csv"), StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines.skip(1)::iterator) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = split(line);
                if (f.length < 4 || !ID.matcher(f[0]).matches()) {
                    malformed[0]++;
                    continue;
                }
                Map<String, Accumulator> products = items.get(f[0]);
                if (products == null) {
                    withoutItems++;
                    continue;
                }
                String[] customer = customers.get(f[1]);
                LocalDateTime purchasedAt = parseTime(f[3]);
                if (customer == null || purchasedAt == null) {
                    malformed[0]++;
                    continue;
                }
                List<ReplayOrder.Line> orderLines = new ArrayList<>();
                products.forEach((sku, acc) -> orderLines.add(new ReplayOrder.Line(sku, acc.quantity, acc.price)));
                orders.add(new ReplayOrder(f[0], purchasedAt, customer[0], customer[1], orderLines));
            }
        }
        orders.sort(Comparator.comparing(ReplayOrder::purchasedAt).thenComparing(ReplayOrder::orderId));
        return new Loaded(orders, withoutItems, malformed[0]);
    }

    private static Path file(Path dir, String name) {
        Path path = dir.resolve(name);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Missing file " + path + " (use data=DIRECTORY to point at the Olist CSV files)");
        }
        return path;
    }

    /** customer_id to {customer_unique_id, customer_state}. */
    private static Map<String, String[]> readCustomers(Path path, int[] malformed) throws IOException {
        Map<String, String[]> customers = new HashMap<>();
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines.skip(1)::iterator) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = split(line);
                if (f.length < 5 || !ID.matcher(f[0]).matches() || !ID.matcher(f[1]).matches()
                        || !STATE.matcher(f[4]).matches()) {
                    malformed[0]++;
                    continue;
                }
                customers.put(f[0], new String[] {f[1], f[4]});
            }
        }
        return customers;
    }

    /** order_id to its products. Several rows for the same product become one line with a larger quantity. */
    private static Map<String, Map<String, Accumulator>> readItems(Path path, int[] malformed) throws IOException {
        Map<String, Map<String, Accumulator>> items = new HashMap<>();
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines.skip(1)::iterator) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = split(line);
                if (f.length < 7 || !ID.matcher(f[0]).matches() || !ID.matcher(f[2]).matches()
                        || !PRICE.matcher(f[5]).matches()) {
                    malformed[0]++;
                    continue;
                }
                items.computeIfAbsent(f[0], k -> new LinkedHashMap<>())
                        .computeIfAbsent(f[2], k -> new Accumulator(f[5]))
                        .quantity++;
            }
        }
        return items;
    }

    private static LocalDateTime parseTime(String text) {
        try {
            return LocalDateTime.parse(text.replace(' ', 'T'));
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
