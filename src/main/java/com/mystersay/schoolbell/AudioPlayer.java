package com.mystersay.schoolbell;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Відтворення дзвінка.
 *
 * Windows: основний backend — нативний WinMM/MCI через winmm.dll у окремому
 * PowerShell-процесі. Це навмисно НЕ Java Sound: такий шлях не залежить від
 * DirectAudioDevice/Clip/SourceDataLine і не успадковує mute для java.exe у
 * Windows Volume Mixer. Якщо MCI не зміг відкрити конкретний файл, пробуємо
 * Windows Media Player COM (MP3/FLAC/M4A/WMA та інші системні кодеки), потім
 * System.Media.SoundPlayer для WAV, а вже після цього — Java Sound.
 *
 * Linux: зовнішній ffplay/mpv/GStreamer/VLC з Java Sound як резервом.
 * macOS: Java Sound.
 */
public final class AudioPlayer implements AutoCloseable {
    private static final int BUFFER_SIZE = 32 * 1024;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "schoolbell-audio");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });

    private final Object stateLock = new Object();
    private final AtomicLong generation = new AtomicLong();

    private volatile SourceDataLine currentLine;
    private volatile Process currentNativeProcess;
    private volatile boolean closed;

    public void play(Path file, int volume, Consumer<String> errorHandler) {
        if (file == null) {
            errorHandler.accept("Аудіофайл не вибрано.");
            return;
        }
        if (!Files.isRegularFile(file)) {
            errorHandler.accept("Аудіофайл не знайдено: " + file);
            return;
        }
        if (closed) {
            errorHandler.accept("Аудіомодуль вже зупинено.");
            return;
        }

        final Path absolute = file.toAbsolutePath().normalize();
        final int safeVolume = Math.max(0, Math.min(100, volume));
        final long token = generation.incrementAndGet();

        stopCurrentOutput();

        executor.submit(() -> {
            Throwable mediaFoundationError = null;
            Throwable nativeMciError = null;
            Throwable mediaPlayerError = null;
            Throwable nativeSoundPlayerError = null;
            Throwable linuxNativeError = null;
            Throwable javaSoundError = null;

            // На Windows не можна вважати успішний код MCI доказом, що MP3/M4A
            // реально почали грати. Для стиснених форматів спочатку використовуємо
            // WPF MediaPlayer (Media Foundation) і чекаємо MediaOpened/MediaFailed.
            if (isWindows() && token == generation.get() && !closed) {
                String extension = fileExtension(absolute);
                boolean wavLike = extension.equals("wav") || extension.equals("wave");
                boolean midiLike = extension.equals("mid") || extension.equals("midi");

                if (!wavLike && !midiLike) {
                    try {
                        playWithWindowsMediaFoundation(absolute, safeVolume, token);
                        return;
                    } catch (Throwable ex) {
                        mediaFoundationError = ex;
                        stopCurrentOutput();
                    }

                    if (token == generation.get() && !closed) {
                        try {
                            playWithWindowsMediaPlayer(absolute, safeVolume, token);
                            return;
                        } catch (Throwable ex) {
                            mediaPlayerError = ex;
                            stopCurrentOutput();
                        }
                    }

                    // MCI лишається лише третім резервом для стиснених форматів:
                    // деякі драйвери повертають success, але реально не дають звук.
                    if (token == generation.get() && !closed) {
                        try {
                            playWithWindowsMci(absolute, safeVolume, token);
                            return;
                        } catch (Throwable ex) {
                            nativeMciError = ex;
                            stopCurrentOutput();
                        }
                    }
                } else if (wavLike) {
                    try {
                        playWithWindowsMci(absolute, safeVolume, token);
                        return;
                    } catch (Throwable ex) {
                        nativeMciError = ex;
                        stopCurrentOutput();
                    }

                    if (token == generation.get() && !closed) {
                        try {
                            playWithWindowsSoundPlayer(absolute, token);
                            return;
                        } catch (Throwable ex) {
                            nativeSoundPlayerError = ex;
                            stopCurrentOutput();
                        }
                    }

                    if (token == generation.get() && !closed) {
                        try {
                            playWithWindowsMediaFoundation(absolute, safeVolume, token);
                            return;
                        } catch (Throwable ex) {
                            mediaFoundationError = ex;
                            stopCurrentOutput();
                        }
                    }
                } else {
                    // MIDI найстабільніше відтворюється через Windows MCI.
                    try {
                        playWithWindowsMci(absolute, safeVolume, token);
                        return;
                    } catch (Throwable ex) {
                        nativeMciError = ex;
                        stopCurrentOutput();
                    }
                }
            }

            // На Linux стандартний Java Sound зазвичай не має декодерів для
            // MP3/M4A/FLAC/OGG/OPUS. Тому спочатку використовуємо системний
            // медіаплеєр. ffplay є першим пріоритетом, далі mpv, GStreamer/VLC.
            if (isLinux() && token == generation.get() && !closed) {
                try {
                    playWithLinuxNativePlayer(absolute, safeVolume, token);
                    return;
                } catch (Throwable ex) {
                    linuxNativeError = ex;
                    stopCurrentOutput();
                }
            }

            if (token == generation.get() && !closed) {
                try {
                    playWithJavaSound(absolute, safeVolume, token);
                    return;
                } catch (Throwable ex) {
                    javaSoundError = ex;
                    stopCurrentOutput();
                }
            }

            if (token == generation.get() && !closed) {
                if (isWindows()) {
                    errorHandler.accept(
                            "Не вдалося відтворити звук жодним способом. "
                                    + "Windows Media Foundation: " + usefulMessage(mediaFoundationError)
                                    + "; Windows MCI: " + usefulMessage(nativeMciError)
                                    + "; Windows Media Player: " + usefulMessage(mediaPlayerError)
                                    + "; Windows SoundPlayer: " + usefulMessage(nativeSoundPlayerError)
                                    + "; Java Sound: " + usefulMessage(javaSoundError)
                                    + ". Файл: " + absolute
                    );
                } else if (isLinux()) {
                    errorHandler.accept(
                            "Не вдалося відтворити звук у Linux. "
                                    + "Системний плеєр: " + usefulMessage(linuxNativeError)
                                    + "; Java Sound: " + usefulMessage(javaSoundError)
                                    + ". Встановіть ffmpeg (ffplay) або mpv, якщо їх немає. Файл: " + absolute
                    );
                } else if (javaSoundError instanceof UnsupportedAudioFileException) {
                    errorHandler.accept("Формат аудіо не підтримується локальним аудіодрайвером: "
                            + absolute.getFileName());
                } else {
                    errorHandler.accept("Не вдалося відтворити звук: " + usefulMessage(javaSoundError));
                }
            }
        });
    }

    public void stop() {
        generation.incrementAndGet();
        stopCurrentOutput();
    }

    /**
     * Основний Windows backend для MP3/M4A/AAC/FLAC/WMA та інших форматів,
     * які декодує Windows Media Foundation. WPF MediaPlayer повідомляє MediaOpened
     * тільки після реального відкриття медіа, тому тут немає старої ситуації,
     * коли MCI повернув код 0, а звук фактично не стартував.
     */
    private void playWithWindowsMediaFoundation(Path file, int volume, long token) throws Exception {
        String path = psSingleQuoted(file.toAbsolutePath().toString());
        double normalizedVolume = Math.max(0.0, Math.min(1.0, volume / 100.0));

        String script = """
                $ErrorActionPreference='Stop'
                Add-Type -AssemblyName PresentationCore
                Add-Type -AssemblyName WindowsBase
                $file = '%s'
                $script:sbOpened = $false
                $script:sbDone = $false
                $script:sbError = $null
                $dispatcher = [System.Windows.Threading.Dispatcher]::CurrentDispatcher
                $player = New-Object System.Windows.Media.MediaPlayer
                $player.Volume = %s

                $player.add_MediaOpened({
                    try {
                        $script:sbOpened = $true
                        $player.Play()
                    } catch {
                        $script:sbError = $_.Exception.Message
                        $script:sbDone = $true
                        [void]$dispatcher.BeginInvokeShutdown([System.Windows.Threading.DispatcherPriority]::Normal)
                    }
                })
                $player.add_MediaEnded({
                    $script:sbDone = $true
                    [void]$dispatcher.BeginInvokeShutdown([System.Windows.Threading.DispatcherPriority]::Normal)
                })
                $player.add_MediaFailed({
                    param($sender, $args)
                    if ($args.ErrorException) {
                        $script:sbError = $args.ErrorException.Message
                    } else {
                        $script:sbError = 'Windows Media Foundation не зміг декодувати файл.'
                    }
                    $script:sbDone = $true
                    [void]$dispatcher.BeginInvokeShutdown([System.Windows.Threading.DispatcherPriority]::Normal)
                })

                $openTimeout = New-Object System.Windows.Threading.DispatcherTimer
                $openTimeout.Interval = [TimeSpan]::FromSeconds(12)
                $openTimeout.add_Tick({
                    if (-not $script:sbOpened -and -not $script:sbDone) {
                        $script:sbError = 'Windows Media Foundation не відкрив аудіофайл за 12 секунд.'
                        $script:sbDone = $true
                        $openTimeout.Stop()
                        [void]$dispatcher.BeginInvokeShutdown([System.Windows.Threading.DispatcherPriority]::Normal)
                    }
                })

                $hardTimeout = New-Object System.Windows.Threading.DispatcherTimer
                $hardTimeout.Interval = [TimeSpan]::FromHours(4)
                $hardTimeout.add_Tick({
                    $script:sbError = 'Перевищено максимальний час відтворення (4 години).'
                    $script:sbDone = $true
                    $hardTimeout.Stop()
                    [void]$dispatcher.BeginInvokeShutdown([System.Windows.Threading.DispatcherPriority]::Normal)
                })

                try {
                    $uri = New-Object System.Uri($file, [System.UriKind]::Absolute)
                    $player.Open($uri)
                    $openTimeout.Start()
                    $hardTimeout.Start()
                    [System.Windows.Threading.Dispatcher]::Run()
                } finally {
                    try { $openTimeout.Stop() } catch {}
                    try { $hardTimeout.Stop() } catch {}
                    try { $player.Stop() } catch {}
                    try { $player.Close() } catch {}
                }

                if ($script:sbError) { throw $script:sbError }
                if (-not $script:sbOpened) { throw 'MediaOpened не спрацював.' }
                """.formatted(path, Double.toString(normalizedVolume));

        runHiddenPowerShell(script, token);
    }

    /**
     * Нативне Windows MCI. Працює через winmm.dll та не використовує Java audio mixer.
     * Гучність MCI: 0..1000.
     */
    private void playWithWindowsMci(Path file, int volume, long token) throws Exception {
        String path = psSingleQuoted(file.toAbsolutePath().toString());
        int mciVolume = Math.max(0, Math.min(1000, volume * 10));

        String script = """
                $ErrorActionPreference='Stop'
                Add-Type -TypeDefinition @'
                using System;
                using System.Text;
                using System.Runtime.InteropServices;
                public static class SchoolBellMci {
                    [DllImport("winmm.dll", CharSet = CharSet.Unicode, SetLastError = true)]
                    public static extern int mciSendString(string command, StringBuilder buffer, int bufferSize, IntPtr hwndCallback);
                    [DllImport("winmm.dll", CharSet = CharSet.Unicode)]
                    [return: MarshalAs(UnmanagedType.Bool)]
                    public static extern bool mciGetErrorString(int errorCode, StringBuilder errorText, int errorTextSize);
                }
                '@
                function Invoke-Mci([string]$cmd) {
                    $buf = New-Object System.Text.StringBuilder 1024
                    $rc = [SchoolBellMci]::mciSendString($cmd, $buf, $buf.Capacity, [IntPtr]::Zero)
                    if ($rc -ne 0) {
                        $err = New-Object System.Text.StringBuilder 1024
                        [void][SchoolBellMci]::mciGetErrorString($rc, $err, $err.Capacity)
                        throw "MCI $rc: $($err.ToString()) | $cmd"
                    }
                    return $buf.ToString()
                }
                $file = '%s'
                try {
                    Invoke-Mci 'close schoolbell' | Out-Null
                } catch {}
                try {
                    Invoke-Mci ('open "' + $file.Replace('"','""') + '" alias schoolbell') | Out-Null
                    Invoke-Mci 'set schoolbell time format milliseconds' | Out-Null
                    try { Invoke-Mci 'setaudio schoolbell on' | Out-Null } catch {}
                    try { Invoke-Mci 'setaudio schoolbell volume to %d' | Out-Null } catch {}
                    Invoke-Mci 'play schoolbell wait' | Out-Null
                } finally {
                    try { Invoke-Mci 'close schoolbell' | Out-Null } catch {}
                }
                """.formatted(path, mciVolume);

        runHiddenPowerShell(script, token);
    }


    /**
     * Windows Media Player COM fallback. Використовує кодеки Windows і тому
     * підтримує значно більше форматів, ніж javax.sound.sampled: MP3, WMA,
     * M4A/AAC, FLAC та інші формати, для яких у системі є декодер.
     */
    private void playWithWindowsMediaPlayer(Path file, int volume, long token) throws Exception {
        String path = psSingleQuoted(file.toAbsolutePath().toString());
        int safeVolume = Math.max(0, Math.min(100, volume));
        String script = """
                $ErrorActionPreference='Stop'
                $file = '%s'
                $wmp = New-Object -ComObject WMPlayer.OCX
                try {
                    $wmp.settings.volume = %d
                    $wmp.settings.autoStart = $false
                    $wmp.URL = $file
                    $wmp.controls.play()
                    $deadline = (Get-Date).AddHours(4)
                    $started = $false
                    while ((Get-Date) -lt $deadline) {
                        $state = [int]$wmp.playState
                        if ($state -eq 3) { $started = $true }
                        if ($started -and ($state -eq 1 -or $state -eq 8 -or $state -eq 10)) { break }
                        if (-not $started -and ($state -eq 1 -or $state -eq 8) -and (Get-Date) -gt $deadline.AddHours(-4).AddSeconds(8)) {
                            throw 'Windows Media Player не почав відтворення файлу.'
                        }
                        Start-Sleep -Milliseconds 100
                    }
                    if (-not $started) { throw 'Windows Media Player не зміг відкрити аудіофайл.' }
                } finally {
                    try { $wmp.controls.stop() } catch {}
                    try { [System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($wmp) | Out-Null } catch {}
                }
                """.formatted(path, safeVolume);
        runHiddenPowerShell(script, token);
    }

    /**
     * Другий нативний Windows backend. SoundPlayer особливо надійний для PCM WAV.
     * Гучність тут контролює системний мікшер; цей backend використовується лише
     * якщо MCI не зміг відкрити файл.
     */
    private void playWithWindowsSoundPlayer(Path file, long token) throws Exception {
        String path = psSingleQuoted(file.toAbsolutePath().toString());
        String script = """
                $ErrorActionPreference='Stop'
                Add-Type -AssemblyName System
                $p = New-Object System.Media.SoundPlayer('%s')
                $p.Load()
                $p.PlaySync()
                """.formatted(path);
        runHiddenPowerShell(script, token);
    }

    private void runHiddenPowerShell(String script, long token) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Sta",
                "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        synchronized (stateLock) {
            if (token != generation.get() || closed) {
                process.destroyForcibly();
                return;
            }
            currentNativeProcess = process;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try {
                process.getInputStream().transferTo(output);
            } catch (IOException ignored) {
            }
        }, "schoolbell-audio-native-log");
        reader.setDaemon(true);
        reader.start();

        int code;
        try {
            code = process.waitFor();
            reader.join(1000);
        } finally {
            synchronized (stateLock) {
                if (currentNativeProcess == process) currentNativeProcess = null;
            }
        }

        if (token != generation.get() || closed) return;
        if (code != 0) {
            String text = decodePowerShellOutput(output.toByteArray());
            throw new IOException(text.isBlank() ? "PowerShell завершився з кодом " + code : text);
        }
    }

    /**
     * Linux backend для стиснених форматів. Використовує вже встановлений у
     * системі медіаплеєр, тому підтримка кодеків не залежить від Java Sound.
     */
    private void playWithLinuxNativePlayer(Path file, int volume, long token) throws Exception {
        String executable;
        ProcessBuilder pb;

        if ((executable = findExecutable("ffplay")) != null) {
            pb = new ProcessBuilder(
                    executable,
                    "-nodisp", "-autoexit", "-loglevel", "error",
                    "-volume", Integer.toString(volume),
                    file.toAbsolutePath().toString()
            );
        } else if ((executable = findExecutable("mpv")) != null) {
            pb = new ProcessBuilder(
                    executable,
                    "--no-video", "--really-quiet", "--no-terminal",
                    "--volume=" + volume,
                    file.toAbsolutePath().toString()
            );
        } else if ((executable = findExecutable("gst-play-1.0")) != null) {
            // gst-play-1.0 не має стабільного CLI-параметра гучності у всіх
            // версіях. Сам файл все одно відтворюється через системні кодеки.
            pb = new ProcessBuilder(
                    executable,
                    "--quiet",
                    file.toAbsolutePath().toString()
            );
        } else if ((executable = findExecutable("cvlc")) != null) {
            pb = new ProcessBuilder(
                    executable,
                    "--intf", "dummy", "--play-and-exit", "--no-video",
                    "--gain", Double.toString(Math.max(0.0, volume / 100.0)),
                    file.toAbsolutePath().toString()
            );
        } else {
            throw new IOException("Не знайдено ffplay, mpv, gst-play-1.0 або cvlc у PATH");
        }

        runNativeProcess(pb, token);
    }

    private void runNativeProcess(ProcessBuilder pb, long token) throws Exception {
        pb.redirectErrorStream(true);
        Process process = pb.start();

        synchronized (stateLock) {
            if (token != generation.get() || closed) {
                process.destroyForcibly();
                return;
            }
            currentNativeProcess = process;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try {
                process.getInputStream().transferTo(output);
            } catch (IOException ignored) {
            }
        }, "schoolbell-audio-linux-log");
        reader.setDaemon(true);
        reader.start();

        int code;
        try {
            code = process.waitFor();
            reader.join(1000);
        } finally {
            synchronized (stateLock) {
                if (currentNativeProcess == process) currentNativeProcess = null;
            }
        }

        if (token != generation.get() || closed) return;
        if (code != 0) {
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8)
                    .replace('\n', ' ').replace('\r', ' ').trim();
            throw new IOException(text.isBlank()
                    ? "Системний аудіоплеєр завершився з кодом " + code
                    : text);
        }
    }

    private static String findExecutable(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir == null || dir.isBlank()) continue;
            try {
                Path candidate = Path.of(dir, name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void playWithJavaSound(Path file, int volume, long token) throws Exception {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(file.toFile());
             AudioInputStream pcm = toPlayablePcm(source)) {

            AudioFormat format = pcm.getFormat();
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);

            synchronized (stateLock) {
                if (token != generation.get() || closed) {
                    line.close();
                    return;
                }
                currentLine = line;
            }

            try {
                int preferredBuffer = Math.max(BUFFER_SIZE,
                        Math.max(format.getFrameSize(), 1) * (int) Math.max(format.getSampleRate() / 4f, 4096f));
                line.open(format, preferredBuffer);
                line.start();

                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while (token == generation.get() && !closed && (read = pcm.read(buffer, 0, buffer.length)) != -1) {
                    if (read <= 0) continue;
                    if (volume < 100) scalePcm16LittleEndian(buffer, read, volume / 100.0);

                    int written = 0;
                    while (written < read && token == generation.get() && !closed) {
                        int n = line.write(buffer, written, read - written);
                        if (n <= 0) break;
                        written += n;
                    }
                }

                if (token == generation.get() && !closed) line.drain();
            } finally {
                try { line.stop(); } catch (Exception ignored) {}
                try { line.flush(); } catch (Exception ignored) {}
                try { line.close(); } catch (Exception ignored) {}
                synchronized (stateLock) {
                    if (currentLine == line) currentLine = null;
                }
            }
        }
    }

    private static AudioInputStream toPlayablePcm(AudioInputStream source) throws Exception {
        AudioFormat base = source.getFormat();
        float sampleRate = base.getSampleRate();
        if (sampleRate <= 0 || Float.isNaN(sampleRate)) sampleRate = 44_100f;
        int channels = base.getChannels();
        if (channels <= 0) channels = 2;

        AudioFormat target = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false
        );

        if (formatsEquivalent(base, target)) return source;
        if (!AudioSystem.isConversionSupported(target, base)) {
            throw new UnsupportedAudioFileException("Немає PCM-конвертера для " + base);
        }
        return AudioSystem.getAudioInputStream(target, source);
    }

    private static boolean formatsEquivalent(AudioFormat a, AudioFormat b) {
        return a.getEncoding().equals(b.getEncoding())
                && Math.abs(a.getSampleRate() - b.getSampleRate()) < 0.01f
                && a.getSampleSizeInBits() == b.getSampleSizeInBits()
                && a.getChannels() == b.getChannels()
                && a.getFrameSize() == b.getFrameSize()
                && !a.isBigEndian();
    }

    private static void scalePcm16LittleEndian(byte[] data, int length, double factor) {
        if (factor >= 0.9999) return;
        if (factor <= 0.0) {
            for (int i = 0; i < length; i++) data[i] = 0;
            return;
        }
        int evenLength = length - (length & 1);
        for (int i = 0; i < evenLength; i += 2) {
            int lo = data[i] & 0xFF;
            int hi = data[i + 1];
            short sample = (short) ((hi << 8) | lo);
            int scaled = (int) Math.round(sample * factor);
            if (scaled > Short.MAX_VALUE) scaled = Short.MAX_VALUE;
            if (scaled < Short.MIN_VALUE) scaled = Short.MIN_VALUE;
            data[i] = (byte) (scaled & 0xFF);
            data[i + 1] = (byte) ((scaled >>> 8) & 0xFF);
        }
    }

    private void stopCurrentOutput() {
        SourceDataLine line;
        Process process;
        synchronized (stateLock) {
            line = currentLine;
            currentLine = null;
            process = currentNativeProcess;
            currentNativeProcess = null;
        }

        if (line != null) {
            try { line.stop(); } catch (Exception ignored) {}
            try { line.flush(); } catch (Exception ignored) {}
            try { line.close(); } catch (Exception ignored) {}
        }
        if (process != null && process.isAlive()) {
            try { process.destroy(); } catch (Exception ignored) {}
            try {
                if (!process.waitFor(350, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (Exception ignored) {
                try { process.destroyForcibly(); } catch (Exception ignored2) {}
            }
        }
    }

    private static String fileExtension(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String psSingleQuoted(String value) {
        return value.replace("'", "''");
    }

    private static String decodePowerShellOutput(byte[] bytes) {
        if (bytes.length == 0) return "";
        // Windows PowerShell with redirected streams is usually OEM/UTF-8 for normal
        // text. Try UTF-8 first; replacement chars are still more useful than silence.
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        return text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private static String usefulMessage(Throwable error) {
        if (error == null) return "не запускався";
        String msg = error.getMessage();
        if (msg == null || msg.isBlank()) return error.getClass().getSimpleName();
        return msg.replace('\n', ' ').replace('\r', ' ').trim();
    }

    @Override
    public void close() {
        closed = true;
        generation.incrementAndGet();
        stopCurrentOutput();
        executor.shutdownNow();
    }
}
