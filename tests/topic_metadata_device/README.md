# Native topic metadata layout fixture

This no-INTERNET helper uses the exact complete candidate production JAR and public assets in its own UID. It creates the actual private `MainActivity.TopicListMeta` through reflection, changing only synthetic fixture values and layout width. No product class is rewritten and neither reaction target is clicked. Startup read attempts are blocked by an in-memory URL handler before a socket can open.

The 54 measured cases cover 220 / 390 / 720 dp, local font scale 1 / 1.3 / 2, light / dark styling and unknown / known-zero / maximum-integer count values. As of 0.20.5, invariants require no reaction targets in previews, nonoverlapping and fully readable reply counts and right-aligned timestamps, and exact reply totals. Unknown, zero and maximum vote inputs must not reintroduce reaction controls. These are measured Android layouts, not physical fold posture, frame-rate measurements or a live vote test.

Build using `PRODUCT_JAR`, `PRODUCT_APK`, their explicit `PRODUCT_*_SHA256` values, private `SIGNING_DIR`, and a fresh absolute `BUILD_DIR`. Run only on the authorized dedicated emulator:

```
am instrument -w -r -e syntheticTopicMetadataOnly true cn.ngapure.metadataprobe/cn.ngapure.metadataprobe.TopicMetadataProbe
```

Require `passed:true`, `observedFailures:0` and all 54 cases in `safe_topic_metadata_report`. Uninstall the helper after use. The physical emulator window must be at least 720 pixels wide; the local activity density is 160 dpi and does not change global settings.
