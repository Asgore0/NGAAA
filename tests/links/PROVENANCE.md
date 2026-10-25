# Link routing examples

These tests make no network request and do not load an account or Android runtime.

The public official activity URL reported in the screenshot was opened read-only on 2026-10-19:

- https://bbs.nga.cn/misc/event/20261015zmd/index.html

It is an HTML activity/rules page, not a native topic endpoint. The router preserves the complete URL as `BROWSER`.

Other concrete menu URL shapes are from the project's existing public official script snapshots:

- https://img4.nga.cn/common_res/js_read.js?3587966 (`work/native-posting/official-read.raw`): `read.php?pid={pid}&to`, `nuke.php?func=ucp&uid={pAid}`, `read.php?tid={tid}&authorid={pAid}`, `thread.php?searchpost=1&authorid={pAid}&fid={fid}`, and native editor `post.php?action=reply|quote|modify` links.
- https://img4.nga.cn/common_res/js_commonui.js?5871374 (`work/native-posting/official-commonui.raw`): `thread.php?favor=...`, `thread.php?stid=...`, `__lib=ucp&__act=get`, `__lib=message&__act=message&act=list|read|new|reply|del_topic`, `__lib=noti&__act=get_all`, `__lib=topic_recommend&__act=add`, and `__lib=forum_favor2&__act=forum_favor&action=add|del`.

Native route recognition requires exact paths and implemented query semantics. Unsupported filters, collection routes, and pid-only redirects preserve browser semantics. Dynamic write forms are classified `BLOCKED`; merely parsing an editor route never submits it. No proprietary app URI or guessed jump wrapper is decoded.

Legacy GBK search values, such as `thread.php?fid=7&key=%D6%D0%CE%C4`, and opaque display-path bytes remain byte-for-byte in the browser URL. They never become replacement-character native routes. Parameter names and official routing identifiers/actions remain strict ASCII; malformed percent escapes and raw or escaped ASCII controls are rejected even when they follow a legacy byte.

The browser bridge must not pass application Cookie/header/session state and must exclude this app from browser resolution. The parser itself does not open links, access a session, resolve redirects, or read network data.
