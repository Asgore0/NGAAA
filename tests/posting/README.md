# Native posting fixtures

Run `bash tests/posting/run.sh`. Protocol fixtures use a queue-backed offline transport; cancellation checks additionally exercise the production HTTP transport with in-memory URL connections. No socket, real account, post, reply, or upload is used. The test-only NgaStore double must never be packaged in the app.

`prepare-anonymous-gb18030.json` is the real anonymous GET response from `https://bbs.nga.cn/post.php?action=new&fid=7&__output=8`, fetched without cookies on 2026-10-18. Other fixtures are synthetic protocol examples derived from the official page and its current scripts; their auth/checksum strings are deliberately fake. They are not evidence of a successful live authenticated submit/upload.

Primary sources (protocol/behavior only, no third-party code copied):

- Official public page: `https://bbs.nga.cn/read.php?tid=47717100` (declares upload `img8.nga.cn`, viewing `img.nga.cn`, current script versions).
- Official posting UI: `https://img4.nga.cn/common_res/js_postfunc_v2.js?3046646` (`postGet`, `genFormFromApiData`, `newPost`, `attachUpload`, error code table, tab-separated paired credentials, numeric zero message code and message[6] read.php target).
- Official editor escaping: `https://img4.nga.cn/common_res/js_bbscode_core.js?1342409` (`unSecureText`'s limited entity subset).
- Existing open-source client protocol cross-check: `https://github.com/mlzzen/open-nga` (`TopicPostModel`, `PostParam`, `TopicPostTask`); current official scripts take precedence.

Coverage includes permission and ownership checks, server quote preservation, append-only edit, original attachment references, GB18030 form and UTF-8 filename encoding, strict success recognition, fixed HTTPS authorities, write redirects/timeouts and no blind retry, multipart original-file bytes, preflight/file signature limits, paired attachment scope/checksums, explicit CAPTCHA/errors, and session changes.

NativeDrafts tests additionally check exact quoted-text/attachment-position recovery, public file metadata without upload credentials, action/target key isolation, durable unconfirmed state before publishing, process recreation, explicit outcome review, commit failure, version-safe rejection/deletion after a late result, visible fail-closed malformed entries, exact raw-version deletion, long quota accounting, twenty protected pending records refusing a twenty-first snapshot, protected text overflow refusal without mutation, ordinary retention that cannot evict pending markers, and large escaped text roundtrips. Unpublished attachment references remain in the draft; the native UI requires reselecting a file or explicitly removing its reference before publishing. Legacy seven-field drafts remain readable. NativeEditorView is compiled against the real API 35 android.jar with Java 8 and strict warnings; UI/instrumented verification is performed by the parent task.

`PostingCancellationTest` checks the production adapter and HTTP transport against interrupt-insensitive synthetic connections. A canceled preparation never opens or completes a new permission request; page cancellation actively releases a blocked read. Explicitly confirmed submissions/uploads keep their response settlement after the disposable page token is canceled. A lost delivery response remains UNKNOWN and cannot reuse the same authorization. These checks never reach a real network or account.

Native limitations remain explicit: no challenge-answer flow, no poll/score configuration editing, image/archive types only, no silent compression. Server rejection is shown verbatim as plain text. Positive live prepare can be tested read-only by the parent task; actual publishing/uploading needs an explicit user action in the native editor.
