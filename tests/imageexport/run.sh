#!/usr/bin/env bash
set -euo pipefail
TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$TEST_DIR/../.." && pwd)"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
BUILD="${TEST_WORK_DIR:-$PROJECT/.build/imageexport}"
mkdir -p "$BUILD"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -d "$BUILD" "$PROJECT/app/src/main/java/cn/ngapure/app/NativeImageExport.java" "$TEST_DIR/ImageExportTest.java"
"$JDK/bin/java" -cp "$BUILD" cn.ngapure.app.ImageExportTest
