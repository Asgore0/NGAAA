# NGA 纯净版 · Android 0.13.1

> 历史／来源文档：保留原版本的实现说明和验证边界，不代表当前候选重新验收。原始设备附件未随源码公开；当前状态见[文档索引](README.md)。

原生 Android NGA 社区客户端，最低 Android 8.0 / API26，目标 API35。阅读、搜索、编辑、私信、资料和收藏使用原生界面；仅短信风控需要时加载官方腾讯验证控件。无广告 SDK、开发者统计或自建中转服务器。

本版修复帖子直接显示 `[jumpurl]` 等标签、官方活动链接无法打开的问题，并统一正文、签名与链接路由。versionCode15，包名 `cn.ngapure.app`。**18组离线回归、51项设备检查通过；约48秒原始模拟器录屏已解码并完成抽样目视检查。** 详见 [0.13.1说明](NATIVE-0.13.1.md)。

## 本版变化

- 对照官方BBCode语法补齐常见排版、代码语言、图片变体、列表、表格、相册、附件、媒体和内部引用。`[jumpurl][url]…[/url][/jumpurl]` 正确形成一个活动链接，保留原图及图片查看入口。
- 普通帖子、版块和按UID引用的用户资料继续使用原生页面。官方活动、合集、筛选主题等尚无等价原生语义的链接，保留完整URL并经确认后交给系统浏览器，避免误打开成未筛选列表。
- 浏览器Intent不附加应用Cookie或请求头，通过浏览器类别选择实际浏览器，避免再次命中客户端自己的深链。视频、PDF等附件不再误进图片查看器。
- 用户签名复用正文的安全纯文本解析。未知标签、普通方括号文本和代码原文保持可读；复杂网页布局、加密及动画的支持边界见 [本版说明](NATIVE-0.13.1.md)。

液态玻璃胶囊、版块原图、用户头像补载及折叠窗口适配延续 [0.13.0](NATIVE-0.13.0.md)。版块官方原图仍覆盖原有373个唯一fid中的352个；默认头像不计作用户个人头像。合集链接可交给浏览器，不表示合集原生列表已经接入。

## 安装与验证

下载0.13.1 APK（历史附件未收入源码仓库），6060043字节（约6.06 MB），versionCode15，已覆盖安装并核验设备字节与交付包一致。SHA256为 `6a0edfc6d5d8feed106e424b18aeb749d116a86d2077f8b7b4dba99dc58615f8`。另附 源码ZIP（历史附件未收入源码仓库）、发布验证（历史附件未收入源码仓库） 和 发布元数据（历史附件未收入源码仓库）。

专用API35模拟器完成51项设备检查，绑定50份证据和10张公开截图。真实原帖47190819中的活动链接及图片显示正常；点击、确认后实际拉起系统Chrome并返回原生页。Chrome停在首次设置，未接受或绕过条款，因此活动规则加载、活动登录及提交未计作通过。

原始录屏（历史附件未收入源码仓库）实际解码时长47.9669秒，8个时间线样本已逐个目视检查；原视频未剪辑、变速或插帧。360/840/360 dp与1.3倍字号检查、底栏7种真实指针模式和无崩溃终检通过。历史跟手性能对照和头像覆盖实测仍归属 [0.13.0验证](VALIDATION-0.13.0.md)。

“我的 → 登录管理”支持扫码、密码和手机验证码；扫码需在官方APP确认授权，未自动跳转时点“确认登录”。“设置 → 自动签到”支持后台日任务与打开补签，只有服务器明确确认才记录当天完成。本轮没有改动认证和签到实现；沿用会话的读取验证通过，没有新扫码、密码或短信登录。既有签到时间2026-10-19 00:06:42保持不变，不计作本版新签到。

## 构建与测试

准备 JDK17、Android SDK Platform35、Build Tools35.0.0 和 `zip`：

```sh
export JAVA_HOME="/path/to/jdk17"
export ANDROID_SDK_ROOT="/path/to/android-sdk"
./build-local.sh
./test-renderer.sh
bash tests/api/run.sh
CHECKIN_JSON_JAR=/path/to/verified-org-json.jar bash tests/api/checkin/run.sh
bash tests/avatar/run.sh
bash tests/boardicons/run.sh
bash tests/nativeauth/run.sh
bash tests/credentialauth/run.sh
bash tests/community/run.sh
bash tests/navigation/run.sh
bash tests/loginprefs/run.sh
bash tests/nativecontent/run.sh
bash tests/posting/run.sh
bash tests/motion/run.sh
bash tests/ratings/run.sh
bash tests/imageexport/run.sh
bash tests/reliability/run.sh
bash tests/screenreads/run.sh
bash tests/links/run.sh
```

构建使用 `aapt2 → javac → d8 → zipalign → apksigner`，缓存位于 `.build/`。覆盖升级需要原签名；私钥不随源码交付，也可自行签名后安装。设备探针按 [设备说明](../tests/device/README.md) 单独构建，不进入生产APK。18组离线脚本均退出0，覆盖536项富文本、210项原生内容和18275项链接语义检查；离线样本不代替设备或真实授权验收。

## 资料

- [本版说明](NATIVE-0.13.1.md)、[验证总览](VALIDATION.md)、[设备检查](DEVICE-TEST-CHECKLIST.md)。
- [0.13.0说明](NATIVE-0.13.0.md)、[历史验证](VALIDATION-0.13.0.md)、历史原始录屏（历史附件未收入源码仓库）。
- [官方APK对照](OFFICIAL-9.9.69.md)、[接口说明](API-NOTES.md)、[第三方声明](../THIRD-PARTY-NOTICES.md)。

二维码使用 Nayuki QR Code generator（MIT）。本版内置核验过的官方公开版块PNG及通用默认头像图形，不包含官方APK或反编译实现代码。图片、内容与NGA名称归各自权利人所有，本项目与NGA官方无隶属关系。
