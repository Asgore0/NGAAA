# Reader reaction integration fixture

This helper runs the **unchanged complete production JAR** in its own UID, without `INTERNET` or a shared UID. It starts the real `MainActivity`, then installs two synthetic reader fixtures through reflection: an original post (`pid=0`) and a reply. It uses only its own newly created synthetic encrypted login preferences and bundled public assets; it cannot read the installed client's preferences or Keystore entries. All HTTP/HTTPS attempts are intercepted in memory; only queued synthetic `postVotes` reads receive responses, and all other attempts fail before a socket is opened.

Each case invokes the real `MainActivity.postRow`, clicks its real like target, opens the actual `NativeThreadActionDialog`, and explicitly presses its confirmation button. A package-private fake `NativeThreadActions.Transport` returns a server-shaped confirmation on a controlled worker. The resulting **actual MainActivity callback** must keep the same screen, generation, scroll container, post card and vote bar; retain the nonzero scroll offset; show the successful dialog; and keep the server-confirmed like selection while refreshing independent totals.

Three real `NgaApi.postVotes` reads per case cover a read started before confirmation and released late, the callback's fresh exact-count read, and a subsequent same-revision legacy net-only response. Assertions check the stale revision cannot overwrite the new state and the legacy response cannot degrade known like/dislike counts. Exactly one synthetic action write is permitted per case. No real reaction, login request, account switch, read-page navigation, external app data access or network traffic occurs. This is focused integration coverage, not a full end-to-end live forum test, accessibility audit, performance benchmark or all-account-lifecycle suite.

## Build

Supply `PRODUCT_JAR`, `PRODUCT_APK`, independently recorded `PRODUCT_JAR_SHA256` and `PRODUCT_APK_SHA256`, external `SIGNING_DIR`, and a **fresh absolute** `BUILD_DIR`. The build verifies binding hashes, packages all original production classes without rewriting them, and copies exact public assets from the matching APK.

```sh
bash scripts/with-env.sh bash tests/reaction_reader_device/build.sh
```

The output is `reaction-reader-probe.apk` in that build directory. No helper is installed by this command. The production JAR must contain the reader reaction integration fix (`ReactionListener`, `readerThreadPage`, `readerVoteViews` and revision checks).

## Run on the explicitly authorized dedicated emulator

```sh
am instrument -w -r -e syntheticReaderReactionsOnly true \
  cn.ngapure.reactionreaderprobe/cn.ngapure.app.ReactionReaderProbe
```

Require `passed:true`, `observedFailures:0`, both cases, six synthetic count reads, and every named assertion in `safe_reaction_reader_report`. The report also appears in the helper's private `files/reaction-reader-report.json` and includes the runtime production binding. The helper attempts to clear its synthetic login and finish MainActivity on completion. Uninstall the helper after collecting the report; never install it over the real client.
