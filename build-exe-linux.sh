#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

./build.sh
command -v go >/dev/null 2>&1 || { echo "Помилка: потрібен Go 1.22+ для створення Windows EXE."; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cp launcher/windows/main.go "$TMP/main.go"
cp dist/SchoolBell.jar "$TMP/SchoolBell.jar"
(
  cd "$TMP"
  GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags='-H windowsgui -s -w' -o "$OLDPWD/dist/SchoolBell.exe" main.go
)
echo "Готово: dist/SchoolBell.exe"
