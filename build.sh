#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build dist
mkdir -p build/classes dist
find src/main/java -name '*.java' -print > build/sources.txt
javac --release 17 -encoding UTF-8 -d build/classes @build/sources.txt
jar --create --file dist/SchoolBell.jar --main-class com.mystersay.schoolbell.Main -C build/classes .
echo "Готово: dist/SchoolBell.jar"
