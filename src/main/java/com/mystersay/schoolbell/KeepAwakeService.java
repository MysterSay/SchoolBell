package com.mystersay.schoolbell;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps both the operating system and the display awake while SchoolBell is running.
 *
 * Windows: JNA -> kernel32!SetThreadExecutionState with SYSTEM + DISPLAY required.
 * Linux:   ProcessBuilder -> desktop/session inhibitor. On KDE, kde-inhibit is
 *          preferred because it suppresses PowerDevil display power management.
 */
public final class KeepAwakeService implements AutoCloseable {
    private static final int ES_SYSTEM_REQUIRED = 0x00000001;
    private static final int ES_DISPLAY_REQUIRED = 0x00000002;
    private static final int ES_CONTINUOUS = 0x80000000;

    public record Result(boolean success, String message) {}

    private Thread windowsThread;
    private CountDownLatch windowsStop;
    private Process linuxProcess;
    private String linuxBackend = "";
    private AppSettings.KeepAwakeMethod activeMethod = AppSettings.KeepAwakeMethod.NONE;

    public synchronized Result apply(AppSettings.KeepAwakeMethod method) {
        if (method == null) method = AppSettings.KeepAwakeMethod.NONE;
        if (method == activeMethod && isStillActive()) {
            return new Result(true, statusMessage());
        }

        stopInternal();
        return switch (method) {
            case NONE -> new Result(true, "Емітацію активності вимкнено.");
            case WINDOWS_JNA -> startWindowsJna();
            case LINUX_PROCESSBUILDER -> startLinuxInhibitor();
        };
    }

    public synchronized AppSettings.KeepAwakeMethod activeMethod() {
        return activeMethod;
    }

    public synchronized String statusMessage() {
        return switch (activeMethod) {
            case WINDOWS_JNA -> "Активно: Windows JNA — сон і вимкнення дисплея заблоковано.";
            case LINUX_PROCESSBUILDER -> "Активно: Linux " + (linuxBackend.isBlank() ? "inhibitor" : linuxBackend)
                    + " — сон і автоматичне вимкнення дисплея заблоковано.";
            case NONE -> "Емітацію активності вимкнено.";
        };
    }

