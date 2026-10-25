# Automatic favorite synchronization device check

This independently built instrumentation is never bundled in the client APK. Build the
production client first, then run `bash tests/auto_device/build.sh`. The output is
`.build/auto-device/verify.apk`. Do not rebuild the client while this helper is compiling.

Every stage requires explicit `-e realAutomaticFavorites true -e mode MODE` arguments to
`cn.ngapure.autoverify/cn.ngapure.autoverify.AutoFavoriteSyncProbe`. Operate only the dedicated
NGA test emulator. The test needs an existing authenticated session, no pending QR login,
and either disabled automatic sign-in or a sign-in already completed today. It does not
log in, send SMS, submit credentials, or sign in.

Run these stages in order:

1. `prepare`: waits for the normal automatic pull, saves a private rollback journal, reads
   the complete cloud baseline, then removes its local projection without a cloud write.
2. Force-stop the production app and launch its normal launcher Activity. Wait for its
   startup automatic synchronization, without tapping a sync control.
3. `check-startup`: checks the restored favorite projection before starting any Activity
   from the helper, then independently verifies that the cloud baseline is unchanged.
4. `online`: clicks the production bound favorite star for public child forum `754`, waits
   for automatic synchronization, independently verifies the opposite state, clicks again,
   and verifies the complete original cloud keyset. It never clicks the manual sync control.
5. Disable Wi-Fi and mobile data on the dedicated emulator, preserving their original
   states. Run `offline-queue`; it requires the production attempt to leave durable pending
   intent. It does not fake a network failure or server confirmation.
6. Force-stop the client, restore emulator connectivity, normally launch the client, and
   wait for its automatic startup retry. Run `check-retry`, which checks that pending intent
   was drained before starting an Activity from the helper and independently reads it back.
7. Run `cleanup` with connectivity available, even after a failed test stage. It restores
   only forum `754` to its original cloud state, verifies the whole cloud keyset, restores
   favorite preferences and icon metadata, checks unrelated settings and session stability,
   and deletes the private rollback journal only after verification.

The journal is app-private and bounded to 2 MiB. It contains favorite data and opaque
SHA-256 scope/session/settings fingerprints, never passwords, cookies, or account IDs.
Stage reports under `safe_auto_favorite_report` export only fixed flags, counters and
outcome codes. The cloud keyset and raw server data never leave the app process. A failure
retains the journal for an explicit `cleanup`; never clear app data to dismiss it.

The old synthetic favorite probe intentionally switches fake account scopes. Do not run
it concurrently with this real automatic-sync flow. This helper uses only the real scope,
and only public forum `754` can be the target of a cloud write.
