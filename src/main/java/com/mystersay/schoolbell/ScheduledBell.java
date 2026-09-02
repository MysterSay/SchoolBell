package com.mystersay.schoolbell;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ScheduledBell {
    public enum AudioSource {
        START("Початок"),
        END("Кінець"),
        CUSTOM("Власне");

        private final String display;
        AudioSource(String display) { this.display = display; }
        public String display() { return display; }

        public static AudioSource parse(String value) {
            if (value == null) return START;
            try { return AudioSource.valueOf(value.trim().toUpperCase()); }
            catch (Exception ignored) { return START; }
        }
    }

    private final String id;
    private String name;
    private LocalTime time;
    private EnumSet<DayOfWeek> days;
    private boolean enabled;
    private AudioSource audioSource;
    private Path customSound;

    public ScheduledBell(String name, LocalTime time, Set<DayOfWeek> days, boolean enabled,
                         AudioSource audioSource, Path customSound) {
        this(UUID.randomUUID().toString(), name, time, days, enabled, audioSource, customSound);
    }

    public ScheduledBell(String id, String name, LocalTime time, Set<DayOfWeek> days, boolean enabled,
                         AudioSource audioSource, Path customSound) {
        this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        this.name = name == null || name.isBlank() ? "Дзвінок" : name;
        this.time = time;
        this.days = days == null || days.isEmpty()
                ? EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
                : EnumSet.copyOf(days);
        this.enabled = enabled;
        this.audioSource = audioSource == null ? AudioSource.START : audioSource;
        this.customSound = customSound;
    }

    public String id() { return id; }
    public String name() { return name; }
    public LocalTime time() { return time; }
    public EnumSet<DayOfWeek> days() { return EnumSet.copyOf(days); }
    public boolean enabled() { return enabled; }
    public AudioSource audioSource() { return audioSource; }
    public Path customSound() { return customSound; }

    public void setName(String name) { this.name = name; }
    public void setTime(LocalTime time) { this.time = time; }
    public void setDays(Set<DayOfWeek> days) { this.days = EnumSet.copyOf(days); }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setAudioSource(AudioSource audioSource) { this.audioSource = audioSource; }
    public void setCustomSound(Path customSound) { this.customSound = customSound; }

    public String timeText() { return time.format(Lesson.TIME_FORMAT); }

    public String daysStorage() {
        return days.stream().map(d -> Integer.toString(d.getValue())).collect(Collectors.joining(","));
    }

    public String daysDisplay() {
        String[] shortNames = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд"};
        if (days.equals(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))) return "Пн–Пт";
        if (days.equals(EnumSet.allOf(DayOfWeek.class))) return "Щодня";
        return days.stream().map(d -> shortNames[d.getValue() - 1]).collect(Collectors.joining(", "));
    }
}
