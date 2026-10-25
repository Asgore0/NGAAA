# Forum request numeric diagnostics

Run `bash tests/forum_network_diagnostics/run.sh`. This compiles the real `NgaApi`
and cancellation logic against the existing JVM-only Context/Keystore boundary
and a checksum-verified test JSON library. Every HTTP connection is replaced by
a scripted in-process connection; no network or real account is accessed.

The checks cover separate per-call timings, all redirect header intervals,
terminal application byte counts, zero diagnostics for locally constructed
pages, unchanged GET/gzip/timeout/close/disconnect behavior, fixed identity across
redirects, fresh identity on a new read, the unchanged non-forum overload, HTTP
and redirect failures, and cancellation before connection and after headers.

All durations are monotonic nanoseconds. Header timing includes DNS/connect/TLS
inside `getResponseCode` without separating them. Request count means application
HTTP hops, not internal retries or socket creations. Body bytes are delivered
bytes after the platform's transparent decompression. The injected waits only
check stage boundaries; this suite is not an Android performance benchmark.
