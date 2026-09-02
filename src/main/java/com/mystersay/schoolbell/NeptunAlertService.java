package com.mystersay.schoolbell;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Polls NEPTUN's read-only alert endpoint at the documented maximum REST frequency (once / 5 sec). */
public final class NeptunAlertService implements AutoCloseable {
    private static final URI ALERTS_URI = URI.create("https://neptun.in.ua/api/v1/alerts");
    private static final long POLL_SECONDS = 5;

    public record AlertEvent(boolean active, String oblast, String district, String since, boolean initial) {}

    private final Supplier<Boolean> enabledSupplier;
    private final Supplier<String> oblastSupplier;
    private final Supplier<String> districtSupplier;
    private final Consumer<AlertEvent> eventConsumer;
    private final Consumer<String> logConsumer;
    private final HttpClient client;
    private final ScheduledExecutorService executor;

    private volatile Boolean lastActive;
    private volatile String lastSelection = "";
    private volatile boolean running;
    private volatile String lastError = "";
    private volatile long lastErrorAt;
    private volatile long lastRequestAt;

    public NeptunAlertService(Supplier<Boolean> enabledSupplier,
                              Supplier<String> oblastSupplier,
                              Supplier<String> districtSupplier,
                              Consumer<AlertEvent> eventConsumer,
                              Consumer<String> logConsumer) {
        this.enabledSupplier = Objects.requireNonNull(enabledSupplier);
        this.oblastSupplier = Objects.requireNonNull(oblastSupplier);
        this.districtSupplier = Objects.requireNonNull(districtSupplier);
        this.eventConsumer = Objects.requireNonNull(eventConsumer);
        this.logConsumer = Objects.requireNonNull(logConsumer);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SchoolBell-NEPTUN-alerts");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        executor.scheduleWithFixedDelay(this::safeCheck, 0, POLL_SECONDS, TimeUnit.SECONDS);
    }

    public void resetState() {
        lastActive = null;
        lastSelection = "";
    }

    public void checkSoon() {
        if (!running) return;
        executor.execute(this::safeCheck);
    }

    private void safeCheck() {
        try {
            check();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            reportError("NEPTUN: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    private void check() throws Exception {
        if (!Boolean.TRUE.equals(enabledSupplier.get())) {
            lastActive = null;
            lastSelection = "";
            return;
        }

        String oblast = clean(oblastSupplier.get());
        String district = clean(districtSupplier.get());
        if (oblast.isBlank() || district.isBlank()) return;

        String selection = oblast + "|" + district;
        if (!selection.equals(lastSelection)) {
            lastSelection = selection;
            lastActive = null;
        }

        long now = System.currentTimeMillis();
        if (lastRequestAt > 0 && now - lastRequestAt < TimeUnit.SECONDS.toMillis(POLL_SECONDS)) return;
        lastRequestAt = now;

        HttpRequest request = HttpRequest.newBuilder(ALERTS_URI)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", "SchoolBell/1.3 (+NEPTUN-alerts)")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " від API тривог");
        }

        AlertMatch match = parseState(response.body(), oblast, district);
        Boolean previous = lastActive;
        lastActive = match.active;
        lastError = "";

        if (previous == null) {
            // First successful snapshot is still useful: if the selected location is already under alert,
            // notify immediately; a calm startup does not play an all-clear sound.
            if (match.active) eventConsumer.accept(new AlertEvent(true, oblast, district, match.since, true));
            else eventConsumer.accept(new AlertEvent(false, oblast, district, match.since, true));
            return;
        }
        if (previous.booleanValue() != match.active) {
            eventConsumer.accept(new AlertEvent(match.active, oblast, district, match.since, false));
        }
    }

    @SuppressWarnings("unchecked")
    private static AlertMatch parseState(String json, String selectedOblast, String selectedDistrict) {
        Object parsed = SimpleJson.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) throw new IllegalArgumentException("Некоректна відповідь /api/v1/alerts");

        boolean wholeOblast = UkraineRegionCatalog.WHOLE_OBLAST.equals(selectedDistrict);
        boolean active = false;
        String since = "";

        Object raionsRaw = root.get("raions");
        if (raionsRaw instanceof List<?> raions) {
            for (Object item : raions) {
                if (!(item instanceof Map<?, ?> row)) continue;
                String name = string(row.get("name"));
                String oblast = string(row.get("oblast"));
                if (!sameOblast(selectedOblast, oblast)) continue;
                if (wholeOblast || sameDistrict(selectedDistrict, name)) {
                    active = true;
                    since = string(row.get("since"));
                    break;
                }
            }
        }

        Object oblastsRaw = root.get("oblasts");
        if (oblastsRaw instanceof List<?> oblasts) {
            for (Object item : oblasts) {
                if (!(item instanceof Map<?, ?> row)) continue;
                String name = string(row.get("name"));
                if (sameOblast(selectedOblast, name)) {
                    active = true;
                    if (since.isBlank()) since = string(row.get("since"));
                    break;
                }
            }
        }
        return new AlertMatch(active, since);
    }

    private static boolean sameOblast(String a, String b) {
        String x = norm(a);
        String y = norm(b);
        if (x.equals(y)) return true;
        // NEPTUN can use short display forms for the two special cities / Crimea.
        return (x.equals("м київ") && y.equals("київ")) || (y.equals("м київ") && x.equals("київ"))
                || (x.equals("ар крим") && y.contains("крим")) || (y.equals("ар крим") && x.contains("крим"));
    }

    private static boolean sameDistrict(String a, String b) {
        String x = norm(a);
        String y = norm(b);
        if (x.equals(y)) return true;
        return aliases(x, y, "самарівський район", "новомосковський район")
                || aliases(x, y, "берестинський район", "красноградський район")
                || aliases(x, y, "шептицький район", "червоноградський район")
                || aliases(x, y, "сіверськодонецький район", "сєвєродонецький район");
    }

    private static boolean aliases(String x, String y, String a, String b) {
        return (x.equals(a) && y.equals(b)) || (x.equals(b) && y.equals(a));
    }

    private static String norm(String s) {
        return clean(s).toLowerCase(Locale.ROOT)
                .replace('’', '\'').replace('ʼ', '\'').replace('`', '\'')
                .replaceAll("[\\s.]+", " ").trim();
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private void reportError(String message) {
        long now = System.currentTimeMillis();
        if (!message.equals(lastError) || now - lastErrorAt > 60_000) {
            lastError = message;
            lastErrorAt = now;
            logConsumer.accept(message);
        }
    }

    @Override public synchronized void close() {
        running = false;
        executor.shutdownNow();
    }

    private record AlertMatch(boolean active, String since) {}
}
