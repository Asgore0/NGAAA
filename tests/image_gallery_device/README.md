# Native original-post gallery fixture

Build with the same explicitly hashed `PRODUCT_JAR`, `PRODUCT_APK`,
`PRODUCT_JAR_SHA256`, `PRODUCT_APK_SHA256`, `SIGNING_DIR`, and fresh `BUILD_DIR`
variables used by the other device helpers, then run `build.sh`. It produces
`image-gallery-probe.apk`; the binding records every copied production class.
The helper declares no INTERNET permission or shared UID, and uses an independent
`cn.ngapure.galleryprobe` application with explicit synthetic attachment URLs.

The sole root-owned device runner installs this helper and invokes:

```
am instrument -w -e syntheticGalleryOnly true \
  cn.ngapure.galleryprobe/cn.ngapure.galleryprobe.ImageGalleryProbe
```

Read `safe_image_gallery_report`. A pass requires both instrumentation completion
and `passed: true`, plus the normal exact-product binding checks. Product gallery,
preview, image viewport and anonymous media classes are copied without rewriting.

The fixture checks third-thumbnail entry into the complete original-post image
set; actual native touch dispatch for next/previous, end rubber band, zoomed pan,
quick reverse, cancellation and interrupted settling; current index and viewport
restoration; actual measured 400 → 760 → 220 dp layouts at local font scale 1.3;
explicit clipped-source totals; canceled media and retired identity callbacks.

No real user account, cookies, community writes, online source images or global
display settings are used. Local measured layouts are not a physical hinge-pose
test. These assertions do not measure display frame rate.

Fixture revision note: the original first execution did not reach gallery checks.
Its preview owner contained only the initially GONE preview, so WRAP_CONTENT
measured the owner to zero height and production correctly skipped discovery.
The revised fixture includes the actual client’s visible title above the preview,
awaits a visible nonzero owner before metadata, and emits safe failure diagnostics.
Production viewport guards and expected gallery assertions are unchanged.
