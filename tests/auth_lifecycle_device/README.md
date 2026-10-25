# Isolated native login cancellation verification

This Android helper exercises the actual NativeCredentialLoginView (SMS login form), NativeLoginView (QR generation) and both production HTTP transports. It has its own application UID, no INTERNET permission, and only synthetic preferences/keystore data. It cannot read the installed client's account. Every HTTPS connection is an in-memory scripted object, and every unscripted endpoint is rejected before a socket can open. It sends no real SMS, submits no real credentials and never displays or requests a real authorization QR code.

The tests close each actual native view while a synthetic response is pending and socket disconnect is deliberately slow. Main-thread responsiveness, synchronous owner revocation, secret clearing, rejection of late grants, preservation of the helper's previous synthetic account and final worker/resource cleanup are checked. Both view types are also tested with the shared disconnect pool saturated at three workers plus twelve queued connections, proving that cancellation cannot fall back to socket I/O on the main thread or grow an unbounded queue.

Build inputs and signing location must be explicit. No dependency is downloaded, no emulator is started, and the build script does not access ADB or implicitly select the mutable project build jar.

```sh
PRODUCT_JAR=/absolute/pinned/classes.jar \
PRODUCT_JAR_SHA256=<reviewed-jar-sha256> \
PRODUCT_APK=/absolute/candidate.apk \
PRODUCT_APK_SHA256=<reviewed-apk-sha256> \
SIGNING_DIR=/absolute/external-signing-directory \
AUTH_LIFECYCLE_BUILD_DIR=/absolute/isolated-helper-output \
bash tests/auth_lifecycle_device/build.sh
```

API 35, build-tools 35.0.0 and JDK 17 are required. `product-class-binding.json` records every production class hash plus the pinned product jar, candidate APK and helper APK hashes; copied production classes in the helper-only jar and JVM Html stub bytecode are rejected.

Run only on the root task's designated emulator. Instrumentation component: `cn.ngapure.authfixturev195/cn.ngapure.authfixturev195.AuthLifecycleProbe`; explicit argument: `offlineAuthLifecycle=true`; result key: `safe_auth_lifecycle_report`. Uninstall the helper afterward. None of its test classes, assets, synthetic account data or signing key belong in the product APK.
