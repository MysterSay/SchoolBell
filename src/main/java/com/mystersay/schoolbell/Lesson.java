package com.mystersay.schoolbell;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class Lesson {
    public static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final String id;
    private String name;
    private LocalTime start;
    private LocalTime end;
    private EnumSet<DayOfWeek> days;
    private boolean enabled;

    public Lesson(String name, LocalTime start, LocalTime end, Set<DayOfWeek> days, boolean enabled) {
        this(UUID.randomUUID().toString(), name, start, end, days, enabled);
    }

    public Lesson(String id, String name, LocalTime start, LocalTime end, Set<DayOfWeek> days, boolean enabled) {
        this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        this.name = name;
        this.start = start;
        this.end = end;
        this.days = days == null || days.isEmpty() ? EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY) : EnumSet.copyOf(days);
        this.enabled = enabled;
    }

    public String id() { return id; }
    public String name() { return name; }
    public LocalTime start() { return start; }
    public LocalTime end() { return end; }
    public EnumSet<DayOfWeek> days() { return EnumSet.copyOf(days); }
    public boolean enabled() { return enabled; }

    public void setName(String name) { this.name = name; }
    public void setStart(LocalTime start) { this.start = start; }
    public void setEnd(LocalTime end) { this.end = end; }
    public void setDays(Set<DayOfWeek> days) { this.days = EnumSet.copyOf(days); }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String startText() { return start.format(TIME_FORMAT); }
    public String endText() { return end.format(TIME_FORMAT); }

    public String daysStorage() {
        return days.stream().map(d -> Integer.toString(d.getValue())).collect(Collectors.joining(","));
    }

    public String daysDisplay() {
        String[] shortNames = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд"};
        if (days.equals(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))) return "Пн–Пт";
        if (days.equals(EnumSet.allOf(DayOfWeek.class))) return "Щодня";
        return days.stream().map(d -> shortNames[d.getValue() - 1]).collect(Collectors.joining(", "));
    }

    public static EnumSet<DayOfWeek> parseDays(String text) {
        EnumSet<DayOfWeek> result = EnumSet.noneOf(DayOfWeek.class);
        if (text != null && !text.isBlank()) {
            for (String part : text.split(",")) {
                try {
                    int value = Integer.parseInt(part.trim());
                    result.add(DayOfWeek.of(value));
                } catch (Exception ignored) {
                }
            }
        }
        if (result.isEmpty()) result.addAll(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
        return result;
    }
}
