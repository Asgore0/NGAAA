# Isolated everyday-feature Android probe

The helper runs the exact immutable product JAR in package `cn.ngapure.everydayprobe`.
It declares neither INTERNET nor a shared UID and cannot read the installed real
client's data. All thread responses and identities are synthetic, produced by an
in-process URL handler. It starts the actual `MainActivity` and uses reflection
only to navigate or invoke the same local progress hooks; production bytecode is
never rewritten and loaded-state fields are never manually asserted.

Coverage: real Android preferences and reader geometry; history opt-out, single
removal and clearing; local favorite removal and clearing; successful/failed/pending
thread progress; account switch between page load and persistence; same-account
credential refresh retains the local identity scope; first-page
start-over versus stale scroll, including an already-open first page actually
scrolled down before choosing start-over; durable-page continuation. This does not verify
real NGA credentials, community writes, or Android process death.

Incoming `ACTION_SEND` checks use actual `routeIntent`: one native thread link,
multiple-link chooser and selection, stale-session choice rejection, invalid/write
destinations, unsupported MIME, overlong text and malformed EXTRA_TEXT type. Outgoing
floor shares are intercepted by a blocking instrumentation ActivityMonitor, so the
system chooser intent can be inspected without opening another application or
transmitting anything. A valid PID is retained; an invalid PID falls back to the
explicit current page.

Reader saves run on the actual product executor. The probe inserts an executor
barrier before negative assertions and waits up to five seconds for expected saved
positions rather than assuming synchronous SharedPreferences commits.

Account-switch coverage blocks the new account's actual response separately:
neither the old page nor a pending new-account read may create new-account progress.
After releasing that response, the actual newly loaded page may save progress when
leaving. The first C2 device run incorrectly required the new account to have no
progress even after this legitimate successful reload; retain that superseded
fixture-expectation report rather than treating it as a product regression.

Build with explicit `PRODUCT_JAR`, `PRODUCT_APK`, their independently recorded
`PRODUCT_JAR_SHA256` / `PRODUCT_APK_SHA256`, external `SIGNING_DIR`, and a fresh
absolute `BUILD_DIR`, then run `bash tests/everyday_device/build.sh`. The build
records exact product and source hashes in `binding.json` and checks the resulting
manifest has no INTERNET permission.

Only the root device driver should install/run/uninstall it on the dedicated
emulator. Instrumentation component:
`cn.ngapure.everydayprobe/cn.ngapure.app.EverydayProbe`; argument
`-e syntheticEverydayOnly true`; output key `safe_everyday_report`.
