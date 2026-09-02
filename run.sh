#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -f dist/SchoolBell.jar ]; then
  ./build.sh
fi
java -jar dist/SchoolBell.jar
