package com.mystersay.schoolbell;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.util.Properties;

public final class ScheduleStore {
    private final Path configDir;
    private final Path configFile;

    public ScheduleStore() {
        this.configDir = resolveConfigDir();
        this.configFile = configDir.resolve("schedule.properties");
    }

    public Path configDir() { return configDir; }
    public Path configFile() { return configFile; }

    public AppSettings load() {
        AppSettings settings = new AppSettings();
        settings.alertsInUaToken = AlertsInUaTokenStore.load();
        if (!Files.exists(configFile)) return settings;

        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            p.load(in);
            settings.scheduleEnabled = Boolean.parseBoolean(p.getProperty("schedule.enabled", "true"));
            settings.volume = clamp(parseInt(p.getProperty("audio.volume"), 85), 0, 100);
            settings.startSound = pathOrNull(p.getProperty("audio.start"));
            settings.endSound = pathOrNull(p.getProperty("audio.end"));
            settings.autoStart = Boolean.parseBoolean(p.getProperty("app.autostart", "false"));
            settings.highPriority = Boolean.parseBoolean(p.getProperty("app.highPriority", "false"));
            settings.minimizeToTray = Boolean.parseBoolean(p.getProperty("app.minimizeToTray", "true"));
            settings.keepAwakeMethod = AppSettings.KeepAwakeMethod.parse(p.getProperty("app.keepAwakeMethod", "NONE"));

            String providerRaw = p.getProperty("alerts.provider", "").trim();
            if (!providerRaw.isBlank()) {
                settings.alertProvider = AppSettings.AlertProvider.parse(providerRaw);
            } else if (Boolean.parseBoolean(p.getProperty("alerts.enabled", "false"))) {
                // Compatibility with 1.3.0 where there was only NEPTUN and a single enabled flag.
                settings.alertProvider = AppSettings.AlertProvider.NEPTUN;
            }
            settings.alertOblast = p.getProperty("alerts.oblast", "");
            settings.alertDistrict = p.getProperty("alerts.district", "");
            settings.alertSound = pathOrNull(p.getProperty("alerts.sound"));
            settings.allClearSound = pathOrNull(p.getProperty("alerts.allClearSound"));

            int lessonCount = parseInt(p.getProperty("lesson.count"), 0);
            for (int i = 0; i < lessonCount; i++) {
                String prefix = "lesson." + i + ".";
                try {
                    String id = p.getProperty(prefix + "id");
                    String name = p.getProperty(prefix + "name", "Урок " + (i + 1));
                    LocalTime start = LocalTime.parse(p.getProperty(prefix + "start", "08:00"), Lesson.TIME_FORMAT);
                    LocalTime end = LocalTime.parse(p.getProperty(prefix + "end", "08:45"), Lesson.TIME_FORMAT);
                    boolean enabled = Boolean.parseBoolean(p.getProperty(prefix + "enabled", "true"));
                    settings.lessons.add(new Lesson(id, name, start, end,
                            Lesson.parseDays(p.getProperty(prefix + "days", "1,2,3,4,5")), enabled));
                } catch (Exception ignored) {
                    // Один пошкоджений рядок не повинен ламати весь розклад.
                }
            }

            int bellCount = parseInt(p.getProperty("bell.count"), 0);
            for (int i = 0; i < bellCount; i++) {
                String prefix = "bell." + i + ".";
                try {
                    String id = p.getProperty(prefix + "id");
                    String name = p.getProperty(prefix + "name", "Дзвінок " + (i + 1));
                    LocalTime time = LocalTime.parse(p.getProperty(prefix + "time", "08:00"), Lesson.TIME_FORMAT);
                    boolean enabled = Boolean.parseBoolean(p.getProperty(prefix + "enabled", "true"));
                    ScheduledBell.AudioSource source = ScheduledBell.AudioSource.parse(p.getProperty(prefix + "audio", "START"));
                    Path custom = pathOrNull(p.getProperty(prefix + "customSound"));
                    settings.bells.add(new ScheduledBell(id, name, time,
                            Lesson.parseDays(p.getProperty(prefix + "days", "1,2,3,4,5")),
                            enabled, source, custom));
                } catch (Exception ignored) {
                    // Пошкоджений окремий дзвінок пропускаємо.
                }
            }
        } catch (IOException ignored) {
        }
        return settings;
    }

    public synchronized void save(AppSettings settings) throws IOException {
        Files.createDirectories(configDir);
        Properties p = new Properties();
        p.setProperty("schedule.enabled", Boolean.toString(settings.scheduleEnabled));
        p.setProperty("audio.volume", Integer.toString(settings.volume));
        p.setProperty("audio.start", settings.startSound == null ? "" : settings.startSound.toString());
        p.setProperty("audio.end", settings.endSound == null ? "" : settings.endSound.toString());
        p.setProperty("app.autostart", Boolean.toString(settings.autoStart));
        p.setProperty("app.highPriority", Boolean.toString(settings.highPriority));
        p.setProperty("app.minimizeToTray", Boolean.toString(settings.minimizeToTray));
        p.setProperty("app.keepAwakeMethod", settings.keepAwakeMethod.name());

        p.setProperty("alerts.enabled", Boolean.toString(settings.alertProvider != AppSettings.AlertProvider.NONE));
        p.setProperty("alerts.provider", settings.alertProvider.name());
        p.setProperty("alerts.oblast", settings.alertOblast == null ? "" : settings.alertOblast);
        p.setProperty("alerts.district", settings.alertDistrict == null ? "" : settings.alertDistrict);
        p.setProperty("alerts.sound", settings.alertSound == null ? "" : settings.alertSound.toString());
        p.setProperty("alerts.allClearSound", settings.allClearSound == null ? "" : settings.allClearSound.toString());
        // Intentionally never persist alerts.in.ua token here.

        p.setProperty("lesson.count", Integer.toString(settings.lessons.size()));
        for (int i = 0; i < settings.lessons.size(); i++) {
            Lesson lesson = settings.lessons.get(i);
            String prefix = "lesson." + i + ".";
            p.setProperty(prefix + "id", lesson.id());
            p.setProperty(prefix + "name", lesson.name());
            p.setProperty(prefix + "start", lesson.startText());
            p.setProperty(prefix + "end", lesson.endText());
            p.setProperty(prefix + "enabled", Boolean.toString(lesson.enabled()));
            p.setProperty(prefix + "days", lesson.daysStorage());
        }

        p.setProperty("bell.count", Integer.toString(settings.bells.size()));
        for (int i = 0; i < settings.bells.size(); i++) {
            ScheduledBell bell = settings.bells.get(i);
            String prefix = "bell." + i + ".";
            p.setProperty(prefix + "id", bell.id());
            p.setProperty(prefix + "name", bell.name());
            p.setProperty(prefix + "time", bell.timeText());
            p.setProperty(prefix + "enabled", Boolean.toString(bell.enabled()));
            p.setProperty(prefix + "days", bell.daysStorage());
            p.setProperty(prefix + "audio", bell.audioSource().name());
            p.setProperty(prefix + "customSound", bell.customSound() == null ? "" : bell.customSound().toString());
        }

        Path temp = configDir.resolve("schedule.properties.tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            p.store(out, "SchoolBell schedule");
        }
        try {
            Files.move(temp, configFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temp, configFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path resolveConfigDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) return Paths.get(appData, "SchoolBell");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) return Paths.get(xdg, "SchoolBell");
        return Paths.get(System.getProperty("user.home"), ".config", "SchoolBell");
    }

    private static Path pathOrNull(String value) {
        return value == null || value.isBlank() ? null : Paths.get(value);
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (Exception e) { return fallback; }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
