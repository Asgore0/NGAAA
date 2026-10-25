#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
BUILD="${TEST_WORK_DIR:-$PROJECT/.build/topic-previews-tests}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
JSON="${TEST_JSON_JAR:-$BUILD/json-20240303.jar}"
mkdir -p "$BUILD/classes"
if [[ ! -f "$JSON" ]]; then
  CACHED="$PROJECT/../../work/research/org-json-test-only.jar"
  if [[ -f "$CACHED" ]]; then cp "$CACHED" "$JSON";
  else curl -fL --max-time 60 https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$JSON"; fi
fi
[[ "$(shasum -a 256 "$JSON" | awk '{print $1}')" == 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed ]]

"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$JSON" -d "$BUILD/classes" \
 "$PROJECT/app/src/main/java/cn/ngapure/app/RequestCancellation.java" \
 "$PROJECT/app/src/main/java/cn/ngapure/app/PostRenderer.java" \
 "$PROJECT/app/src/main/java/cn/ngapure/app/TopicPreviewImages.java" \
 "$PROJECT/app/src/main/java/cn/ngapure/app/VoteMetrics.java" \
 "$PROJECT/tests/topic_previews/TopicPreviewImagesTest.java"
"$JDK/bin/java" -cp "$BUILD/classes:$JSON" cn.ngapure.app.TopicPreviewImagesTest
