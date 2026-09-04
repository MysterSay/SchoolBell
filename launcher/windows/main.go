package main

import (
	"crypto/sha256"
	_ "embed"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

//go:embed SchoolBell.jar
var schoolBellJar []byte

const appVersion = "1.5.1"
const createNoWindow = 0x08000000
const jnaVersion = "5.19.1"
const jnaURL = "https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.19.1/jna-5.19.1.jar"
const jnaSHA256 = "4fb141dd8ef6b0585ffceea4bc49602fbc6312fa977e2c488794ea3e6aafecae"

func main() {
	exePath, err := os.Executable()
	if err != nil {
		messageBox("SchoolBell", "Не вдалося визначити шлях до SchoolBell.exe.")
		return
	}
	exePath, _ = filepath.Abs(exePath)

	jarPath, err := ensureEmbeddedJar()
	if err != nil {
		messageBox("SchoolBell", "Не вдалося підготувати файли програми:\n\n"+err.Error())
		return
	}

	javaExe := findJava17Plus()
	if javaExe == "" {
		messageBox("SchoolBell", "Для запуску SchoolBell потрібна Java 17 або новіша.\n\nНа цьому комп'ютері не знайдено сумісну Java. Встанови Java 17/21/24 і запусти SchoolBell.exe ще раз.")
		return
	}

	jnaPath := ensureJNA(filepath.Dir(jarPath))
	args := []string{
		"-Dfile.encoding=UTF-8",
		"-Dschoolbell.launcher=" + exePath,
	}
	if jnaPath != "" {
		args = append(args, "-cp", jarPath+";"+jnaPath, "com.mystersay.schoolbell.Main")
	} else {
		// SchoolBell itself can still run without JNA. Only the Windows keep-awake
		// method will be unavailable until JNA can be cached successfully.
		args = append(args, "-jar", jarPath)
	}
	cmd := exec.Command(javaExe, args...)
	cmd.Dir = filepath.Dir(exePath)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}

	if err := cmd.Start(); err != nil {
		messageBox("SchoolBell", "Не вдалося запустити програму:\n\n"+err.Error())
		return
	}
}

func ensureEmbeddedJar() (string, error) {
	base := os.Getenv("LOCALAPPDATA")
	if strings.TrimSpace(base) == "" {
		var err error
		base, err = os.UserConfigDir()
		if err != nil {
			return "", err
		}
	}

	appDir := filepath.Join(base, "SchoolBell", "app")
	if err := os.MkdirAll(appDir, 0755); err != nil {
		return "", err
	}

	jarPath := filepath.Join(appDir, "SchoolBell-"+appVersion+".jar")
	wanted := sha256.Sum256(schoolBellJar)

	if data, err := os.ReadFile(jarPath); err == nil {
		have := sha256.Sum256(data)
		if have == wanted {
			return jarPath, nil
		}
	}

	tmp := jarPath + ".tmp"
	if err := os.WriteFile(tmp, schoolBellJar, 0644); err != nil {
		return "", err
	}
	if err := os.Rename(tmp, jarPath); err != nil {
		_ = os.Remove(tmp)
		return "", err
	}

	// Невеликий маркер версії/контролю цілісності для діагностики.
	_ = os.WriteFile(filepath.Join(appDir, "version.txt"), []byte(appVersion+"\nsha256="+hex.EncodeToString(wanted[:])+"\n"), 0644)
	return jarPath, nil
}

func ensureJNA(appDir string) string {
	jarPath := filepath.Join(appDir, "jna-"+jnaVersion+".jar")
	if fileSHA256(jarPath) == jnaSHA256 {
		return jarPath
	}

	client := &http.Client{Timeout: 18 * time.Second}
	resp, err := client.Get(jnaURL)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return ""
	}

	tmp := jarPath + ".tmp"
	out, err := os.Create(tmp)
	if err != nil {
		return ""
	}
	_, copyErr := io.Copy(out, resp.Body)
	closeErr := out.Close()
	if copyErr != nil || closeErr != nil {
		_ = os.Remove(tmp)
		return ""
	}
	if fileSHA256(tmp) != jnaSHA256 {
		_ = os.Remove(tmp)
		return ""
	}
	if err := os.Rename(tmp, jarPath); err != nil {
		_ = os.Remove(tmp)
		return ""
	}
	return jarPath
}

