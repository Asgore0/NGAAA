# Emoji entity regression probes

The renderer now decodes **non-ASCII Unicode scalar numeric entities in ordinary
post text**, directly or inside exactly one server `&amp;` envelope. Decimal entities allow at most seven ASCII digits; hexadecimal
entities allow at most six. Values below U+00A0 (including C0/C1 controls), UTF-16
surrogates, values above U+10FFFF, malformed and overlong entities stay literal.
Emoji, supplementary code points, skin tones, variation selectors and ZWJ
sequences retain their exact Unicode sequence.

This intentionally does not introduce a general HTML decoder. ASCII numeric
entities, BBCode token arguments, and code block numeric entities retain their
previous literal behavior. Existing named entity normalization is unchanged.
The scan accepts only the complete prefixes `&#` and `&amp;#`; output is never
fed back into it. Both `&#128517;` and `&amp;#128517;` become 😅, while deeper
`&amp;amp;#128517;` stays literal `&amp;#128517;` after the existing named-entity
pass. Invalid/ASCII numeric payloads do not receive numeric decoding. Encoded brackets cannot
create BBCode; an encoded non-ASCII space inside a token cannot activate a tag.
Code bodies, including unclosed code tails, retain numeric entity source text.

Run the isolated JVM check:

```sh
TEST_WORK_DIR=/absolute/path/entity-jvm bash tests/entity_device/run-jvm.sh
```

Also run the existing `test-renderer.sh` and `tests/nativecontent/run.sh`.
`EntityCases.java` is shared by the offline and Android probes. It covers the
reported decimal emoji, mixed decimal/hex sequences, malformed and oversized
input, double encoding, literal code, encoded HTML/BBCode, and unsafe protocols.
The exact public floor 16 sentence from official topic 47764699 is included:
the anonymous official response contains `&amp;#128517;`, not a direct entity.
Source evidence URL: <https://bbs.nga.cn/read.php?tid=47764699&page=1&__output=8>;
captured response SHA-256: `be69dcc0d81c59b44e18211b5dc647f653fb4c37513f728153513646a3bd0c2d`.
Only the short public sentence is a fixture; no account/session metadata is
copied into the test. Escaped hexadecimal, VS/ZWJ, deeper envelopes and unsafe
ASCII syntax receive separate assertions.
The offline test additionally checks resuming conversion after code, nested
ordinary text, and a 20,000-bracket malformed input. Runtime is reported only as
an observation.

The Android helper loads the full immutable production JAR without rewriting
or shadowing product classes. It has a separate UID, no `INTERNET` permission,
and never starts or reads the real client. It creates real `NativeRichText`
views, waits for Android layout, and checks exact TextView code points in light
and dark themes. Every fixture must have zero URLSpan, ImageSpan, StyleSpan,
button, input, image-view or WebView activation. Code labels are excluded from
body-text comparison. The final light/dark screenshots contain synthetic text
only and are written to the helper's own files directory.
The Chinese/NBSP fixture places decoded whitespace inside a sentence: ordinary
native flow already trims leading/trailing spaces and NBSP through
`FlowTextWhitespace`, so a trailing NBSP is not a valid preservation assertion.
On mismatch the device report includes both actual and expected Unicode code
points before raising its assertion.

```sh
PRODUCT_JAR=/absolute/path/production.jar \
PRODUCT_APK=/absolute/path/production.apk \
PRODUCT_JAR_SHA256=independently_recorded_jar_sha256 \
PRODUCT_APK_SHA256=independently_recorded_apk_sha256 \
SIGNING_DIR=/absolute/path/private-signing-directory \
BUILD_DIR=/absolute/path/fresh-isolated-helper \
bash tests/entity_device/build.sh
```

The fresh output includes `entity-probe.apk` and a binding with hashes of the
product APK, JAR, every production class, and all helper sources. The external
signing key is never copied into the helper or report.

On the dedicated emulator, run each binding in a fresh process:

```sh
adb install -r /absolute/path/entity-probe.apk
adb shell am instrument -w -e expectFixed true \
  cn.ngapure.entityprobe/cn.ngapure.entityprobe.EntityProbe
adb exec-out run-as cn.ngapure.entityprobe cat files/entities-light.png > entities-light.png
adb exec-out run-as cn.ngapure.entityprobe cat files/entities-dark.png > entities-dark.png
adb uninstall cn.ngapure.entityprobe
```

Use `-e expectFixed false` with the 0.20.8 baseline binding. A passing baseline
run means the literal-entity defect was reproduced; it is not a fixed result.
The result key is `safe_entity_report`. No device command is run by the build
scripts.
