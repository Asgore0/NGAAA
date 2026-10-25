# UI session ownership cache

Run `bash tests/session_scopes/run.sh`. This executes the actual pure Java cache and digest code with synthetic source values, without Android, network or credentials.

The checks cover the legacy digest contract, repeated warm reads, token/account/host changes, A→B→A, anonymous state, explicit lifecycle invalidation, failed-decryption recovery, source changes during resolution, exceptional resolution, the authoritative rollback-source contract, and the encrypted-source size cap. These host checks do not simulate Android Keystore or claim to measure its timing; the isolated own-UID device probe covers the real store integration.

The 0.20.12 cases additionally exercise priming from a fresh credential read: a matching authoritative host/encrypted value avoids a second ownership resolution, while token/account/host changes, failed reads, rollback overlays, oversized sources and lifecycle invalidation retain the same isolation and retry rules. Only encrypted source bytes and derived hashes enter this cache; network credentials are still captured afresh by `NgaStore.captureCheckInSession()`.
