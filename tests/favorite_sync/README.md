# Favorite synchronization state tests

Run `bash tests/favorite_sync/run.sh` from the Android project. The script uses only the existing checksummed test JSON JAR and local API 35/JDK 17; it downloads nothing.

These tests compile the production `NgaStore` and `ForumFavoriteSyncState`, then use synthetic opaque account scopes and in-memory `SharedPreferences`. They never read login preferences, decrypt credentials, open a socket, or modify a remote account.

Coverage includes:

- Upgrade preservation of old device-only favorites, without implicit upload.
- Persisted add intents and removal tombstones, Store recreation, immutable pending snapshots, and metadata bounds.
- Download reconciliation of previously observed cloud removals, while preserving never-confirmed local favorites and the latest pending intent.
- Exact revision/desired-state compare-and-set acknowledgement, including 400 repeated add/remove/add sequences and stale callbacks.
- Account, host, logout, and later re-login projection isolation; inactive callbacks cannot update local favorites.
- Independent forum and collection identifiers, negative forum identifiers, invalid input rejection, malformed saved state, and durable-write failure handling.

The console reports the exact scenario/assertion totals. This suite verifies local state semantics. Separate device tests and independent remote reads are needed to substantiate a real cloud mutation round trip.
