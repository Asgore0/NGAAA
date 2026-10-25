# Confirmed user-write lifecycle probe

This strict helper packages the exact pinned production JAR under its own UID
without Internet permission. It runs production `MainActivity`, its real native
confirmation and confirmed-write queue, using fixed local counters. It never
starts the real client package or sends a community mutation.

The checks cover an active confirmed write surviving actual Main destruction,
two accepted queued writes surviving destruction, rejection of a third waiting
write, a visible “nothing was sent” message and restored cancel/buttons after
rejection, no retry or replay after Activity restart, and no results entering a
retired or changed-account UI. Another case confirms a local block-list clear
under synthetic A, switches to B before execution, and verifies that neither
account is modified. Only helper-private synthetic preferences are used.

Build with explicit `PRODUCT_APK`, `PRODUCT_JAR`, their SHA-256 values,
`SIGNING_DIR`, and an absolute isolated `BUILD_DIR`. Run with
`syntheticConfirmedWritesOnly=true`; the result key is
`safe_user_write_lifecycle_report`.

The queue survives window destruction only. It deliberately has no disk journal,
credential persistence, process-death recovery, or automatic network retry. The
probe makes no claim about a real server write or physical-device performance.

The same APK also declares `LoginMethodLifecycleProbe`, run with
`syntheticLoginMethodsOnly=true`; its result key is
`safe_login_method_lifecycle_report`. It drives actual Main recreation with SMS
and password selected. It checks empty restored fields/agreements, sensitive
fixture values absent from the framework's saved Parcel, one-shot restoration,
and rejection of a wrong scope, wrong types, invalid integers and non-auth routes.
An in-memory URL handler refuses every network connection and only counts the
public QR-generation action, so restored credential forms must produce no QR
request. No login, SMS send, CAPTCHA or real account flow is submitted.

That runner requires API 29+ because it uses post-create/post-save lifecycle
callbacks to inspect and mutate the complete state after Main's overrides have
finished, before Android delivers it to the recreated Activity. Ordinary callbacks
at the `super` call would observe incomplete application state. See the
[Android callback contract](https://developer.android.com/reference/android/app/Application.ActivityLifecycleCallbacks#onActivityPostSaveInstanceState(android.app.Activity,%20android.os.Bundle)).
