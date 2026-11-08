package com.bhavin.orders.replay;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Replays real Olist orders against order-service.
 *
 * <pre>
 * stats [data=DIR]
 * run   [date=2017-11-24] [from=00:00] [to=24:00] [speed=1] [stock-factor=0.9] [limit=0]
 *       [wait=120] [dry=false] [data=DIR] [order-url=..] [inventory-url=..] [payment-url=..]
 * </pre>
 */
public final class ReplayTool {

    private static final Path DEFAULT_DATA =
            Path.of(System.getProperty("user.home"), "Documents", "DS_project", "data", "olist");
    private static final Set<String> RUN_OPTIONS = Set.of("date", "from", "to", "speed", "stock-factor", "limit",
            "wait", "dry", "data", "order-url", "inventory-url", "payment-url");
    private static final Set<String> STATS_OPTIONS = Set.of("data");

    private ReplayTool() {
    }

    public static void main(String[] args) {
        try {
            execute(args, System.out);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    static void execute(String[] args, PrintStream out) throws Exception {
        if (args.length == 0) {
            out.println("Usage: stats [data=DIR] | run [date=..] [from=..] [to=..] [speed=..] [dry=true] ...");
            return;
        }
        switch (args[0]) {
            case "stats" -> stats(options(args, STATS_OPTIONS), out);
            case "run" -> run(options(args, RUN_OPTIONS), out);
            default -> throw new IllegalArgumentException("Unknown command '" + args[0] + "'. Use stats or run.");
        }
    }

    static Map<String, String> options(String[] args, Set<String> allowed) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq < 1) {
                throw new IllegalArgumentException("Options look like key=value, got '" + args[i] + "'");
            }
            String key = args[i].substring(0, eq);
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Unknown option '" + key + "'. Known: " + new java.util.TreeSet<>(allowed));
            }
            options.put(key, args[i].substring(eq + 1));
        }
        return options;
    }

    private static Path dataDir(Map<String, String> options) {
        return options.containsKey("data") ? Path.of(options.get("data")) : DEFAULT_DATA;
    }

    static void stats(Map<String, String> options, PrintStream out) throws Exception {
        OlistLoader.Loaded loaded = OlistLoader.load(dataDir(options));
        List<ReplayOrder> orders = loaded.orders();
        out.printf(Locale.ROOT, "Replayable orders: %,d (%,d without items skipped, %,d malformed rows skipped)%n",
                orders.size(), loaded.withoutItems(), loaded.malformed());
        if (orders.isEmpty()) {
            return;
        }
        out.println("Date range: " + orders.get(0).purchasedAt().toLocalDate()
                + " to " + orders.get(orders.size() - 1).purchasedAt().toLocalDate());

        Map<LocalDate, Long> perDay = orders.stream()
                .collect(Collectors.groupingBy(o -> o.purchasedAt().toLocalDate(), Collectors.counting()));
        List<Map.Entry<LocalDate, Long>> busiest = new ArrayList<>(perDay.entrySet());
        busiest.sort(Map.Entry.<LocalDate, Long>comparingByValue().reversed());
        out.println("Busiest days (replayable orders):");
        busiest.stream().limit(5).forEach(e -> out.printf(Locale.ROOT, "  %s  %,d%n", e.getKey(), e.getValue()));

        LocalDate top = busiest.get(0).getKey();
        Map<Integer, Long> perHour = new TreeMap<>(orders.stream()
                .filter(o -> o.purchasedAt().toLocalDate().equals(top))
                .collect(Collectors.groupingBy(o -> o.purchasedAt().getHour(), Collectors.counting())));
        out.println("Orders per hour on " + top + ":");
        perHour.forEach((hour, count) -> out.printf(Locale.ROOT, "  %02d:00  %,d%n", hour, count));
    }

    static void run(Map<String, String> options, PrintStream out) throws Exception {
        LocalDate date = LocalDate.parse(options.getOrDefault("date", "2017-11-24"));
        String fromText = options.getOrDefault("from", "00:00");
        String toText = options.getOrDefault("to", "24:00");
        LocalDateTime start = date.atTime(LocalTime.parse(fromText));
        LocalDateTime end = toText.equals("24:00") ? date.plusDays(1).atStartOfDay() : date.atTime(LocalTime.parse(toText));
        double speed = Double.parseDouble(options.getOrDefault("speed", "1"));
        double stockFactor = Double.parseDouble(options.getOrDefault("stock-factor", "0.9"));
        int limit = Integer.parseInt(options.getOrDefault("limit", "0"));
        int waitSeconds = Integer.parseInt(options.getOrDefault("wait", "120"));
        boolean dry = Boolean.parseBoolean(options.getOrDefault("dry", "false"));
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("'to' must be after 'from'");
        }
        if (speed <= 0 || stockFactor <= 0 || stockFactor > 10 || limit < 0 || waitSeconds <= 0) {
            throw new IllegalArgumentException("speed and stock-factor must be > 0 (stock-factor <= 10), limit >= 0, wait > 0");
        }

        List<ReplayOrder> window = OlistLoader.load(dataDir(options)).orders().stream()
                .filter(o -> !o.purchasedAt().isBefore(start) && o.purchasedAt().isBefore(end))
                .collect(Collectors.toCollection(ArrayList::new));
        if (limit > 0 && window.size() > limit) {
            window = new ArrayList<>(window.subList(0, limit));
        }
        if (window.isEmpty()) {
            throw new IllegalArgumentException("No orders between " + start + " and " + end);
        }

        long ideal = Replayer.dueNanos(start, window.get(window.size() - 1).purchasedAt(), speed);
        long skus = window.stream().flatMap(o -> o.lines().stream()).map(ReplayOrder.Line::sku).distinct().count();
        out.printf(Locale.ROOT, "Window %s to %s at %sx: %,d orders, %,d SKUs, ideal send time %.1f s%n",
                start, end, options.getOrDefault("speed", "1"), window.size(), skus, ideal / 1e9);
        if (dry) {
            out.println("dry=true: nothing was sent.");
            return;
        }

        Replayer.Config config = new Replayer.Config(
                options.getOrDefault("order-url", "http://localhost:8081"),
                options.getOrDefault("inventory-url", "http://localhost:8082"),
                options.getOrDefault("payment-url", "http://localhost:8083"),
                speed, stockFactor, Duration.ofSeconds(waitSeconds), Duration.ofMillis(500), 32);
        new Replayer(config, out).run(window, start);
    }
}
