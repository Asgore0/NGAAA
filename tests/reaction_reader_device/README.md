# Direct reader reaction integration fixture

Revision 4 runs the **unchanged complete production JAR** in its own UID, without `INTERNET` or a shared UID. It starts the real `MainActivity`, then installs two synthetic reader fixtures through reflection: an original post (`pid=0`) and a reply. It uses only its own synthetic encrypted login preferences and bundled public assets. All HTTP/HTTPS attempts are intercepted in memory; only queued synthetic `NgaApi.postVotes` reads receive responses, and other attempts fail before a socket is opened.

Each case invokes the actual `MainActivity.postRow`, clicks its real vote targets, and receives the real MainActivity controller callback. A package-private fake `NativeThreadActions.Transport` returns server-shaped GB18030 responses, while a `NativeReactionController` with a manual worker makes in-flight input deterministic. Main's existing `listenForReaderReactions()` binds the actual observer; no production class is rewritten or substituted.

The two cases cover:

- One tap to like, another tap to undo, and a further independent tap to like again; switching to dislike and tapping again to undo it.
- The wire request must use `__output=8` and the official direction `value=1` or `-1`, including undo. The undo response deliberately has an empty message and no delta: `{"data":{"0":"","2":1}}` for undo-like, or flags `2` for undo-dislike. Selection comes from flags; independent totals only come from the supplementary read.
- Repeated same-direction and opposite-direction `performClick()` calls while busy cannot enqueue another write, even though this directly exercises listeners beyond normal disabled-touch handling.
- Known failure appears inline, keeps the existing selection, and does not retry. An unknown result disables both choices and shows inline recovery. Clicking recovery only resolves the local guard; it sends no toggle. Only a separate later tap can submit another write.
- No reaction sheet is allocated at any checked stage (`MainActivity.threadSheets` stays null). Screen, generation, scroll container, post card, vote bar and nonzero scroll offset remain unchanged.
- A net-only read begun before the first tap and released after acknowledgement cannot overwrite newer state. A subsequent same-revision net-only response cannot degrade known totals.

The original-post and reply cases each expect eight synthetic action requests and eight synthetic count reads. A third deterministic case completes a real action worker while holding its Main callback, then rebinds the actual post row with the old data revision. The rebound bar must immediately discard stale totals, keep the acknowledged selection, and request exactly one supplemental read through Main’s normal reader subscription. Releasing the delayed callback and subscribing again must neither clear fresh totals nor repeat the read. This case adds one synthetic write and one count read. Two further interleavings hold the Main acknowledgement callback, then directly start a count read whose controller revision already covers that acknowledgement: one delivers the callback before the count read completes, and the other delivers it after fresh counts arrive. Both must retain exact totals and avoid another read. There are nineteen synthetic writes and nineteen count reads overall across five cases. No real reaction, login request, account switch, external app data access or network traffic occurs. This is focused integration coverage, not a live forum test, full Activity lifecycle suite or performance benchmark. Controller/session lifecycle regressions are tested separately.

## Build

Supply `PRODUCT_JAR`, `PRODUCT_APK`, independently recorded `PRODUCT_JAR_SHA256` and `PRODUCT_APK_SHA256`, external `SIGNING_DIR`, and a **fresh absolute** `BUILD_DIR`. The build verifies binding hashes, packages the original production classes, and copies exact public assets from the matching APK.

```sh
bash scripts/with-env.sh bash tests/reaction_reader_device/build.sh
```

The output is `reaction-reader-probe.apk` in that directory. Nothing is installed automatically. The JAR must contain the direct reaction implementation (`NativeReactionController`, the Main observer and inline `NativeVoteBar` states).

## Run on the explicitly authorized dedicated emulator

```sh
am instrument -w -r -e syntheticReaderReactionsOnly true \
  cn.ngapure.reactionreaderprobe/cn.ngapure.app.ReactionReaderProbe
```

Require `passed:true`, `observedFailures:0`, all five cases, `helperRevision:4`, `directTapMode:true`, nineteen synthetic count reads and nineteen total count-read attempts, and every named assertion in `safe_reaction_reader_report`. The same report is written to the helper's private `files/reaction-reader-report.json`, including its runtime production binding. Finish collection and uninstall the helper; never install it over the real client.

For an offline demonstration recording, start recording separately and add `-e demoMode true -e demoHoldMs 700`. Holds are bounded to 300–2000 ms and affect only the fixture, not production logic. The fixed visible heading reads **“离线合成验证 · 不操作真实账号”**, with a stage label, 56 dp top safety padding and a short synthetic post so the marker and actual reaction controls remain visible. A demonstration is not evidence that a real NGA account was used or modified. The helper attempts to finish MainActivity and clear its synthetic login on completion.
