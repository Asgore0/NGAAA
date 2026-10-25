# Subforum reflow regression

Build with the same explicit immutable `PRODUCT_JAR`, `PRODUCT_APK`, SHA-256, private `SIGNING_DIR`, and fresh absolute `BUILD_DIR` variables as the other independent device helpers.

Runner: `cn.ngapure.subforumreflowprobe/cn.ngapure.subforumreflowprobe.SubforumReflowProbe`.
Required arguments: `syntheticSubforumReflowOnly=true` and `expectFixed=true` (final) or `expectFixed=false` (known-broken baseline).

The isolated UID has no INTERNET permission. It calls the actual private `MainActivity.subForums` using synthetic public forum metadata. Real Android GridLayout measurement and layout run synchronously on the main thread in a detached tree so a baseline exception is caught without crashing the instrumentation process. It preserves the same grid and child instances across 520→320→760→220 dp twice, at font scales 1 / 1.3 / 2 and with 2 / 5 expanded children, then verifies the real collapse and expand callbacks. It checks current grid specs, all child widths/bounds, non-overlap and 48dp board/favorite/expand targets. It never clicks a favorite or navigates to a real forum.

The final run requires 60 completed measurements and all six sequences without exceptions. The negative control requires the exact `columnCount` shrink exception from the product's GridLayout onMeasure in all six sequences. `passed` always means the product has fixed behavior; a reproduced broken baseline therefore has `passed=false`, `knownRegressionReproduced=true`, and `expectationMatched=true`. Instrumentation exit status follows `expectationMatched`.

Report: `safe_subforum_reflow_report`, plus `files/subforum-reflow-report.json`. This does not claim a physical folding-device test or a real network write.