    private Result startWindowsJna() {
        if (!isWindows()) {
            return new Result(false, "Метод JNA / SetThreadExecutionState доступний тільки у Windows.");
        }

        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch stopLatch = new CountDownLatch(1);
        AtomicReference<Result> startResult = new AtomicReference<>();

        Thread thread = new Thread(() -> {
            Object function = null;
            Method invokeInt = null;
            try {
                Class<?> nativeLibraryClass = Class.forName("com.sun.jna.NativeLibrary");
                Class<?> functionClass = Class.forName("com.sun.jna.Function");
                Method getInstance = nativeLibraryClass.getMethod("getInstance", String.class);
                Method getFunction = nativeLibraryClass.getMethod("getFunction", String.class);
                invokeInt = functionClass.getMethod("invokeInt", Object[].class);

                Object kernel32 = getInstance.invoke(null, "kernel32");
                function = getFunction.invoke(kernel32, "SetThreadExecutionState");
                int flags = ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED;
                int result = (Integer) invokeInt.invoke(function, (Object) new Object[]{flags});
                if (result == 0) {
                    startResult.set(new Result(false, "Windows SetThreadExecutionState повернув 0."));
                    ready.countDown();
                    return;
                }

                startResult.set(new Result(true,
                        "Емітацію активності увімкнено через JNA / SetThreadExecutionState. "
                                + "Windows не повинна переходити в сон або автоматично вимикати дисплей."));
                ready.countDown();
                stopLatch.await();
            } catch (ClassNotFoundException ex) {
                startResult.set(new Result(false,
                        "JNA не знайдено. Для EXE бібліотека завантажується лаунчером автоматично; "
                                + "для JAR запускай run.bat або додай jna.jar у classpath."));
                ready.countDown();
            } catch (Throwable ex) {
                startResult.set(new Result(false, "Не вдалося активувати JNA: " + rootMessage(ex)));
                ready.countDown();
            } finally {
                // Reset only the execution requirements that were set by this thread.
                if (function != null && invokeInt != null) {
                    try {
                        invokeInt.invoke(function, (Object) new Object[]{ES_CONTINUOUS});
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "SchoolBell-KeepAwake-Windows");
        thread.setDaemon(true);
        thread.start();

        try {
            if (!ready.await(4, TimeUnit.SECONDS)) {
                stopLatch.countDown();
                return new Result(false, "JNA не відповів протягом 4 секунд.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            stopLatch.countDown();
            return new Result(false, "Очікування JNA перервано.");
        }

        Result result = startResult.get();
        if (result != null && result.success()) {
            windowsThread = thread;
            windowsStop = stopLatch;
            activeMethod = AppSettings.KeepAwakeMethod.WINDOWS_JNA;
            return result;
        }
        stopLatch.countDown();
        return result == null ? new Result(false, "Невідома помилка JNA.") : result;
    }

    private Result startLinuxInhibitor() {
        if (!isLinux()) {
            return new Result(false, "Метод ProcessBuilder / системні утиліти доступний тільки у Linux.");
        }

        List<LinuxCandidate> candidates = new ArrayList<>();
        String kdeInhibit = findOnPath("kde-inhibit");
        String systemdInhibit = findOnPath("systemd-inhibit");
        String gnomeInhibit = findOnPath("gnome-session-inhibit");
        String sleep = findOnPath("sleep");
        if (sleep == null) sleep = "sleep";

        // KDE/Plasma first. Unlike a plain logind inhibitor, kde-inhibit talks to
        // PowerDevil and also suppresses automatic display power management/DPMS.
        if (kdeInhibit != null) {
            if (systemdInhibit != null) {
                candidates.add(new LinuxCandidate("KDE/PowerDevil + systemd-inhibit", List.of(
                        kdeInhibit,
                        "--power",
                        "--screenSaver",
                        systemdInhibit,
                        "--what=sleep:idle",
                        "--who=SchoolBell",
                        "--why=SchoolBell має виконувати заплановані дзвінки",
                        "--mode=block",
                        sleep, "infinity")));
            } else {
                candidates.add(new LinuxCandidate("KDE/PowerDevil", List.of(
                        kdeInhibit,
                        "--power",
                        "--screenSaver",
                        sleep, "infinity")));
            }
        }

        // GNOME/session-aware fallback. This blocks both idle and suspend while
        // the child command remains alive.
        if (gnomeInhibit != null) {
            candidates.add(new LinuxCandidate("GNOME session inhibitor", List.of(
                    gnomeInhibit,
                    "--inhibit", "idle:suspend",
                    "--reason", "SchoolBell має виконувати заплановані дзвінки",
                    sleep, "infinity")));
        }

        // Generic fallback. On modern Plasma this is also exposed to PowerDevil,
        // but KDE's own inhibitor above is preferred because it reliably covers
        // display power management as well.
        if (systemdInhibit != null) {
            candidates.add(new LinuxCandidate("systemd-inhibit", List.of(
                    systemdInhibit,
                    "--what=sleep:idle",
                    "--who=SchoolBell",
                    "--why=SchoolBell має виконувати заплановані дзвінки",
                    "--mode=block",
                    sleep, "infinity")));
        }

        if (candidates.isEmpty()) {
            return new Result(false,
                    "Не знайдено kde-inhibit, gnome-session-inhibit або systemd-inhibit. "
                            + "Для KDE/Plasma встанови пакет kde-cli-tools.");
        }

        StringBuilder failures = new StringBuilder();
        for (LinuxCandidate candidate : candidates) {
            List<String> command = candidate.command();
            try {
                Process process = new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                Thread.sleep(500);
                if (process.isAlive()) {
                    linuxProcess = process;
                    linuxBackend = candidate.name();
                    activeMethod = AppSettings.KeepAwakeMethod.LINUX_PROCESSBUILDER;
                    return new Result(true,
                            "Емітацію активності увімкнено через " + candidate.name()
                                    + ". Сон і автоматичне вимкнення дисплея мають бути заблоковані, "
                                    + "поки працює SchoolBell.");
                }
                int code = process.exitValue();
                failures.append(candidate.name()).append(" завершився з кодом ").append(code).append("; ");
            } catch (Exception ex) {
                failures.append(candidate.name()).append(": ").append(ex.getMessage()).append("; ");
            }
        }

        return new Result(false, "Не вдалося запустити системний inhibitor. " + failures);
    }

    private boolean isStillActive() {
        return switch (activeMethod) {
            case WINDOWS_JNA -> windowsThread != null && windowsThread.isAlive();
            case LINUX_PROCESSBUILDER -> linuxProcess != null && linuxProcess.isAlive();
            case NONE -> true;
        };
    }

    private void stopInternal() {
        if (windowsStop != null) windowsStop.countDown();
        if (windowsThread != null) {
            try { windowsThread.join(1200); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
        windowsStop = null;
        windowsThread = null;

        if (linuxProcess != null) {
            linuxProcess.destroy();
            try {
                if (!linuxProcess.waitFor(1, TimeUnit.SECONDS)) linuxProcess.destroyForcibly();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                linuxProcess.destroyForcibly();
            }
        }
        linuxProcess = null;
        linuxBackend = "";
        activeMethod = AppSettings.KeepAwakeMethod.NONE;
    }

    @Override
    public synchronized void close() {
        stopInternal();
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static String findOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return null;
        for (String dir : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (dir == null || dir.isBlank()) continue;
            Path candidate = Path.of(dir, executable);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        String message = cur.getMessage();
        return message == null || message.isBlank() ? cur.getClass().getSimpleName() : message;
    }

    private record LinuxCandidate(String name, List<String> command) {}
}
