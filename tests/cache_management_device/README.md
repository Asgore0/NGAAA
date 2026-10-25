# Isolated Android disposable media cache verification

This new helper verifies the production `NativeImages.cacheStats()` and `clearCaches()` APIs on Android. It targets its own `cn.ngapure.cacheprobe` package, declares no INTERNET permission or shared UID, generates a 32 × 24 PNG locally with the actual Android encoder, uses the actual Android image decoders and displays the decoded bitmap in its own native `ImageView`. The instrumented production classes come directly from an explicitly pinned immutable candidate JAR; they are never copied into the helper sources, rewritten or replaced with JVM decoder stubs.

The in-memory HTTP connection factory only returns generated PNG bytes for fixture URLs. It rejects other requests and checks that no Cookie or Authorization header is sent. No socket is opened; the official client is not launched, its data is not copied and its account is not read.

Checks cover:

- Actual encoded-file sizes (including cache headers), LRU accounting and a successful clear.
- Preservation of unrelated files, cache-shaped nested directories, a real symbolic-link entry and its target; no traversal through a linked cache root.
- A bitmap already displayed by an attached `ImageView` remains valid and unrecycled after clearing and reloading.
- Two requests blocked in synthetic response headers plus a third queued request cannot repopulate memory or disk after clear, although all still deliver valid images to their original listeners. A new request caches normally.
- An avatar blocked before clear can still deliver but cannot refill the LRU; a new avatar can cache normally.
- Two production loaders sharing a real directory observe the same generation and local-copy qualification, including invalidation from the other owner.
- An actual app-private directory changed to mode 0500 reports failed deletions and the real remaining bytes; mode 0700 is restored in `finally`, and retry succeeds.

Build only after the candidate is immutable:

```sh
CACHE_MANAGEMENT_BUILD_DIR=/absolute/work/helper-build \
PRODUCT_JAR=/absolute/work/immutable-production.jar \
PRODUCT_JAR_SHA256=<reviewed-sha256> \
PRODUCT_APK=/absolute/work/immutable-candidate.apk \
PRODUCT_APK_SHA256=<reviewed-sha256> \
SIGNING_DIR=/absolute/external/signing \
bash tests/cache_management_device/build.sh
```

The build checks both product hashes before compilation, before D8 and after signing. `assets/binding.json` records both hashes and every production class hash; `product-class-binding.json` additionally binds the resulting helper APK hash. The signing key is external and is never included in the helper or exported source.

The coordinating device runner may install this helper on the dedicated test emulator and invoke:

```sh
adb shell am instrument -w -r -e syntheticCacheOnly true \
  cn.ngapure.cacheprobe/cn.ngapure.cacheprobe.CacheManagementProbe
```

Parse the `safe_cache_management_report` JSON and require `passed`, `cleanupPassed`, every check and the expected immutable product hashes. Keep this new run and binding separate from older media-helper results. Remove only this helper package afterward. Building alone is not Android runtime verification.

Reported `memoryBytes` are the loader LRU accounting, not total process memory, native heap or displayed-image memory. These synthetic local checks do not measure real network throughput, physical foldable performance or system-wide storage usage.
