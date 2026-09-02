package com.mystersay.schoolbell;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AppSettings {
    public enum AlertProvider {
        NONE,
        NEPTUN,
        ALERTS_IN_UA;

        public static AlertProvider parse(String value) {
            if (value == null || value.isBlank()) return NONE;
            try {
                return AlertProvider.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (Exception ex) {
                return NONE;
            }
        }

        public String displayName() {
            return switch (this) {
                case NEPTUN -> "NEPTUN";
                case ALERTS_IN_UA -> "alerts.in.ua";
                case NONE -> "вимкнено";
            };
        }
    }

    public final List<Lesson> lessons = new ArrayList<>();
    public final List<ScheduledBell> bells = new ArrayList<>();
    public Path startSound;
    public Path endSound;
    public int volume = 85;
    public boolean scheduleEnabled = true;
    public boolean autoStart = false;
    public boolean highPriority = false;
    public boolean minimizeToTray = true;

    public AlertProvider alertProvider = AlertProvider.NONE;
    public String alertOblast = "";
    public String alertDistrict = "";
    public Path alertSound;
    public Path allClearSound;

    /** Secret is loaded from Windows user environment or the fallback token file, never schedule.properties. */
    public String alertsInUaToken = "";
}
