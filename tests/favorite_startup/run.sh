#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
BUILD="${TEST_WORK_DIR:-$PROJECT/.build/favorite-startup-tests}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
mkdir -p "$BUILD/tokens" "$BUILD/coordinator"
cd "$PROJECT"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -d "$BUILD/tokens" \
 app/src/main/java/cn/ngapure/app/FavoriteForegroundTokens.java tests/favorite_startup/FavoriteForegroundTokensTest.java
"$JDK/bin/java" -cp "$BUILD/tokens" cn.ngapure.app.FavoriteForegroundTokensTest
sources=()
while IFS= read -r source; do sources+=("$source"); done < <(rg --files tests/favorite_startup/support -g '*.java' | LC_ALL=C sort)
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -d "$BUILD/coordinator" \
 "${sources[@]}" app/src/main/java/cn/ngapure/app/FavoriteForegroundTokens.java \
 app/src/main/java/cn/ngapure/app/StartupTaskQueue.java app/src/main/java/cn/ngapure/app/ForumFavoriteAutoPolicy.java \
 app/src/main/java/cn/ngapure/app/ForumFavoritesCoordinator.java tests/favorite_startup/FavoriteStartupTest.java
"$JDK/bin/java" -cp "$BUILD/coordinator" cn.ngapure.app.FavoriteStartupTest
