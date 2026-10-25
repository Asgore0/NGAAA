# Persistent reading progress checks

Run `bash tests/readingprogress/run.sh` from the project root. Tests use the real
`ReadingProgress`, `ReaderAnchor` and navigation bounds, a checksum-pinned cached
test-only JSON library, and Android's `SharedPreferences` interface. No network,
emulator or real account is used.

The in-memory preferences double models Android's mutation of its live map before
`commit()` returns, including repeated disk failures and concurrent store instances.
Checks cover cold reconstruction, account/host isolation, stable save order and the
global 128-entry cap, bounded geometry, malformed preference types and JSON,
oversized/deeply nested input, schema versions, duplicates, exact rollback and
scoped removal/clear. Whole-list `snapshot(scope)` reads preferences once, filters
the selected scope, and returns an unmodifiable map of immutable values; checks
also verify later saves cannot mutate a previously rendered list snapshot.
This is not an Android filesystem or full UI flow test.

`save(scope, tid, page, anchor)` is only for a successfully loaded thread page;
the store cannot decide whether a network load succeeded. The UI integration owns
that success guard. Scope must be a 64-character lowercase SHA-256 identity value;
the UI uses `localDataScope()` (host and account identity), while separately checking
the exact session with `contentScope()`. No cookie, author name, title or post text
is stored.
