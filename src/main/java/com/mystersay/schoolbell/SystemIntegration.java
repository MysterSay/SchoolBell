package com.mystersay.schoolbell;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class SystemIntegration {
    public record Result(boolean success, String message) {}

    private SystemIntegration() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    public static Result setAutoStart(boolean enabled) {
        try {
            if (isWindows()) return setWindowsAutoStart(enabled);
            if (isLinux()) return setLinuxAutoStart(enabled);
            return new Result(false, "Автозапуск для цієї операційної системи поки не підтримується.");
        } catch (Exception ex) {
            return new Result(false, "Не вдалося змінити автозапуск: " + readable(ex));
        }
    }

    public static Result applyHighPriority(boolean enabled) {
        try {
            long pid = ProcessHandle.current().pid();
            Thread.currentThread().setPriority(enabled ? Thread.MAX_PRIORITY : Thread.NORM_PRIORITY);

            if (!isWindows()) {
                return new Result(true, enabled
                        ? "Підвищено пріоритет Java-потоку планувальника. Системний пріоритет процесу змінюється лише у Windows."
                        : "Пріоритет Java-потоку повернено до нормального.");
            }

            String priority = enabled ? "High" : "Normal";
            String script = "$ErrorActionPreference='Stop'; "
                    + "$p=Get-Process -Id " + pid + "; "
                    + "$p.PriorityClass='" + priority + "'";
            CommandResult result = runCommand(List.of(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script));
            if (result.exitCode == 0) {
                return new Result(true, enabled
                        ? "Для SchoolBell встановлено високий пріоритет процесу Windows."
                        : "Пріоритет процесу Windows повернено до нормального.");
            }

            // Запасний варіант для систем, де PowerShell обмежений політикою.
            String value = enabled ? "128" : "32";
            CommandResult wmic = runCommand(List.of(
                    "wmic", "process", "where", "processid=" + pid, "call", "setpriority", value));
            if (wmic.exitCode == 0) {
                return new Result(true, enabled
                        ? "Для SchoolBell встановлено високий пріоритет процесу Windows."
                        : "Пріоритет процесу Windows повернено до нормального.");
            }
            return new Result(false, "Windows не дозволила змінити пріоритет процесу.");
        } catch (Exception ex) {
            return new Result(false, "Не вдалося змінити пріоритет: " + readable(ex));
        }
    }

    public static Result openFolder(Path path) {
        try {
            Files.createDirectories(path);
            if (DesktopBridge.open(path)) return new Result(true, "Відкрито папку налаштувань.");
            return new Result(false, "Не вдалося відкрити папку налаштувань.");
        } catch (Exception ex) {
            return new Result(false, "Не вдалося відкрити папку: " + readable(ex));
        }
    }

    private static Result setWindowsAutoStart(boolean enabled) throws IOException, InterruptedException {
        String key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
        if (!enabled) {
            CommandResult result = runCommand(List.of("reg", "delete", key, "/v", "SchoolBell", "/f"));
            if (result.exitCode == 0 || result.output.toLowerCase(Locale.ROOT).contains("unable to find")) {
                return new Result(true, "Автозапуск SchoolBell вимкнено.");
            }
            return new Result(false, "Не вдалося прибрати SchoolBell з автозапуску Windows.");
        }

        String launch = launchCommandForCurrentApp(true);
        if (launch == null) {
            return new Result(false, "Автозапуск можна ввімкнути після запуску з готового SchoolBell.jar.");
        }
        CommandResult result = runCommand(List.of("reg", "add", key, "/v", "SchoolBell", "/t", "REG_SZ", "/d", launch, "/f"));
        if (result.exitCode == 0) return new Result(true, "SchoolBell додано до автозапуску Windows.");
        return new Result(false, "Windows не дозволила додати SchoolBell до автозапуску.");
    }

    private static Result setLinuxAutoStart(boolean enabled) throws IOException, URISyntaxException {
        Path autostartDir = Paths.get(System.getProperty("user.home"), ".config", "autostart");
        Path desktop = autostartDir.resolve("schoolbell.desktop");
        if (!enabled) {
            Files.deleteIfExists(desktop);
            return new Result(true, "Автозапуск SchoolBell вимкнено.");
        }

        String launch = launchCommandForCurrentApp(false);
        if (launch == null) {
            return new Result(false, "Автозапуск можна ввімкнути після запуску з готового SchoolBell.jar.");
        }
        Files.createDirectories(autostartDir);
        String content = "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=SchoolBell\n"
                + "Comment=SchoolBell school schedule\n"
                + "Exec=" + launch + "\n"
                + "Terminal=false\n"
                + "X-GNOME-Autostart-enabled=true\n";
        Files.writeString(desktop, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return new Result(true, "SchoolBell додано до автозапуску Linux.");
    }

    private static String launchCommandForCurrentApp(boolean preferJavaw) {
        try {
            // Якщо SchoolBell запущено через Windows EXE-лаунчер, автозапуск
            // повинен посилатися саме на EXE, а не на внутрішній розпакований JAR.
            String launcher = System.getProperty("schoolbell.launcher", "").trim();
            if (isWindows() && !launcher.isBlank()) {
                Path launcherPath = Paths.get(launcher).toAbsolutePath().normalize();
                if (Files.isRegularFile(launcherPath)
                        && launcherPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")) {
                    return quote(launcherPath.toString());
                }
            }

            Path code = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
            if (!Files.isRegularFile(code) || !code.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return null;
            }
            Path javaBin = Paths.get(System.getProperty("java.home"), "bin",
                    preferJavaw && isWindows() ? "javaw.exe" : (isWindows() ? "java.exe" : "java"));
            if (!Files.isRegularFile(javaBin)) {
                javaBin = Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
            }
            return quote(javaBin.toString()) + " -jar " + quote(code.toString());
        } catch (Exception ex) {
            return null;
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static CommandResult runCommand(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        byte[] data = process.getInputStream().readAllBytes();
        boolean finished = process.waitFor(8, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new CommandResult(-1, "timeout");
        }
        return new CommandResult(process.exitValue(), new String(data, StandardCharsets.UTF_8));
    }

    private record CommandResult(int exitCode, String output) {}

    private static String readable(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private static final class DesktopBridge {
        static boolean open(Path path) {
            try {
                if (!java.awt.Desktop.isDesktopSupported()) return false;
                java.awt.Desktop.getDesktop().open(path.toFile());
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }
}
