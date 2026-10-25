#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
BUILD="${TEST_WORK_DIR:-$PROJECT/.build/forum-early-read-tests}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
mkdir -p "$BUILD/classes"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -d "$BUILD/classes" \
 "$PROJECT/app/src/main/java/cn/ngapure/app/ForumReadDelivery.java" \
 "$PROJECT/app/src/main/java/cn/ngapure/app/ScreenReadTasks.java" \
 "$PROJECT/app/src/main/java/cn/ngapure/app/RequestCancellation.java" \
 "$PROJECT/tests/forum_early_read/ForumReadDeliveryTest.java"
"$JDK/bin/java" -cp "$BUILD/classes" cn.ngapure.app.ForumReadDeliveryTest
