#!/usr/bin/env bash
# Sourced by public entry points. No downloads, compilation or signing.
NGAAA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ngaaa_die() { printf '%s\n' "$*" >&2; return 1; }
ngaaa_env() {
  command -v python3 >/dev/null 2>&1 || { ngaaa_die 'Python 3 is required.'; return 1; }
  if [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ "$(uname -s)" == Darwin && -x /usr/libexec/java_home ]]; then
      JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    fi
    if [[ -z "${JAVA_HOME:-}" ]] && command -v javac >/dev/null 2>&1; then
      JAVA_HOME="$(python3 - "$(command -v javac)" <<'PY'
import pathlib, sys
print(pathlib.Path(sys.argv[1]).resolve().parent.parent)
PY
)"
    fi
  fi
  [[ -x "${JAVA_HOME:-}/bin/javac" && -x "${JAVA_HOME:-}/bin/java" ]] || {
    ngaaa_die 'Set JAVA_HOME to an installed JDK 17 directory.'; return 1;
  }
  local version
  version="$("$JAVA_HOME/bin/javac" -version 2>&1)"
  [[ "$version" == 'javac 17.'* || "$version" == 'javac 17' ]] || {
    ngaaa_die "JDK 17 is required; found: $version"; return 1;
  }
  export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
  if [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
    ANDROID_SDK_ROOT="${ANDROID_HOME:-}"
    if [[ -z "$ANDROID_SDK_ROOT" ]]; then
      local candidate
      for candidate in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" /opt/homebrew/share/android-commandlinetools; do
        if [[ -f "$candidate/platforms/android-35/android.jar" ]]; then
          ANDROID_SDK_ROOT="$candidate"; break
        fi
      done
    fi
  fi
  [[ -f "${ANDROID_SDK_ROOT:-}/platforms/android-35/android.jar" ]] || {
    ngaaa_die 'Set ANDROID_SDK_ROOT (or ANDROID_HOME) to an SDK with platforms;android-35.'; return 1;
  }
  export ANDROID_SDK_ROOT ANDROID_HOME="$ANDROID_SDK_ROOT"
  export BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-35.0.0}"
}