func fileSHA256(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

func findJava17Plus() string {
	candidates := javaCandidates()
	seen := map[string]bool{}
	for _, c := range candidates {
		if c == "" {
			continue
		}
		abs, err := filepath.Abs(c)
		if err == nil {
			c = abs
		}
		key := strings.ToLower(c)
		if seen[key] {
			continue
		}
		seen[key] = true
		if st, err := os.Stat(c); err != nil || st.IsDir() {
			continue
		}
		if javaMajorVersion(c) >= 17 {
			return c
		}
	}
	return ""
}

func javaCandidates() []string {
	out := []string{}

	if custom := strings.TrimSpace(os.Getenv("SCHOOLBELL_JAVA")); custom != "" {
		out = append(out, custom)
	}

	if home := strings.TrimSpace(os.Getenv("JAVA_HOME")); home != "" {
		out = append(out,
			filepath.Join(home, "bin", "javaw.exe"),
			filepath.Join(home, "bin", "java.exe"),
		)
	}

	if p, err := exec.LookPath("javaw.exe"); err == nil {
		out = append(out, p)
	}
	if p, err := exec.LookPath("java.exe"); err == nil {
		out = append(out, p)
	}

	// Типові каталоги JDK/JRE. Скануємо лише один рівень, щоб запуск був швидким.
	roots := []string{
		os.Getenv("ProgramFiles"),
		os.Getenv("ProgramW6432"),
		os.Getenv("LOCALAPPDATA"),
		filepath.Join(os.Getenv("USERPROFILE"), ".jdks"),
	}
	subdirs := []string{
		"Eclipse Adoptium", "Java", "Microsoft", "BellSoft", "Zulu", "Amazon Corretto", "Programs",
	}

	for _, root := range roots {
		if strings.TrimSpace(root) == "" {
			continue
		}
		// Сам root може вже бути .jdks.
		out = append(out, scanJavaHomes(root)...)
		for _, sub := range subdirs {
			out = append(out, scanJavaHomes(filepath.Join(root, sub))...)
		}
	}

	// Якщо JAR раніше відкривався подвійним кліком, часто javaw прописаний у реєстрі.
	out = append(out, javaFromRegistry()...)
	return out
}

func scanJavaHomes(root string) []string {
	entries, err := os.ReadDir(root)
	if err != nil {
		return nil
	}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		if e.IsDir() {
			names = append(names, e.Name())
		}
	}
	sort.Sort(sort.Reverse(sort.StringSlice(names)))

	out := []string{}
	for _, name := range names {
		dir := filepath.Join(root, name)
		out = append(out,
			filepath.Join(dir, "bin", "javaw.exe"),
			filepath.Join(dir, "bin", "java.exe"),
		)
		// Деякі менеджери кладуть JDK ще на рівень глибше.
		nested, err := os.ReadDir(dir)
		if err == nil {
			for _, n := range nested {
				if n.IsDir() {
					out = append(out,
						filepath.Join(dir, n.Name(), "bin", "javaw.exe"),
						filepath.Join(dir, n.Name(), "bin", "java.exe"),
					)
				}
			}
		}
	}
	return out
}

func javaFromRegistry() []string {
	keys := [][]string{
		{"query", `HKCR\\jarfile\\shell\\open\\command`, "/ve"},
		{"query", `HKCR\\Applications\\javaw.exe\\shell\\open\\command`, "/ve"},
		{"query", `HKLM\\SOFTWARE\\JavaSoft\\JDK`, "/s"},
		{"query", `HKLM\\SOFTWARE\\Eclipse Adoptium`, "/s"},
	}
	reQuotedExe := regexp.MustCompile(`(?i)"([^"]*javaw?\.exe)"`)
	reBareExe := regexp.MustCompile(`(?i)([A-Z]:\\[^\r\n]*?javaw?\.exe)`)
	out := []string{}

	for _, args := range keys {
		cmd := exec.Command("reg.exe", args...)
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
		data, err := cmd.CombinedOutput()
		if err != nil && len(data) == 0 {
			continue
		}
		text := string(data)
		for _, m := range reQuotedExe.FindAllStringSubmatch(text, -1) {
			if len(m) > 1 {
				out = append(out, m[1])
			}
		}
		for _, m := range reBareExe.FindAllStringSubmatch(text, -1) {
			if len(m) > 1 {
				out = append(out, strings.TrimSpace(m[1]))
			}
		}
	}
	return out
}

func javaMajorVersion(javaPath string) int {
	probe := javaPath
	lower := strings.ToLower(filepath.Base(probe))
	if lower == "javaw.exe" {
		sibling := filepath.Join(filepath.Dir(probe), "java.exe")
		if st, err := os.Stat(sibling); err == nil && !st.IsDir() {
			probe = sibling
		}
	}

	cmd := exec.Command(probe, "-version")
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: createNoWindow}
	data, err := cmd.CombinedOutput()
	if err != nil && len(data) == 0 {
		return 0
	}
	text := string(data)
	re := regexp.MustCompile(`(?i)version\s+"([0-9]+)(?:\.([0-9]+))?`)
	m := re.FindStringSubmatch(text)
	if len(m) < 2 {
		// OpenJDK 9+ також часто виводить: openjdk version "21..."
		re2 := regexp.MustCompile(`"([0-9]+)(?:\.([0-9]+))?`)
		m = re2.FindStringSubmatch(text)
	}
	if len(m) < 2 {
		return 0
	}
	first, _ := strconv.Atoi(m[1])
	if first == 1 && len(m) > 2 {
		second, _ := strconv.Atoi(m[2])
		return second
	}
	return first
}

func messageBox(title, text string) {
	user32 := syscall.NewLazyDLL("user32.dll")
	proc := user32.NewProc("MessageBoxW")
	t, _ := syscall.UTF16PtrFromString(text)
	c, _ := syscall.UTF16PtrFromString(title)
	_, _, _ = proc.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), 0x10)
}

// keep fmt referenced in case future diagnostic build enables formatted logging
var _ = fmt.Sprintf
