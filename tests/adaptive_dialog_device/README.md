# Adaptive native dialog and form fixture

Build with explicit immutable `PRODUCT_JAR`, matching `PRODUCT_APK`, their
independently recorded `PRODUCT_JAR_SHA256` / `PRODUCT_APK_SHA256`, external
`SIGNING_DIR`, and a fresh absolute `BUILD_DIR`, then run `build.sh`.
The output `adaptive-dialog-probe.apk` contains the full unmodified production
jar plus a separate helper package. `binding.json` records every product class.
There is no INTERNET permission or shared UID; all data belongs to the helper.

The sole root-owned device runner installs it and invokes:

```
am instrument -w -e syntheticDialogsOnly true \
  -e kind all -e width 220 -e font 2.0 -e dark false \
  -e tag baseline220f2 -e fixed false \
  cn.ngapure.adaptivedialogprobe/cn.ngapure.adaptivedialogprobe.AdaptiveDialogProbe
```

`kind` accepts `all`, `login`, `editor`, `gallery`, or `page`. Width is an actual
measured local pane width in dp (160–1000); font 1–2 is a local Activity override.
The helper never changes global display settings. These are measured layouts,
not a physical hinge-pose test. For the fixed product repeat with `-e fixed true`;
then every targeted text layout and the QR card must fit its measured bounds.
Use a unique alphanumeric/hyphen/underscore `tag` per run.

Read `safe_adaptive_dialog_report`. Baseline mode reports and captures defects
without failing solely because text clips. Fixed mode fails if any measured target
still clips. The report distinguishes pane width from full display width for the
QR card. Gallery arrow symbols use baseline plus actual glyph bounds, since font
padding alone can exceed their box while the complete glyph remains visible.
The page input keeps strict line-advance checks and exercises long → short → long
values with draft/selection preservation. Diagnostic line widths, Paint advances,
glyph bounds, scroll offsets and selection ranges are recorded. Login inputs
remain empty; the editor contains deliberately synthetic
local text and one pending attachment. No credential, SMS, login confirmation,
image export, attachment picker, page navigation, preview, or community publish
button is activated. Anonymous QR preparation and editor permission preparation
use only in-process explicitly bounded synthetic URLs; image URLs return a local
bitmap. An unexpected URL throws before opening any socket.

UiAutomation captures actual display screenshots into the helper's `files/`.
Absolute screenshot paths and the JSON report path appear in the instrumentation
result. The root runner can pull them with `run-as` through its device tooling.
The manifest declares package visibility for `cn.ngapure.app`; it never starts or
reads account data from that application. Remove the helper after verification.
