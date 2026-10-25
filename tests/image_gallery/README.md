# Original-post gallery state checks

`bash tests/image_gallery/run.sh` runs 140 host assertions without HTTP, Android
state or accounts. The test-only `org.json` dependency is SHA-256 pinned and accepts
`TEST_JSON_JAR` (the isolated release runner seeds it before blocking downloads).

The real production value model is checked for clicked-image index mapping,
immutable allowlisted attachment URLs, independent current-image viewport state,
bounded JSON restoration, rapid index changes, explicit source-count limits,
unknown count labels, and malformed or oversized saved-state rejection.

This suite does not prove touch behavior, rendering or frame rate; see the separate
Android `image_gallery_device` fixture and the real-client emulator recording.
