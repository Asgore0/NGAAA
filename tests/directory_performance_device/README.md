# Native directory performance fixture

This helper runs the original production `MainActivity`, `NgaStore`, directory
adapter and Android Keystore in the separate `cn.ngapure.directoryperfprobe` UID.
The complete production JAR and all public APK assets are copied byte exactly.
Production classes are never rewritten. No INTERNET or shared UID permission is
present; an HTTP URL handler rejects attempted network connections as well.

The helper resets only its own fixture preferences, writes a fixed synthetic
login through the real encrypted `NgaStore.setCookie`, and seeds exactly 17
favorites from the public catalog using the real favorites projection. It never
opens the installed `cn.ngapure.app`, accesses its data, clicks a favorite, or
performs a real community write. Login/Cookie/board identifiers are not exported.
The public binding manifest lists immutable class/asset hashes.

## Same test for baseline and candidate

Build this same unchanged source against each immutable pair. For baseline:

```sh
export PRODUCT_JAR=/absolute/path/to/production.jar
export PRODUCT_APK=/absolute/path/to/matching.apk
export PRODUCT_JAR_SHA256="$(shasum -a 256 "$PRODUCT_JAR" | cut -d " " -f 1)"
export PRODUCT_APK_SHA256="$(shasum -a 256 "$PRODUCT_APK" | cut -d " " -f 1)"
export SIGNING_DIR=/absolute/path/outside/repository/signing
export BUILD_DIR=/absolute/path/to/fresh-helper-output
bash scripts/with-env.sh bash tests/directory_performance_device/build.sh
```

Use a fresh `BUILD_DIR` for every build. Change only the immutable product
JAR/APK/expected hashes and output directory for the new candidate. Keep the
probe's source hashes identical. The script verifies both input hashes, refuses
nonempty build directories, compiles with `-Xlint:all -Werror`, embeds its binding
and verifies the helper APK signature and lack of INTERNET.

## Root-operated dedicated emulator

Requires Android API 29+ and a viewport at least 760 physical pixels wide.
The Activity overrides its own density to 160dpi and font scale to 1. It measures
real roots at 320/520/760dp without changing global emulator display settings.
Use the same emulator geometry, idle state and test order for comparisons.
The root agent alone executes these instructions using its verified emulator ID;
building this helper does not access ADB.

```sh
adb -s "$SERIAL" install -r "$BUILD_DIR/directory-performance-probe.apk"
adb -s "$SERIAL" shell am instrument -w -r \
  -e syntheticDirectoryPerformanceOnly true \
  cn.ngapure.directoryperfprobe/cn.ngapure.directoryperfprobe.DirectoryPerformanceProbe
adb -s "$SERIAL" exec-out run-as cn.ngapure.directoryperfprobe \
  cat files/directory-performance-report.json > "$RESULT_DIR/report.json"
adb -s "$SERIAL" uninstall cn.ngapure.directoryperfprobe
```

Preserve full raw stdout and actual helper/binding hashes beside the safe JSON.
The instrumentation also returns `safe_directory_performance_report` in stdout.
Allow a 240-second runner timeout; each eligible pre-draw has a 30-second bound.
Uninstall between baseline/candidate runs to avoid mixing fixture process state.

## Measurements and interpretation

Every case has 2 retained warmups followed by 7 retained measured samples.

* Recycled adapter binding: at each width, invoke the actual directory
  `ListAdapter.getView` for all 17 cards on the main thread. Reuse the same
  detached production row views between samples. Record wall and thread CPU
  nanoseconds around binding only; assertions and JSON are outside the timer.
  This exposes synchronous repeated session/Keystore costs without modifying
  or intercepting the product's cookie/session methods.
* Directory entry: actual `navigateHome(3)` to `navigateHome(1)`, 520dp. Record
  the main-thread entry action and elapsed time to the first eligible pre-draw
  showing all 17 favorites, expected columns and no pending restore.
* Width reflow: repeat 320→520→760dp on the actual directory root. Record the
  action and ready pre-draw latency with the same readiness checks.

Raw samples retain warmups explicitly; summary median/mean/min/max and
nearest-rank p95 exclude warmups. With 7 measured samples p95 equals maximum;
do not imply a large population. `passed` only means the fixture and all fixed
samples completed safely. It contains **no improvement/performance threshold**
and has no `expectFixed` option.

Motion is disabled in helper preferences to isolate native binding/layout.
Glass remains at the real default. Ready pre-draw is a traversal readiness
timestamp, not presentation/GPU completion. CPU time excludes Keystore/Binder
waits; no exact Keystore transaction count is inferred. Emulator scheduling,
JIT, images/cache and other work affect measurements. Compare repeated runs on
the same device and use a separate real-client animation test. This is not an
FPS benchmark, full cold-start benchmark, or physical fold test.

The distributed Java and manifest are byte-identical to the measured work fixture.
Only the build script project-root lookup and this README build invocation are
adjusted for the source archive directory layout. No measurement logic differs.
