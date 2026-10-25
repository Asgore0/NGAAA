# Everyday search and share helpers

Run `bash tests/everyday/run.sh`. These are offline tests of the production helpers, with a checksum-pinned host JSON implementation and an in-memory SharedPreferences model. No Android app, clipboard, network, account or real community action is accessed.

`SharedTextLinks` imports only bounded, explicit HTTP(S) links from submitted text. NativeLinkTarget remains the navigation authority. Tests cover title/link shares, punctuation, preserved pages and post IDs, legacy HTTPS upgrades, aliases and duplicates, eight-destination ordering, malicious hosts/credentials, nested foreign URLs, ambiguous routes, unsupported semantics, editor/write/login rejection and random inputs.

`SearchHistory` accepts only explicit submitted keywords, not URLs, credential-shaped strings or control characters. Tests cover twelve-entry recency, scope isolation, reconstruction, deletion/clear, exact schema and type checks, malformed-data retention, immutable results, Android-style commit(false) live-map rollback, failed rollback commits, unrelated later flush and concurrent instances. Credential detection handles recognizable URL, Cookie/token/password field shapes; it does not identify an arbitrary unlabelled password. The caller must invoke recordSubmitted only from keyword-search submission, never login fields or passive input listeners.
