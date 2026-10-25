# Confirmed user-action teardown observation

This observation-only helper packages the unchanged, explicitly pinned production
JAR and runs its real `MainActivity` inside a separate application UID. Its
manifest requests no Internet permission and shares no UID. Constructor/startup
reads cannot connect; automatic check-in and notification polling are disabled.
The real client package is never launched and no real account is accessed.

Native confirmation buttons invoke the production `NativeUserActionDialogs.write`
with fixed local counter work. One case waits in an active worker while the real
Main is destroyed; another occupies the three shared Main I/O workers, queues an
accepted local write, and destroys Main. Counters record interruption, completion,
discarded queued work, retired UI delivery and absence of replay after restart.

Additional checks use an in-memory `NativeUserActions.Transport` to verify one
mutation attempt followed by read-back after an unknown signature result, and
refusal before transport if the exact session changes. Those calls use a fixed
synthetic session only and never access a network.

The final observation creates two synthetic accounts and one local block marker
per account in this helper's private preferences. It confirms the native local
clear action under A, holds its executor, switches to B, and completes the queued
work. This exposes whether the action incorrectly resolves its account only when
the worker starts. The helper's local state is the only state affected.

Build inputs: explicit `PRODUCT_APK`, `PRODUCT_JAR`, both expected SHA-256 values,
`SIGNING_DIR` and absolute isolated `BUILD_DIR`. Run `UserWriteDestroyProbe` with
`syntheticConfirmedWritesOnly=true`. The Bundle key is
`safe_user_write_destroy_report`. It exports booleans, counters and fixed labels;
it does not export credentials, user content, URLs or account identifiers.

This is not proof that every live HTTP write aborts on interruption: Java socket
operations may ignore thread interruption. It demonstrates the teardown's actual
interrupt signal and deterministic loss of accepted queued tasks. Process death
and real server mutation delivery are outside this helper's scope.
