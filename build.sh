#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/classes
find src/main/java -name '*.java' > build/sources.txt
javac --release 21 -encoding UTF-8 -d build/classes @build/sources.txt
jar --create --file build/almoxarifado.jar --main-class br.com.almoxarifado.Main -C build/classes .
