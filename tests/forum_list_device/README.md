# Production forum list Android fixture

Build only after freezing the production APK and complete classes JAR. `build.sh`
requires explicit `PRODUCT_APK`, `PRODUCT_JAR`, both corresponding SHA256 values,
`SIGNING_DIR`, and a fresh absolute `BUILD_DIR`. It copies the full production JAR,
public assets, compiled resources and resource table byte for byte. It rejects
helper classes shadowing any product class. The helper uses its own package/UID,
no shared UID and no INTERNET permission, and rejects HTTP at the Java URL layer.
It starts production MainActivity only under the helper UID with synthetic data.

Instrumentation:

```
am instrument -w -e syntheticForumListOnly true cn.ngapure.forumlistprobe/cn.ngapure.forumlistprobe.ForumListProbe
```

The private files output is `forum-list-report.json`. It embeds the build binding.
Root automation installs/runs/extracts/removes the fixture; this script does not.

The fixture invokes the exact production forum renderer with full 20/100/200 row
pages and compares every intersecting TextView and its Android bounds with the
unchanged production eager `topicRow` reference at 220/390/720 dp and 1x/2x fonts.
A forwarding adapter counts views returned by the real production adapter;
viewport completeness, full logical item counts, header expansion/collapse, distant recycling and
return-to-top are checked. Recycling requires an observed non-null convertView
returned as the identical view and rebound to a different topic; no guessed
row-count threshold is used for pages with a large header. Timing is synthetic detached-layout diagnostic only,
not a replacement for unchanged real-account cold-start firstDraw measurements.

The fixture also exercises topic-ID/top-offset restoration after changed data and
width, scoped Bundle state, changed-host rejection, preview disposal, repeated
requestLayout retaining the same preview and pixels, late metadata height
changes, actual window pointer injection after attachment/focus/layout opening the gallery
(the point is inside the intersection of the image’s visible screen pixels,
fixture root, own window and visible display frame; all bounds are recorded), gallery back restoring
the original list position, and page closure releasing all registered previews.
The bitmap is synthetic; actual network image decode, real accounts, dark mode,
physical fold posture and persistent process recreation are separate real-device
regressions. Pure state serialization is covered by tests/forum_list.
