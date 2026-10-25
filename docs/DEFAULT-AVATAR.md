# Default user avatar

> 历史／来源文档：保留原版本的实现说明和验证边界，不代表当前候选重新验收。原始设备附件未随源码公开；当前状态见[文档索引](README.md)。

The generic grey NGA helmet comes from the official public post renderer's `commonui.posterInfo.avatarBlank` function in [js_read.js](https://img4.nga.cn/app_res/sync/common_res/js_read.js?3587966). The renderer uses this inline SVG for an empty avatar in its compact layout (`lite & 1`); the full layout may leave the avatar absent. It is a shared default, not a personal user image.

The 130 × 130 SVG was converted to `app/src/main/res/drawable/avatar_default.xml`. Its path data, fills, path order and the two `<use>` vertical offsets (4 and 2) are preserved as native VectorDrawable paths/groups. The client draws it over a pale grey circle when a user avatar is unavailable. It does not run SVG or JavaScript at runtime.

Real downloaded user bitmaps remain separate from the default drawable. Default avatars are announced as “默认头像” and do not count toward personal-avatar coverage. Unknown board icons keep their independent initial fallback.

| Evidence | SHA-256 |
| --- | --- |
| Official `js_read.js?3587966` | `e245e742ca16dddc3538db16c5e50bec3c00a3cc21f9be21119a0e4d6d63a923` |
| Extracted original SVG | `ea5b8cf099e76081017cc6b672b0101639e139383484c2abc7491f89cd0f6cf0` |
