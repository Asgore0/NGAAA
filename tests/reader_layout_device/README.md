# Native reader line and size fixture

This independent application packages the entire exact product JAR unchanged,
declares no INTERNET or shared UID, starts only its own synthetic Activity, and
never accesses the main client's data or account. An in-process URL transport
accepts only explicit generated 120×180, 1200×800, 120×600 PNGs and a generated
24×24 stand-in at the official emoji URL. It does not fetch source images.
The exact main-client package is declared in `queries` only so the guard can
compare its UID on Android 11 and later; the client must already be installed.

Build using explicit immutable `PRODUCT_JAR`, `PRODUCT_APK`, their independently
recorded `PRODUCT_JAR_SHA256` and `PRODUCT_APK_SHA256`, external `SIGNING_DIR`, and
a fresh absolute `BUILD_DIR`. Run `bash build.sh`. The binding asset includes all
production class hashes and rejects any helper class that shadows one.

The sole root-owned device runner installs `reader-layout-probe.apk` and runs:

```text
am instrument -w -e syntheticReaderOnly true -e expectFixed false \
  cn.ngapure.readerlayoutprobe/cn.ngapure.readerlayoutprobe.ReaderLayoutProbe
```

Read `safe_reader_layout_report`, requiring `completed: true`, `passed: true`,
the exact artifact binding, and complete `measurementReport`. Baseline mode
expects at least one candidate defect to reproduce and records
`defectReproduced: true`; a passing baseline run is evidence of reproduction,
not acceptance of the fixed product. For final acceptance set `expectFixed true`:
ordinary images must occupy their own native line, emoji must remain inline,
and actual `MetricAffectingSpan.updateMeasureState` paint ratios must equal
200%, 50%, 500%, and the inner 200% relative to the reader base size.
Fixed mode additionally verifies attachment boundaries, outer 150% sizing,
bold text, and attachment link callbacks inside quotes, tables and lazy
collapses. It checks equal-range nested sizes, intervening colors, headings,
and allowed font families. These scenes save `scope-quote.png`,
`scope-table.png`, `scope-collapse.png`, and `size-scopes.png` alongside the
main screenshots.
Run fixed mode again with `-e fontScale 2.0` (also accepts `1.3`; default `1.0`).
The override belongs only to the helper Activity. The expected size ratios are
exact ratios to `TextView.getTextSize()` after Android's font scaling, including
API 35 nonlinear scaling; they are not independently scaled target-sp values.
The report records the requested local scale and verifies that it was applied.

Scenarios run independently so one failure does not discard later samples.
Each stable scene calls `UiAutomation.takeScreenshot()`, saving only this
synthetic Activity to the helper's private files directory:

```text
files/synthetic-reader-screenshots/mixed-image.png
files/synthetic-reader-screenshots/wide-image.png
files/synthetic-reader-screenshots/emoji-inline.png
files/synthetic-reader-screenshots/size-16.png
files/synthetic-reader-screenshots/size-22.png
files/synthetic-reader-screenshots/delayed-before.png
files/synthetic-reader-screenshots/delayed-after.png
files/reader-layout-report.json
```

The delayed image sample records the same character's screen baseline before
and after the generated image is released. It is diagnostic only; the fixed
assertions do not force changes to the existing reading-anchor algorithm.
The Activity uses local 160dpi and the requested local fontScale, and a measured
392px panel with 360px body width. No global display settings are changed.
The helper tests Android layout semantics, not physical hinge poses or frame rate.

## Image spacing audit

The existing 27 fixed-mode assertions remain unchanged. Each image sample now
also records the measured image-line height, the difference between `Layout.getLineBottom(line, true)` and
`Layout.getLineBottom(line, false)`, the ordinary
preceding text line's metrics, and the draw-top position implied by Android's
bottom-aligned `ImageSpan`. These are layout measurements; the screenshots
remain the visual check for the actual draw result.

Run a spacing baseline against the immutable 0.20.1 product with
`-e expectFixed true`. The report's `image-spacing-audit.inflationReproduced`
distinguishes image-height-driven whitespace from the already fixed inline
attachment and BBCode-size issues. Add `-e expectCompactImageSpacing true` for
the new candidate: two extra assertions compare line-extra growth between
180px and 240px images and between the 80px placeholder and the decoded 600px
image. They allow 1px rounding, without prescribing a particular font size or
line-height value. Repeat at fontScale 1.0 and 2.0. The anchor sample remains
diagnostic and no production anchor or media lifecycle behavior is replaced.
