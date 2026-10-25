# Native board directory Android probe

This helper declares a separate package/UID with no INTERNET permission. It loads
the exact immutable production JAR without rewriting any class and copies public
assets byte-for-byte from the matching APK, including the real bundled catalog.
It runs the actual final `MainActivity`; that class is final and is not subclassed.
No real installed client storage or account is accessed.

The instrumentation applies a density 160 / font scale 1 override before the local
Activity is attached, then changes its actual `FoldAdaptiveLayout` root width to
520/760/320 dp, capped to the available display width. This drives real Android
measure/layout and grid-column changes without setting product column/row fields,
changing global display settings, or claiming a physical fold posture test. The
dedicated emulator must offer at least 684 physical pixels for the three-column
case. All HTTP/HTTPS opens are intercepted and rejected before any socket exists.

Coverage includes the 658 canonical public catalog keys and collections; local
collection filtering and IME handling; stable-key visibility through 2/3/1-column
reflow; filter/category/touch/exit cancellation of pending restores; native board
entry and back navigation; actual `Activity.recreate()` with category, query and
signed pixel anchor recovery; and real pointer taps on the star child without
opening its parent tile. Star changes affect only this helper's synthetic account
and cannot reach a server. No production success/loaded/column/row state is injected.

The fixture reapplies its measured-width constraint after each native navigation
and before awaiting the directory: `Main.go` creates a new layout root without
resuming the Activity. The C4 first attempt passed 22 assertions, then timed out
after board/back navigation; that attempt and its exact original sources remain
under `board-directory-build-c4/helper-source-original`. R2 corrects this fixture
constraint and adds a sanitized failure message plus actual root/list widths,
expected/actual column counts and pending-restore diagnostics. It changes no
product behavior or assertion expectation.

Build with explicit immutable `PRODUCT_JAR`, `PRODUCT_APK`, their independently
recorded SHA-256 values, external `SIGNING_DIR`, and a fresh absolute `BUILD_DIR`,
then `bash tests/board_directory_device/build.sh`. The binding includes all input
production class and asset hashes plus helper source hashes. No device is contacted
by the build script.

Only the root driver should install/run/uninstall the helper on the dedicated
emulator. Instrumentation component:
`cn.ngapure.directoryprobe/cn.ngapure.directoryprobe.BoardDirectoryProbe`, argument
`-e syntheticDirectoryOnly true`, output key `safe_board_directory_report`.

0.20.5 adds recycled selected/pending-to-unselected star checks, pending-only completion, same-UI-turn stale card/star/long-press rejection for account/token/host/logout changes, and an empty favorites cold-entry case. All changes remain in this helper’s synthetic UID.
