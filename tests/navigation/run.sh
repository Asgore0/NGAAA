#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/navigation-tests}"
mkdir -p "$TEST_WORK_DIR/classes"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javac ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
cd "$PROJECT_ROOT"
javac --release 8 -Xlint:all -Werror -encoding UTF-8 -d "$TEST_WORK_DIR/classes" \
  app/src/main/java/cn/ngapure/app/NavigationState.java app/src/main/java/cn/ngapure/app/HomeScreenState.java app/src/main/java/cn/ngapure/app/AvatarPickerState.java app/src/main/java/cn/ngapure/app/PromptDraftState.java app/src/main/java/cn/ngapure/app/ForumRoute.java app/src/main/java/cn/ngapure/app/ReaderAnchor.java \
  tests/navigation/NavigationStateTest.java tests/navigation/ReaderAnchorTest.java tests/navigation/HomeScreenStateTest.java tests/navigation/AvatarPickerStateTest.java tests/navigation/PromptDraftStateTest.java
java -cp "$TEST_WORK_DIR/classes" cn.ngapure.app.NavigationStateTest
java -cp "$TEST_WORK_DIR/classes" cn.ngapure.app.ReaderAnchorTest
java -cp "$TEST_WORK_DIR/classes" cn.ngapure.app.HomeScreenStateTest
java -cp "$TEST_WORK_DIR/classes" cn.ngapure.app.AvatarPickerStateTest
java -cp "$TEST_WORK_DIR/classes" cn.ngapure.app.PromptDraftStateTest
