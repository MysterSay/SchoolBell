package com.mystersay.schoolbell;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Keeps the alerts.in.ua token out of schedule.properties. */
public final class AlertsInUaTokenStore {
    public static final String ENV_NAME = "SCHOOLBELL_ALERTS_IN_UA_TOKEN";
    public static final String FALLBACK_FILE_NAME = "alerts-in-ua.token";

    public record SaveResult(boolean success, String message, Storage storage) {}
    public enum Storage { WINDOWS_ENVIRONMENT, FALLBACK_FILE, NONE }

    private AlertsInUaTokenStore() {}

    public static String load() {
        String env = clean(System.getenv(ENV_NAME));
        if (!env.isBlank()) return env;

        if (SystemIntegration.isWindows()) {
            String registry = readWindowsUserEnvironment();
            if (!registry.isBlank()) return registry;
        }

        Path fallback = fallbackFile();
        try {
            if (Files.isRegularFile(fallback)) {
                return clean(Files.readString(fallback, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    public static SaveResult save(String token) {
        String value = clean(token);
        if (value.isBlank()) return clear();

        if (SystemIntegration.isWindows()) {
            if (writeWindowsEnvironmentWithSetx(value) || writeWindowsEnvironmentWithRegistry(value)) {
                try { Files.deleteIfExists(fallbackFile()); } catch (IOException ignored) {}
                return new SaveResult(true,
                        "API токен alerts.in.ua збережено у змінній середовища Windows " + ENV_NAME + ".",
                        Storage.WINDOWS_ENVIRONMENT);
            }
        }

        try {
            Path file = fallbackFile();
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, value + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            tryHideFallbackOnWindows(file);
            return new SaveResult(true,
                    "Не вдалося записати змінну середовища. API токен збережено у " + file + ".",
                    Storage.FALLBACK_FILE);
        } catch (IOException ex) {
            return new SaveResult(false, "Не вдалося зберегти API токен: " + readable(ex), Storage.NONE);
        }
    }

    public static SaveResult clear() {
        boolean envCleared = true;
        if (SystemIntegration.isWindows()) {
            try {
                CommandResult result = run(List.of("reg.exe", "delete", "HKCU\\Environment", "/v", ENV_NAME, "/f"));
                String out = result.output.toLowerCase(Locale.ROOT);
                envCleared = result.exitCode == 0 || out.contains("unable to find") || out.contains("не удается найти");
            } catch (Exception ex) {
                envCleared = false;
            }
        }
        boolean fileCleared = true;
        try { Files.deleteIfExists(fallbackFile()); } catch (IOException ex) { fileCleared = false; }
        boolean ok = envCleared && fileCleared;
        return new SaveResult(ok,
                ok ? "API токен alerts.in.ua видалено." : "Не вдалося повністю видалити API токен alerts.in.ua.",
                Storage.NONE);
    }

    public static Path fallbackFile() {
        try {
            String launcher = clean(System.getProperty("schoolbell.launcher", ""));
            if (!launcher.isBlank()) {
                Path exe = Paths.get(launcher).toAbsolutePath().normalize();
                Path parent = exe.getParent();
                if (parent != null) return parent.resolve(FALLBACK_FILE_NAME);
            }

            Path code = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            if (Files.isRegularFile(code)) {
                Path parent = code.getParent();
                if (parent != null) return parent.resolve(FALLBACK_FILE_NAME);
            }
        } catch (URISyntaxException | RuntimeException ignored) {
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize().resolve(FALLBACK_FILE_NAME);
    }

    private static String readWindowsUserEnvironment() {
        try {
            CommandResult result = run(List.of("reg.exe", "query", "HKCU\\Environment", "/v", ENV_NAME));
            if (result.exitCode != 0) return "";
            String[] lines = result.output.split("\\R");
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.toUpperCase(Locale.ROOT).startsWith(ENV_NAME)) continue;
                int regSz = trimmed.toUpperCase(Locale.ROOT).indexOf("REG_SZ");
                if (regSz < 0) regSz = trimmed.toUpperCase(Locale.ROOT).indexOf("REG_EXPAND_SZ");
                if (regSz >= 0) {
                    int valueStart = trimmed.indexOf(' ', regSz);
                    if (valueStart >= 0) return clean(trimmed.substring(valueStart));
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static boolean writeWindowsEnvironmentWithSetx(String token) {
        try {
            CommandResult result = run(List.of("setx.exe", ENV_NAME, token));
            return result.exitCode == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean writeWindowsEnvironmentWithRegistry(String token) {
        try {
            CommandResult result = run(List.of("reg.exe", "add", "HKCU\\Environment", "/v", ENV_NAME,
                    "/t", "REG_SZ", "/d", token, "/f"));
            return result.exitCode == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static void tryHideFallbackOnWindows(Path file) {
        if (!SystemIntegration.isWindows()) return;
        try { run(List.of("attrib.exe", "+h", file.toString())); } catch (Exception ignored) {}
    }

    private static CommandResult run(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        byte[] bytes = process.getInputStream().readAllBytes();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new CommandResult(-1, "timeout");
        }
        return new CommandResult(process.exitValue(), new String(bytes, StandardCharsets.UTF_8));
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String readable(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }
    private record CommandResult(int exitCode, String output) {}
}
