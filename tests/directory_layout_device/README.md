# Native directory card layout fixture

This independent no-INTERNET helper hosts the actual production `MainActivity.DirectoryTile` and calls its real binder with a synthetic title/category. The product's `BoardDirectory.columns` calculates the tile width for 390 / 520 / 760 dp panes at local font scales 1 / 1.3 / 2. One short and three observed long directory names, with both constructor color flags, produce 72 cases. Public images and startup reads cannot open sockets; favorite targets are never clicked.

Checks cover whole two-line title readability, category text, the preserved native avatar size, independent 48 dp favorite bounds, labels not overlapping the favorite target, unchanged tags/click bindings/accessibility semantics and at most 100 dp compact card height at ordinary font size. It does not assert that one arrangement is aesthetically superior, change the product column count, or claim physical fold or live favorite synchronization testing. Exact light/dark appearance is checked separately from client screenshots.

Text drawing bounds begin with `getDrawingRect` before ancestor conversion so TextView's internal single-line scrolling is not mistaken for an external offset. Text width excludes trailing whitespace using `Layout.getLineMax`; all visible content and ellipsis counts remain checked.

Build with explicit immutable `PRODUCT_JAR`, `PRODUCT_APK`, both `PRODUCT_*_SHA256`, external private `SIGNING_DIR` and a fresh absolute `BUILD_DIR`. Run only on the authorized emulator (at least 760 physical pixels wide):

```
am instrument -w -r -e syntheticDirectoryLayoutOnly true cn.ngapure.directorylayoutprobe/cn.ngapure.directorylayoutprobe.DirectoryLayoutProbe
```

Require `passed:true`, `observedFailures:0` and 72 cases from `safe_directory_layout_report`. The same JSON is exported at `files/directory-layout-report.json`. Keep the existing `board_directory_device` suite for real synthetic star taps, directory navigation, reflow and recreation behavior; this layout fixture does not replace it.
