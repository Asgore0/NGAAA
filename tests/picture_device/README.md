# Local native picture viewport checks

This helper runs the exact `NativePictureView` classes from a pinned product JAR in its own UID, without INTERNET. It uses generated bitmap pixels only. It verifies normalized zoom/center restoration before and after asynchronous layout/image ordering, repeated restoration, malformed state, real Android double-tap and drag events, accessibility zoom and 50 rapid size changes. It does not start the main client or read its account.

Set `PRODUCT_JAR`, `PRODUCT_APK`, `SIGNING_DIR` and optionally `BUILD_DIR`, then run `bash build.sh`. The runner must independently verify the product artifact hashes in `binding.json`; the helper output alone does not attest which main-client APK is installed.

On the dedicated test device, install the resulting `picture-probe.apk`, then run `am instrument -w cn.ngapure.pictureprobe/cn.ngapure.pictureprobe.PictureViewportProbe`. The output key is `safe_picture_viewport_report`. Remove only this helper package after the run.

This isolated geometry test complements the installed MainActivity recreation check in `tests/recreation_device`; it is not a claim of device-wide performance or accessibility certification.
