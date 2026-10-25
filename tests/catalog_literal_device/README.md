# Bundled catalog literal-path Android probe

This fixture compares an immutable full production JAR and its byte-exact APK
public assets. It runs as a separate UID, has no INTERNET/shared UID, does not
start the real client, and denies every HTTP handler. It never supplies a real
account or edits production fields/classes. Build scripts never run ADB.
The manifest queries only `cn.ngapure.app` so Android package visibility permits
the UID-separation check; it adds no storage, network or account permission.

For each product version, install a freshly built helper and instrument with
`bundledCatalogOnly=true` and `expectOptimized=false` (baseline) or `true`
(candidate). Runner is `cn.ngapure.catalogliteralprobe/cn.ngapure.app.CatalogLiteralProbe`;
read `safe_catalog_literal_report`. Root owns device operation.

Timing consists of one first `NgaApi.forums()` invocation in a fresh helper
process followed by seven repeated invocations. They are separate categories,
not eight cold starts. Each interval surrounds only the actual API call on
Android's main thread and records monotonic wall / main CPU durations. JSON
canonicalization, output comparison, text oracle calls, and parallel-read checks
are outside the measured intervals. The real application may defer this work to
a worker; startup and first HTTP timing are measured separately by root.

Every public Forum field is included, with stable row and nested-list order, in
`files/public-catalog-output.json`. Compare its complete bytes and SHA-256 across
baseline/candidate on the same Android runtime, never against a JVM Html stub.
The 658 destinations, parent routes, aliases, categories, groups, descriptions,
icons and search metadata must all remain identical.

The text suite invokes the production catalog sanitizer against the prior
algorithm implemented with actual platform `Html.fromHtml`, at six UTF-16 limits.
It includes ordinary multilingual labels, punctuation, ASCII/C1 controls,
whitespace forms, format characters, malformed markup, entities, emoji, isolated
surrogates and truncation boundaries. `files/adversarial-text-output.json` stores
exact UTF-16 code units to retain malformed-surrogate distinctions. Candidate
checks also confirm conservative fallback selection. The JVM API suite only
counts avoided parser calls; it is not evidence of Android text semantics.

A concurrent worker/main read using the same production NgaApi instance must
return identical full catalog bytes. No process-global result cache is used.

Build with absolute PRODUCT_JAR, PRODUCT_APK, their SHA256 variables, SIGNING_DIR,
and a fresh absolute BUILD_DIR. `bash build.sh` uses installed JDK17/API35 tools,
checks all class/asset bindings and package permissions, and produces
`catalog-parse-probe.apk`. No dependencies are downloaded.
