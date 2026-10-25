# Native table image visibility probe

This separate Android application has its own UID and no `INTERNET` permission. It
loads the exact immutable product classes JAR without rewriting production
classes, decodes a locally generated two-frame GIF with Android's real image
decoder, and substitutes an in-process HTTPS fixture transport. It never starts
the main client, reads its account data, or writes to the community.

Build with explicit immutable inputs and an isolated output directory:

```sh
PRODUCT_JAR=/absolute/path/production.jar \
PRODUCT_APK=/absolute/path/candidate.apk \
PRODUCT_JAR_SHA256=independently_recorded_jar_sha256 \
PRODUCT_APK_SHA256=independently_recorded_apk_sha256 \
SIGNING_DIR=/absolute/path/private-signing-directory \
BUILD_DIR=/absolute/path/isolated-horizontal-probe \
bash tests/horizontal_media_device/build.sh
```

The build directory contains `horizontal-media-probe.apk` and `binding.json`,
which records product JAR/APK, every production class, fixture, and helper source
hash. Explicit input hashes, when supplied, are verified before building. The
output directory must be fresh and absolute; existing attempts are preserved.
The signing key is required externally and is never copied into the helper.

Run on a dedicated test emulator, then remove only this helper package:

```sh
adb install -r /absolute/path/isolated-horizontal-probe/horizontal-media-probe.apk
adb shell am instrument -w -e expectFixed true \
  cn.ngapure.horizontalprobe/cn.ngapure.horizontalprobe.HorizontalMediaProbe
adb uninstall cn.ngapure.horizontalprobe
```

Output key: `safe_horizontal_media_report`. On an unchanged 0.19.5 baseline,
omit `expectFixed` to assert and record the observed defects. The baseline mode
passing means reproduction succeeded, not that the product passed the fixed
behavior requirements.

Coverage:

- Eight table columns: completely clipped GIFs do not animate, and a column
  outside the 320dp horizontal prefetch margin does not request its fixture.
- Scrolling to the last columns starts revealed images and stops hidden images.
- Two columns follow the current native measure constraint through repeated
  1400px/800px changes, including the first measurement.
- A short image in a wide `colspan8` cell follows its own visible position even
  while its owning TextView remains visible, in LTR and RTL paragraphs.
- A short image exactly at an automatically wrapped line boundary still uses
  its own horizontal position when hidden and revealed, in both LTR and RTL.
- A whole table 80dp below the viewport still prefetches vertically; animations
  stay stopped until revealed, then start without another request.

The eight-column fixture expects a phone viewport with at least one column
beyond 320dp of horizontal prefetch. The 1400px/800px geometry checks assume
112dp is no more than 400px; normal test density 420dpi satisfies this. This probe
does not certify physical-device frame rate or measure the network usage of
real forum posts.

For a focused line-boundary diagnostic, add `-e mode wrapped`. It reports the
selection Path, its filled Region, cached image X bounds, paragraph direction,
and native line offsets. The fixed full run has 43 assertions; the focused run
has 14. Paths may contain zero-width next-line contours and paragraph
continuation fill, so their overall bounds alone are not the image bounds.

For the v201 ordinary-attachment paragraph contract, add
`-e blockAttachments true -e expectFixed true`. The original default mode and
all its visibility, animation and LTR/RTL line-boundary assertions are retained
for historical inline-attachment builds. Block mode keeps the eight-column
prefetch/animation checks, current-constraint resizing and vertical prefetch.
It separately verifies ordinary images have their own native line in a wide
colspan cell, then checks real animation state and `shouldRun()` against the
image's actual selection bounds as the cell remains visible during horizontal
scrolling. Both LTR and RTL are covered; only the synthetic owner TextView's
public `LTR` / `RTL` text direction is set so a neutral image-only paragraph is
actually measured in both directions. The helper enables `supportsRtl=true`,
matching the production manifest. Early helper attempts omitted this flag and
observed resolved direction `FIRST_STRONG=1` even after requesting RTL; the
real image-paragraph direction assertion caught the setup mismatch. It remains
required. The report verifies the application RTL flag and records owner
identity/direction before attach and after decode, plus direct first-strong and
forced heuristic results for the same image row.

The original wrapped-edge checks still execute in block mode using official
inline emoji declarations backed by the same locally generated two-frame GIF.
They retain the actual cross-line boundary, paragraph direction, animation,
reverse visibility and one-fetch assertions. No official image is fetched,
and no production spans or classes are rewritten. The report records
`blockAttachments: true` and the changed wrapped-boundary asset contract.
Unlike a wide ordinary-image placeholder, a 32dp inline emoji at the distant
LTR line end begins outside prefetch. Block mode asserts that no request starts
there, scrolls to reveal it for decoding, then restores the initial left edge
before running the retained boundary and animation assertions. RTL's line-end
emoji begins visible and needs no such reveal. This setup warmup is recorded.
