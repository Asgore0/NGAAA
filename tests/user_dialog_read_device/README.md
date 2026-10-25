# Native user-dialog read cancellation probe

The helper packages an explicitly pinned production JAR under its own application
UID. It requests no Internet permission, shares no UID, starts no real client,
uses no account session, and instantiates no network transport.

Reflection calls the production `read` and `write` orchestration with private
interface proxies, a controlled executor, and fixed local counters. Baseline mode
records whether canceled or owner-retired queued reads still execute. Strict mode
checks that canceled queued reads never execute, a running read observes its
cancellation scope without blocking the UI, successful reads present once, closed
owners cannot start new reads, and confirmed writes keep running after the UI
closes. The write is only an in-memory local counter; no community API is used.

Build with explicit `PRODUCT_APK`, `PRODUCT_JAR`, both SHA-256 inputs,
`SIGNING_DIR`, and an absolute isolated `BUILD_DIR`. Run `DialogReadProbe` with
`syntheticDialogReadsOnly=true`, optionally `observeBaseline=true`. The result
Bundle's `safe_dialog_read_report` contains fixed labels, booleans, counts, and
isolation flags only.
