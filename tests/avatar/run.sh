#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/avatar-metadata-tests}"
mkdir -p "$TEST_WORK_DIR/classes"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javac ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
JSON_JAR="${AVATAR_JSON_JAR:-$PROJECT_ROOT/../../work/research/org-json-test-only.jar}"
[[ -f "$JSON_JAR" ]] || { echo 'Verified local test-only org.json is required' >&2; exit 1; }
[[ "$(shasum -a 256 "$JSON_JAR" | awk '{print $1}')" == 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed ]] || { echo 'Test JSON checksum mismatch' >&2; exit 1; }
cd "$PROJECT_ROOT"
javac --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$JSON_JAR" -d "$TEST_WORK_DIR/classes" \
  app/src/main/java/cn/ngapure/app/AvatarSources.java app/src/main/java/cn/ngapure/app/VoteMetrics.java \
  app/src/main/java/cn/ngapure/app/RequestCancellation.java app/src/main/java/cn/ngapure/app/NativeAvatarResolver.java \
  tests/avatar/AvatarMetadataTest.java tests/avatar/NativeAvatarResolverTest.java
java -cp "$TEST_WORK_DIR/classes:$JSON_JAR" cn.ngapure.app.AvatarMetadataTest

java -cp "$TEST_WORK_DIR/classes:$JSON_JAR" cn.ngapure.app.NativeAvatarResolverTest
