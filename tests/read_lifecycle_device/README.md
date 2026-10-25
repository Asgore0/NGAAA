# Isolated Android read cancellation verification

This helper contains the production views and transport supplied through an exact
production `classes.jar`. It has its own package/UID and no INTERNET permission.
Its URL factory returns only six scripted in-memory HTTPS connections. It never
opens a real socket, reads the client account, sends a private message, changes a
favorite or uploads an avatar.

The 50 checks exercise close/cancellation of community, following and activity
views; socket reads deliberately ignore worker interruption. They must actively
disconnect off the main thread and let their workers exit within two seconds.
A synthetic task verifies that explicit delivery/read-back work can finish after
the community page closes, without making a write request. The real ratings
reader and `ScreenReadTasks` queue must release an obsolete blocked read so the
latest request can run. These are isolated lifecycle tests, not a claim that a
real server mutation has been tested.

Build with JDK 17, Python 3, `zip`, Android SDK platform 35 and build-tools 35.0.0:

```sh
JAVA_HOME=/path/to/jdk17 ANDROID_SDK_ROOT=/path/to/android-sdk \
PRODUCT_JAR=/absolute/path/to/production/classes.jar \
PRODUCT_APK=/absolute/path/to/matching/candidate.apk \
SIGNING_DIR=/absolute/path/to/fixture-signing \
BUILD_DIR=/absolute/path/to/helper-output \
bash tests/read_lifecycle_device/build.sh
```

`PRODUCT_JAR`, `PRODUCT_APK` and `SIGNING_DIR` are mandatory. `BUILD_DIR` defaults
to the project's `.build/read-lifecycle-device`; `BUILD_TOOLS_VERSION` may override
35.0.0. The fixture uses `SIGNING_DIR/ngapure-debug.keystore`, alias
`ngapure-debug`, store/key password `android`. A separate throwaway debug key is
sufficient; no release signing key is required.

Compilation uses Java 8 compatibility and `-Xlint:all -Werror`. The production
jar goes directly into D8; JVM Html stubs, copied production classes in the helper
jar and missing cancellation hooks are rejected. `product-class-binding.json`
records the exact product jar/APK/helper APK hashes and every production class
SHA-256. It also rejects product inputs that changed while the helper compiled.
Generated APKs, classes and binding reports belong in `BUILD_DIR`, never in this
source directory.

Install on a dedicated emulator and run:

```text
am instrument -w -e offlineCommunityCancellation true
  cn.ngapure.readcancelfixturev194/cn.ngapure.readcancelfixturev194.ReadLifecycleProbe
```

The result key is `safe_read_lifecycle_report`. Require `passed=true` and all 50
checks. Collect the report, then uninstall `cn.ngapure.readcancelfixturev194`.
