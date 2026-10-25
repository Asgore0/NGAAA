# Isolated Android unread JobService lifecycle verification

This helper registers the actual production UnreadNotificationService as an Android JobService. Android's JobScheduler starts it, stops its blocked read, then starts a replacement one-shot job. The real NativeNotifications adapter and NativeCommunity transport are used. All HTTPS responses are in-memory objects, every unscripted endpoint fails before a socket opens, all unread counts are zero, and no notification is published.

It runs in a separate UID with synthetic preferences/keystore state and no INTERNET permission. Grant POST_NOTIFICATIONS only to `cn.ngapure.unreadfixturev195` so the production eligibility check can pass; never change the installed client's permission or settings. It cannot access that client's credentials. The helper schedules/cancels only job 12092 in its own UID, and it removes that job during cleanup.

```sh
PRODUCT_JAR=/absolute/pinned/classes.jar \
PRODUCT_JAR_SHA256=<reviewed-jar-sha256> \
PRODUCT_APK=/absolute/candidate.apk \
PRODUCT_APK_SHA256=<reviewed-apk-sha256> \
SIGNING_DIR=/absolute/external-signing-directory \
UNREAD_LIFECYCLE_BUILD_DIR=/absolute/isolated-helper-output \
bash tests/unread_lifecycle_device/build.sh
```

API 35, build-tools 35.0.0 and JDK 17 are required. The script downloads nothing, starts no device, accesses no ADB and never implicitly selects a mutable project jar. It emits the helper APK, permission dump and class binding with hashes of all production classes and both APKs. The signing key stays outside this source tree.

Run only on the root task's designated emulator. Instrumentation component: `cn.ngapure.unreadfixturev195/cn.ngapure.unreadfixturev195.UnreadLifecycleProbe`; required argument: `offlineUnreadLifecycle=true`; structured result key: `safe_unread_lifecycle_report`. Normal mode verifies that an eligible read deferred by an existing owner requests a retry, then tests actual Android job cancellation, active socket disconnection, replacement completion, successful baseline persistence and no notification.

The optional `observeBusyDeferralOnly=true` mode only observes the production busy-owner return value without a network request or scheduled job. It exists to capture before/after defect evidence. Its `passed` field means observation completed, not that the observed behavior is correct; `busyEligibleReadReturnedSuccess=true` identifies the defect. Normal verification requires that value to be false.

Uninstall the helper afterward. Helper classes, synthetic account data and signing keys are not part of the product APK.
