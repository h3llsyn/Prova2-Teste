#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
bash build.sh
java -jar build/almoxarifado.jar
