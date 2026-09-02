package com.mystersay.schoolbell;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class SchedulerService implements AutoCloseable {
    public enum EventType { START, END, BELL }

    public record BellEvent(Lesson lesson, ScheduledBell bell, EventType type, LocalDateTime scheduledAt) {
        public String name() { return bell != null ? bell.name() : lesson.name(); }
    }

    public record NextBell(Lesson lesson, ScheduledBell bell, EventType type, LocalDateTime at) {
        public String name() { return bell != null ? bell.name() : lesson.name(); }
    }

    private final Supplier<List<Lesson>> lessonsSupplier;
    private final Supplier<List<ScheduledBell>> bellsSupplier;
    private final Supplier<Boolean> enabledSupplier;
    private final Supplier<Boolean> highPrioritySupplier;
    private final Consumer<BellEvent> bellConsumer;
    private final Consumer<NextBell> nextConsumer;
    private final Consumer<BellEvent> skippedConsumer;
    private final ScheduledExecutorService executor;
    private final Set<String> fired = new HashSet<>();
    private LocalDate lastCleanupDate;
    private long lastNextUpdateSecond = -1;
    private volatile String skippedKey;
    private volatile NextBell currentNext;

    public SchedulerService(Supplier<List<Lesson>> lessonsSupplier,
                            Supplier<List<ScheduledBell>> bellsSupplier,
                            Supplier<Boolean> enabledSupplier,
                            Supplier<Boolean> highPrioritySupplier,
                            Consumer<BellEvent> bellConsumer,
                            Consumer<NextBell> nextConsumer,
                            Consumer<BellEvent> skippedConsumer) {
        this.lessonsSupplier = lessonsSupplier;
        this.bellsSupplier = bellsSupplier;
        this.enabledSupplier = enabledSupplier;
        this.highPrioritySupplier = highPrioritySupplier;
        this.bellConsumer = bellConsumer;
        this.nextConsumer = nextConsumer;
        this.skippedConsumer = skippedConsumer;
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "schoolbell-scheduler");
            t.setDaemon(true);
            t.setPriority(highPrioritySupplier.get() ? Thread.MAX_PRIORITY : Thread.NORM_PRIORITY);
            return t;
        };
        this.executor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    public void start() {
        executor.scheduleAtFixedRate(this::tickSafe, 0, 250, TimeUnit.MILLISECONDS);
    }

    public NextBell currentNext() { return currentNext; }

    public synchronized NextBell skipNext() {
        NextBell next = currentNext;
        if (next == null) return null;
        skippedKey = eventKey(next.at().toLocalDate(), next.lesson(), next.bell(), next.type());
        currentNext = findNext(new ArrayList<>(lessonsSupplier.get()), new ArrayList<>(bellsSupplier.get()), LocalDateTime.now());
        nextConsumer.accept(currentNext);
        return next;
    }

    private void tickSafe() {
        try { tick(); } catch (Throwable ignored) {}
    }

    private void tick() {
        Thread.currentThread().setPriority(highPrioritySupplier.get() ? Thread.MAX_PRIORITY : Thread.NORM_PRIORITY);
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        if (!today.equals(lastCleanupDate)) {
            fired.removeIf(key -> !key.startsWith(today + "|"));
            lastCleanupDate = today;
        }

        List<Lesson> lessons = new ArrayList<>(lessonsSupplier.get());
        List<ScheduledBell> bells = new ArrayList<>(bellsSupplier.get());
        if (enabledSupplier.get()) {
            LocalTime minute = now.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
            for (Lesson lesson : lessons) {
                if (!lesson.enabled() || !lesson.days().contains(today.getDayOfWeek())) continue;
                if (lesson.start().equals(minute)) fireLessonOnce(today, lesson, EventType.START);
                if (lesson.end().equals(minute)) fireLessonOnce(today, lesson, EventType.END);
            }
            for (ScheduledBell bell : bells) {
                if (!bell.enabled() || !bell.days().contains(today.getDayOfWeek())) continue;
                if (bell.time().equals(minute)) fireBellOnce(today, bell);
            }
        }

        long currentSecond = System.currentTimeMillis() / 1000L;
        if (currentSecond != lastNextUpdateSecond) {
            lastNextUpdateSecond = currentSecond;
            currentNext = findNext(lessons, bells, now);
            nextConsumer.accept(currentNext);
        }
    }

    private void fireLessonOnce(LocalDate date, Lesson lesson, EventType type) {
        String key = eventKey(date, lesson, null, type);
        if (!fired.add(key)) return;
        LocalTime time = type == EventType.START ? lesson.start() : lesson.end();
        BellEvent event = new BellEvent(lesson, null, type, LocalDateTime.of(date, time));
        dispatchOrSkip(key, event);
    }

    private void fireBellOnce(LocalDate date, ScheduledBell bell) {
        String key = eventKey(date, null, bell, EventType.BELL);
        if (!fired.add(key)) return;
        BellEvent event = new BellEvent(null, bell, EventType.BELL, LocalDateTime.of(date, bell.time()));
        dispatchOrSkip(key, event);
    }

    private void dispatchOrSkip(String key, BellEvent event) {
        if (key.equals(skippedKey)) {
            skippedKey = null;
            skippedConsumer.accept(event);
            return;
        }
        bellConsumer.accept(event);
    }

    private NextBell findNext(List<Lesson> lessons, List<ScheduledBell> bells, LocalDateTime now) {
        if (!enabledSupplier.get()) return null;
        NextBell best = null;
        for (int offset = 0; offset <= 7; offset++) {
            LocalDate date = now.toLocalDate().plusDays(offset);
            for (Lesson lesson : lessons) {
                if (!lesson.enabled() || !lesson.days().contains(date.getDayOfWeek())) continue;
                best = earlier(best, candidateLesson(lesson, EventType.START, date, lesson.start(), now));
                best = earlier(best, candidateLesson(lesson, EventType.END, date, lesson.end(), now));
            }
            for (ScheduledBell bell : bells) {
                if (!bell.enabled() || !bell.days().contains(date.getDayOfWeek())) continue;
                best = earlier(best, candidateBell(bell, date, now));
            }
            if (best != null) break;
        }
        return best;
    }

    private NextBell candidateLesson(Lesson lesson, EventType type, LocalDate date, LocalTime time, LocalDateTime now) {
        LocalDateTime at = LocalDateTime.of(date, time);
        if (!at.isAfter(now)) return null;
        if (eventKey(date, lesson, null, type).equals(skippedKey)) return null;
        return new NextBell(lesson, null, type, at);
    }

    private NextBell candidateBell(ScheduledBell bell, LocalDate date, LocalDateTime now) {
        LocalDateTime at = LocalDateTime.of(date, bell.time());
        if (!at.isAfter(now)) return null;
        if (eventKey(date, null, bell, EventType.BELL).equals(skippedKey)) return null;
        return new NextBell(null, bell, EventType.BELL, at);
    }

    private static NextBell earlier(NextBell a, NextBell b) {
        if (b == null) return a;
        if (a == null || b.at().isBefore(a.at())) return b;
        return a;
    }

    private static String eventKey(LocalDate date, Lesson lesson, ScheduledBell bell, EventType type) {
        String id = bell != null ? bell.id() : lesson.id();
        return date + "|" + id + "|" + type;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
