# Native rich text visibility work probe

This separate application has its own UID and no `INTERNET` permission. It loads
the immutable, complete production classes JAR without rewriting or shadowing
product classes. The probe supplies only its own Activity, counting ScrollView,
an in-process anonymous HTTPS fixture transport, and a generated 512px PNG.
It never starts the real client, reads account data, or writes to the community.

```sh
PRODUCT_JAR=/absolute/path/production.jar \
PRODUCT_APK=/absolute/path/production.apk \
PRODUCT_JAR_SHA256=independently_recorded_jar_sha256 \
PRODUCT_APK_SHA256=independently_recorded_apk_sha256 \
SIGNING_DIR=/absolute/path/private-signing-directory \
BUILD_DIR=/absolute/path/fresh-isolated-helper-directory \
bash tests/rich_visibility_device/build.sh
```

The output `binding.json` records every product class, helper source, and input
JAR/APK hash. A fresh absolute build directory and an external signing key are
required. The key is never copied into the helper or report.

On a dedicated test emulator, run each APK in a fresh instrumentation process:

```sh
adb install -r /absolute/path/rich-visibility-probe.apk
adb shell am instrument -w -e expectShared false \
  cn.ngapure.richvisibilityprobe/cn.ngapure.richvisibilityprobe.RichVisibilityProbe
# Use -e expectShared true for the candidate, then remove only this helper.
adb uninstall cn.ngapure.richvisibilityprobe
```

The `safe_rich_visibility_report` result includes:

- A native 50-image long post, with all spans belonging to one real TextView.
  After layout/decoder warmup, 500 synchronous visibility passes count calls
  through ScrollView's real `getGlobalVisibleRect(Rect, Point)` and
  `getLocationInWindow()`. `getLocalVisibleRect()` is final and delegates to the
  counted overload; the probe does not replace its implementation. The baseline
  must read the viewport at least 50 times per pass, while the candidate must
  read it once per owner per pass. CPU time is an observation, not an FPS claim.
- Direct animation visibility predicates must sample afresh outside a batch.
  A new scroll position must be visible immediately to the next predicate.
- A single real inline image receives loader-busy responses while 32 fixture
  subscribers occupy the product's bounded loader. The original retry deadline
  and a single pending Android callback survive 200 visibility checks. The
  callback then actually retries near its original deadline. Public
  `Handler.hasCallbacks()` and `Handler.dump()` inspect pending messages without
  hidden APIs. Scroll-out, detach, and successful recovery must clear the pending
  callback and deadline. Candidate bookkeeping is checked as additional evidence;
  production code is not instrumented to count scheduling operations.

Baseline mode passing means the repeated viewport work was reproduced and the
existing retry behavior was recorded. It is not a performance pass. This focused
probe does not replace the existing `media_device` bounded-retention/size/return
tests, `horizontal_media_device` LTR/RTL/table clipping tests, or
`reader_layout_device` delayed-image reading-position tests.
