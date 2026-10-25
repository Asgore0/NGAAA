# Reaction display state

Run `bash scripts/test.sh votes_state`. This JVM suite exercises production `VoteMetrics` and its bounded confirmed-selection state. It covers mixed exact/net-only page data, separate counts, delayed reads crossing acknowledgements, flags without a selection update, per-topic/reply ownership, session/host/token changes, saved-state recreation, malformed state and eviction.

These are deterministic state/parser checks, not an Android UI or real-account write test. The no-INTERNET `reactions_device` helper separately connects real production response callbacks to the production button and uses the official HTML response envelope. It does not infer independent totals from a net delta or claim that a real community vote was submitted.
