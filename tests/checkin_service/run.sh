#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/checkin-service-tests}"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javac ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
mkdir -p "$TEST_WORK_DIR/classes"
cd "$PROJECT_ROOT"
sources=()
while IFS= read -r source; do sources+=("$source"); done < <(rg --files tests/checkin_service -g '*.java' | LC_ALL=C sort)
javac --release 8 -Xlint:all -Werror -encoding UTF-8 -d "$TEST_WORK_DIR/classes" "${sources[@]}" app/src/main/java/cn/ngapure/app/StartupTaskQueue.java app/src/main/java/cn/ngapure/app/CheckInService.java
java --add-opens java.base/java.util.concurrent=ALL-UNNAMED -cp "$TEST_WORK_DIR/classes" cn.ngapure.app.CheckInOpenQueueTest
