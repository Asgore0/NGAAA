# Private-message native view boundary probe

This helper packages the explicitly pinned production JAR unchanged in a separate
application UID. Its manifest requests no Internet permission and declares no
shared UID. `PRODUCT_APK`, `PRODUCT_JAR`, both SHA-256 values, `SIGNING_DIR`, and an
absolute isolated `BUILD_DIR` are mandatory build inputs.

The production view's initial disposable read is invalidated, its executor is
stopped, and its private API/executor fields are replaced with a synthetic
read-only transport and controlled queue. The constructor's original read cannot
connect because Android denies Internet access to this helper. Only fixed local
fixture cookies and local private-message draft text exist in the helper UID.
No client account is opened, no server mutation is executed, and no message body
or session value is printed in the report.

Run `CommunityViewProbe` with `syntheticPrivateMessagesOnly=true`. Its strict mode
checks canceled editor entries, recipient lookup completion during the closing
animation, nested recipient confirmation lifetime, canceled read-only views,
single-window private-message pagination, and full-row user touch targets. The
`observeBaseline=true` mode records the two stale-window regressions without
asserting the repaired behavior. Controlled read completion in the same main-loop
turn makes the closing-animation boundary deterministic; it measures neither
network latency nor UI performance.

The result Bundle contains `safe_community_view_report` with boolean assertions,
fixed stage names, read/mutation counts, and fixture isolation flags only.
