# Native search and share UI fixture

Build against the immutable release classes JAR and matching APK using `build.sh`. Required inputs are `PRODUCT_JAR`, `PRODUCT_APK`, their independently recorded SHA-256 values, an isolated absolute `BUILD_DIR`, and external `SIGNING_DIR`. The binding lists every original Main/search/helper class hash and does not rewrite production classes.

The fixture package `cn.ngapure.searchprobe` has its own UID, no Internet permission, no shared UID and no exported activity. Run its instrumentation on API 29 or later with `syntheticSearchOnly=true`. All preferences, keywords and accounts are synthetic and confined to that package. All HTTP(S) connections are additionally replaced with a local adapter that throws before opening a socket. No ADB or real app is started by the build script.

Cases use the actual product MainActivity, real Android preferences, view clicks and framework Activity.recreate(): recent-keyword fill/delete/clear; unsubmitted input recovery; explicit global/current/negative-board/thread-derived range restoration; result-page repeat-search scope and keyword; stable account storage versus exact-session action guards; invalid incoming share text refusing editor/login routes; direct board validation; and dark radio-button ink/tint. This does not claim a successful live NGA search response, real-account navigation, clipboard integration or physical fold-device performance.

Optional `geometryAudit=true` appends geometry checks after every existing behavior
check. The helper overrides only its own newly created MainActivity to density
160 and fontScale 1.0 or 2.0, and gives that Activity's Window a 412×900 or
220×900 pixel size. No global display, font, keyboard or real-client settings are
changed. The report first requires the actual decor/safe-pane width to match and
at least 760px safe height. A mismatch fails in `geometryFixtureWindow`, indicating
that the test surface prerequisite was not met rather than a label defect.

At 412px/fontScale 1.0, two synthetic recent keywords exist before the first search
show. Without scrolling, the actual heading and first keyword must be fully inside
the closest body ScrollView's visible rectangle. Clearing history must retain the
original empty hint while removing the unnecessary heading and clear action.

The four window/font combinations measure the actual production RadioGroup and
RadioButtons with a short synthetic board label and a long synthetic board label.
Every line must fit its view's text box with no ellipsis and no missing final
characters; both targets must retain at least 48dp and must not intersect. The
suite requires both horizontal and vertical arrangements to occur. Long content
may legitimately need body scrolling; it is not required to fit the whole dialog
viewport at once. The report records actual window sizes, line bounds, text-box
sizes and target rectangles. This does not replace emulator screenshots of the
real client or claim physical fold/IME behavior.
