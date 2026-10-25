# Isolated native editor lifecycle verification

This helper includes the exact reviewed production class jar alongside only the fixture activity and instrumentation classes. It has its own Android UID, no INTERNET permission and synthetic local preferences/keystore data. A URL handler turns every requested HTTPS connection into an in-memory object; any unscripted endpoint fails before a socket can open.

It verifies the actual NativeEditorView and NativePosting transport: closing a pending permission read disconnects it without blocking the main thread; a synthetic explicitly confirmed submission still settles after the editor closes; confirmed success clears its own durable draft, while a lost response retains the unconfirmed marker. It cannot read the installed client's credentials or send a real post/upload.

The build script requires explicit immutable inputs and reviewed SHA256 values. It never implicitly selects the mutable project `.build/classes.jar`, downloads dependencies, starts a device or accesses ADB. The debug signing key stays outside this source directory.

```sh
PRODUCT_JAR=/absolute/pinned/classes.jar \
PRODUCT_JAR_SHA256=<reviewed-jar-sha256> \
PRODUCT_APK=/absolute/candidate.apk \
PRODUCT_APK_SHA256=<reviewed-apk-sha256> \
SIGNING_DIR=/absolute/external-signing-directory \
EDITOR_LIFECYCLE_BUILD_DIR=/absolute/isolated-helper-output \
bash tests/editor_lifecycle_device/build.sh
```

API 35, build-tools 35.0.0 and JDK 17 are required locally. The output contains the helper APK, permission dump and `product-class-binding.json`, which records every input production class hash and both APK hashes. The helper jar is rejected if it contains copied product classes; all production types go directly into D8 from the explicitly pinned input jar.

The root task installs/runs the helper only on its designated emulator. Instrumentation component: `cn.ngapure.editorfixturev195/cn.ngapure.editorfixturev195.EditorLifecycleProbe`; explicit argument: `offlineEditorLifecycle=true`; structured result key: `safe_editor_lifecycle_report`. Uninstall the helper afterward. No helper classes, assets, fixture account data or debug signing key belong in the product APK.
