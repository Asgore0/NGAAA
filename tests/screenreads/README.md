# Screen reads and scoped home snapshots

Run `bash tests/screenreads/run.sh` with JDK17. Pure Java8 regression tests, no Android runtime, account or network.

Checks verify queued old pages never start after navigation, running reads receive cancellation, a new page runs, work is bounded, closed activities reject reads, and refresh/session/expiry changes prevent stale cached snapshots. Active socket cancellation is separately exercised against the real NgaApi through `tests/reliability/run.sh`.

User-triggered writes do not use ScreenReadTasks. Device rotation, scroll geometry, media viewport lifecycle and GIF behavior are tested by the dedicated instrumentation probes.
