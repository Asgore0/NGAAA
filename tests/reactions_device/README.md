# Native reaction UI device fixture

The helper copies the immutable final production JAR without rewriting its classes and binds it to the independently supplied APK/JAR SHA256. It hosts `NativeVoteBar`, `NativeThreadActionDialog`, `NativeThreadActions` and `VoteMetrics` in a separate UID with **no INTERNET permission**. The real client Activity, login storage and community service are never accessed. The only transport is an injected synthetic in-memory request/response fixture.

Set `PRODUCT_JAR`, `PRODUCT_APK`, both `PRODUCT_*_SHA256` values, `SIGNING_DIR`, and an absolute isolated `BUILD_DIR`, then run `bash tests/reactions_device/build.sh`. Install only on the authorized dedicated test emulator. Run its instrumentation with:

```
am instrument -w -r -e syntheticReactionsOnly true cn.ngapure.voteprobe/cn.ngapure.app.NativeReactionProbe
```

Read `safe_reaction_report` and require `passed:true`; Android's instrumentation completion code alone is insufficient. Uninstall the helper after the run. There is no real post, vote, report, account or network mutation in this test.

The fixture checks independent known/unknown counts, native button accessibility roles and separate 48 dp touch targets, explicit like/dislike confirmation and cancel, queued duplicate taps, exact official topic-head direction fields, server-confirmed state text, no fabricated count update from a net delta, account changes after confirmation but before worker dispatch, durable uncertain-result guards across reopened opposite-direction sheets, and suppression of callbacks after the UI owner closes. It measures the actual production widget at 200/320/640 dp with font scales 1.0/1.3 in both color modes. This verifies responsive widget layout, not physical fold posture or refresh rate.

The protocol follows the already archived official `commonui.postScoreAdd` / `.after` implementation in `work/research/votes-v010/js_read.utf8.js` (upstream `https://img4.nga.cn/common_res/js_read.js?3587966`). A reaction uses `__lib=topic_recommend`, `__act=add`, `tid`, `pid`, `value=1` or `-1`, `raw=3`; `data[1]` is the net change and cannot supply separate counts; `data[2]` flags 4/8 indicate the confirmed positive/negative selection. Distinct counts come only from the explicit recommendation tuple's support/oppose fields. Scalar recommendation values remain unknown in the visible count controls.

Fixture revision 3 routes actual `Reaction` callbacks into `NativeVoteBar.confirmedReaction`, rather than manually setting selection in the success assertion. Like, zero-flag acknowledgement and removal use the official full HTML envelope. Uncertain-result recovery must perform a local-only guard resolution and open a new confirmation before any second synthetic request. Production state merge/ownership/order rules also have the `votes_state` JVM suite.

For an explicitly labeled recording, add `-e demoMode true`. The fixture displays “本机离线点赞回归 · 合成帖子” and pauses for one second on key screens. This remains synthetic no-INTERNET validation and must not be described as real account voting.
