# Forum return data snapshots

`bash tests/forum_return/run.sh` runs the production cache and production API models
with deterministic monotonic time and the existing checksum-verified JSON test JAR.
It downloads nothing and has no account, device, or network dependency. Set
`TEST_WORK_DIR` to use an isolated output directory.

Coverage includes exact screen identity despite equal-value objects, all session
and route parameters, non-renewing two-minute expiry and clock rollback, deep
input/output isolation (including mutable vote counts and child-forum metadata
lists), four-entry eviction, a 200,000-character aggregate budget, bounded item and
metadata-list counts, malformed and oversized payload rejection, active-stack
retention, and repeated replacement accounting. An oversized response is omitted
from the cache as a whole; the displayed network response is not truncated.

The separate own-UID forum-return device helper runs unchanged MainActivity with
in-memory HTTP fixtures to test actual back navigation, refresh/page requests,
same-width scroll restoration, and queued old-account response rejection.
