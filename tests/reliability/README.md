# Transport lifecycle and local topic snapshots

Run `bash tests/reliability/run.sh` from the project directory. The runner uses the
checksum-verified test-only JSON JAR and Android API 35. For an extracted source
archive, set `TEST_JSON_JAR=/absolute/path/json-20240303.jar` (or `CHECKIN_JSON_JAR`)
and `ANDROID_SDK_ROOT` if needed. Otherwise the runner reuses
`.build/api-parser-tests/json-20240303.jar` or the delivery workspace research cache.
It verifies SHA-256 `3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed`;
no dependency is downloaded or included in the APK.

`TransportLifecycleTest` uses the real `NgaApi` and `LoginPreferences`, the same
synthetic Keystore boundary as the check-in account-race tests, and a URL handler
that rejects every unscripted connection. It covers an account switch across a
redirect, login beginning during an anonymous request, cancellation before a
request and reply locator, cancellation after headers and while reading a body,
no HTML fallback after cancellation, failed POST output cleanup, unsafe redirect
rejection, and the existing 8 MiB response limit. All cancellation assertions
check that the thread's interrupt flag is preserved and opened resources close.
`RequestCancellationTest` binds opt-in per-page cancellation scopes around real
`NgaApi` reads. It verifies that three fake sockets which ignore thread interruption
are actively disconnected and release all three screen-read workers, allowing the
latest queued page to run within two seconds. Nested scope cleanup, registration
release and pre-cancelled requests are checked. Disconnect runs on an independent
pool of at most three daemon threads and twelve queued tasks, never on the UI or
calling thread. The saturation test verifies that the helper and token return false
rather than silently executing blocking disconnect on their caller. In that rare
fallback, or if a platform does not promptly unblock a disconnected socket, normal
worker cleanup and the existing 20-second read timeout remain the backstop.
Unscoped writes and background jobs are unaffected by another page's token.

`LocalTopicStoreTest` uses the real `NgaStore` with in-memory `SharedPreferences`.
It round-trips topic metadata, normalized public avatar URLs, exact support/oppose
counts and net values with their known flags. Old records without new fields,
known zero, net-only values, invalid avatar origins, malformed counts/flags,
null metrics, deduplication and saved-topic removal are covered. These are local
snapshots, not a claim that cached vote counts remain live. No test requests
Keystore access, logs credentials, sends a message, or uses a real account.

All three test groups compile with Java 8 API compatibility, all lint warnings,
and `-Werror`. The current baseline is 37 transport assertions across 11 scenarios,
43 active-cancellation assertions across 4 scenarios, and 53 store assertions.
