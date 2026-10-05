#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
bash build.sh
mkdir -p build/test-classes
find src/test/java -name '*.java' > build/test-sources.txt
javac --release 21 -encoding UTF-8 -cp build/classes -d build/test-classes @build/test-sources.txt
java -cp build/classes:build/test-classes br.com.almoxarifado.SystemTest
