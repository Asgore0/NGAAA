# Direct reaction controller

Run `bash scripts/test.sh direct_reactions`. The production controller is exercised with deterministic worker/callback queues. Coverage includes same-floor duplicate/opposite taps, independent routes/accounts, page disposal and resubscription, stale queued notifications, flags without a selection update, unknown results, explicit local-only resolution followed by a separate tap, session retirement while a write is running, executor rejection and bounded state.

No sleep, Android, credentials or network is used. This suite verifies controller ordering rather than the HTTP parser or actual widget layout. `reaction_reader_device` exercises direct taps through the real MainActivity reader and production transport adapter with synthetic replies in a separate UID.
