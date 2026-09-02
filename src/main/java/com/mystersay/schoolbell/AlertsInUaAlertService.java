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

/** Polls alerts.in.ua active alerts API using a user-supplied bearer token. */
public final class AlertsInUaAlertService implements AutoCloseable {
    private static final URI ALERTS_URI = URI.create("https://api.alerts.in.ua/v1/alerts/active.json");
    // alerts.in.ua hard limit is 12 requests/minute/IP. Six seconds keeps us below it (10/minute).
    private static final long POLL_SECONDS = 6;

    public record AlertEvent(boolean active, String oblast, String district, String since, boolean initial) {}

    private final Supplier<Boolean> enabledSupplier;
    private final Supplier<String> tokenSupplier;
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

    public AlertsInUaAlertService(Supplier<Boolean> enabledSupplier,
                                  Supplier<String> tokenSupplier,
                                  Supplier<String> oblastSupplier,
                                  Supplier<String> districtSupplier,
                                  Consumer<AlertEvent> eventConsumer,
                                  Consumer<String> logConsumer) {
        this.enabledSupplier = Objects.requireNonNull(enabledSupplier);
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier);
        this.oblastSupplier = Objects.requireNonNull(oblastSupplier);
        this.districtSupplier = Objects.requireNonNull(districtSupplier);
        this.eventConsumer = Objects.requireNonNull(eventConsumer);
        this.logConsumer = Objects.requireNonNull(logConsumer);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SchoolBell-alerts.in.ua");
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
        if (running) executor.execute(this::safeCheck);
    }

    private void safeCheck() {
        try {
            check();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            reportError("alerts.in.ua: " + readable(ex));
        }
    }

    private void check() throws Exception {
        if (!Boolean.TRUE.equals(enabledSupplier.get())) {
            lastActive = null;
            lastSelection = "";
            return;
        }

        String token = clean(tokenSupplier.get());
        String oblast = clean(oblastSupplier.get());
        String district = clean(districtSupplier.get());
        if (token.isBlank()) {
            reportError("alerts.in.ua: API токен не задано.");
            lastActive = null;
            return;
        }
        if (oblast.isBlank() || district.isBlank()) return;

        String selection = oblast + "|" + district + "|" + token.hashCode();
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
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", "SchoolBell/1.4 (+alerts.in.ua)")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IllegalStateException("API токен відхилено (HTTP " + response.statusCode() + ")");
        }
        if (response.statusCode() == 429) {
            throw new IllegalStateException("перевищено ліміт API (HTTP 429)");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " від API тривог");
        }

        AlertMatch match = parseState(response.body(), oblast, district);
        Boolean previous = lastActive;
        lastActive = match.active;
        lastError = "";

        if (previous == null) {
            eventConsumer.accept(new AlertEvent(match.active, oblast, district, match.since, true));
            return;
        }
        if (previous.booleanValue() != match.active) {
            eventConsumer.accept(new AlertEvent(match.active, oblast, district, match.since, false));
        }
    }

    @SuppressWarnings("unchecked")
    static AlertMatch parseState(String json, String selectedOblast, String selectedDistrict) {
        Object parsed = SimpleJson.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Некоректна відповідь /v1/alerts/active.json");
        }
        Object alertsRaw = root.get("alerts");
        if (!(alertsRaw instanceof List<?> alerts)) return new AlertMatch(false, "");

        boolean wholeOblast = UkraineRegionCatalog.WHOLE_OBLAST.equals(selectedDistrict);
        String since = "";
        for (Object item : alerts) {
            if (!(item instanceof Map<?, ?> row)) continue;
            if (!"air_raid".equalsIgnoreCase(string(row.get("alert_type")))) continue;

            String locationOblast = string(row.get("location_oblast"));
            String locationTitle = string(row.get("location_title"));
            String locationType = string(row.get("location_type"));
            String locationRaion = string(row.get("location_raion"));

            // For oblast-level alerts some responses may repeat the oblast only in location_title.
            boolean sameOblast = sameOblast(selectedOblast, locationOblast)
                    || ("oblast".equalsIgnoreCase(locationType) && sameOblast(selectedOblast, locationTitle));
            if (!sameOblast) continue;

            boolean applies;
            if (wholeOblast) {
                applies = true;
            } else if ("oblast".equalsIgnoreCase(locationType)) {
                applies = true; // Oblast-wide alert also applies to every selected district.
            } else {
                applies = sameDistrict(selectedDistrict, locationRaion)
                        || ("raion".equalsIgnoreCase(locationType) && sameDistrict(selectedDistrict, locationTitle));
            }

            if (applies) {
                since = string(row.get("started_at"));
                return new AlertMatch(true, since);
            }
        }
        return new AlertMatch(false, since);
    }

    private static boolean sameOblast(String a, String b) {
        String x = norm(a);
        String y = norm(b);
        if (x.equals(y)) return true;
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
    private static String readable(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }

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

    record AlertMatch(boolean active, String since) {}
}
