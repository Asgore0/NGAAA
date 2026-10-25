# Native multi-post reading position

This helper consumes the explicit immutable product class JAR and APK. It compiles only the fixture and packages the unchanged production classes under its own UID, `cn.ngapure.anchorprobe`, without INTERNET permission. All PNGs and post text are generated locally. It reads no client account state and performs no community requests or writes.

Build with installed JDK 17 and Android SDK 35 tools:

```bash
PRODUCT_CLASSES=/absolute/path/to/production.jar \
PRODUCT_APK=/absolute/path/to/candidate.apk \
SIGNING_DIR=/absolute/path/to/external/signing \
BUILD_DIR=/absolute/path/to/helper-output \
bash tests/multi_post_anchor_device/build.sh
```

The output is `multi-post-anchor-probe.apk`. After installing it on a dedicated emulator, run instrumentation `cn.ngapure.anchorprobe/cn.ngapure.anchorprobe.MultiPostAnchorProbe`. Its JSON result key is `safe_native_multi_post_anchor_report`.

Eight actual Android layout cases cover a delayed first image after the reader moves to the next independent `NativeRichText`, cancellation outside the prefetch margin, the existing same-owner anchor, a Down/Up gesture at unchanged scroll position between capture and layout, a new scroll, detaching the old page, a pointer already down when decoding completes, and a remaining 1 dp of bottom padding after the old text layout has left the viewport. The fixture compares the screen baseline of the same reading character offset, image span growth and scroll position. The interruption cases assert that the old anchor does not force a new scroll. Its custom `ScrollView.dispatchTouchEvent` uses the same public observation hook as the client.

The helper stores before/after screenshots only in its own private `files/synthetic-anchor-screenshots` directory. It is debuggable solely to export those synthetic screenshots. Native scrolling and touch dispatch are programmatic fixture operations, not injected human finger gestures. This does not claim to test every OEM layout engine or real network timing.

The binding asset records independent input APK/JAR digests, every production class digest, fixture source/manifest/build-script digests and the absence of class rewriting. It contains no local filesystem paths. The signing key remains external. Optional `edgeOnly=true` runs just the padding boundary; `expectEdgeStable=false` exists only to record a known historical baseline and must not be used for final acceptance.
