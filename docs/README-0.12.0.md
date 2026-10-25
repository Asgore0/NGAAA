# NGA 纯净版 · Android 0.12.0

> 历史／来源文档：保留原版本的实现说明和验证边界，不代表当前候选重新验收。原始设备附件未随源码公开；当前状态见[文档索引](README.md)。

原生 Android NGA 社区客户端，最低 Android 8.0 / API26，目标 API35。阅读、搜索、编辑、私信、资料和收藏使用原生界面；仅短信风控需要时加载官方腾讯验证控件。无广告 SDK、开发者统计或自建中转服务器。

本版集中打磨帖子排版、液态玻璃底栏与窗口适配：楼层改为紧凑卡片，引用和代码更易读，快速拖动切换保留连续运动状态，展开窗口限制阅读宽度并按楼层恢复位置。详见 [0.12.0说明](NATIVE-0.12.0.md)。

## 安装与使用

- NgaPure-0.12.0.apk（历史附件未收入源码仓库），versionCode13，包名 `cn.ngapure.app`，沿用0.11.0签名，可覆盖升级。该安装包已在模拟器安装，正文、快速手势、媒体、阅读恢复及窗口检查通过；约51秒原始录屏已解码并目视审核。完整制品与 SHA 以 发布元数据（历史附件未收入源码仓库） 为准。
- 每层帖子底部只有一行操作：赞数、引用回复、更多。复制和本人编辑入口放在更多菜单；明确正赞显示“点赞”，仅净值显示“净赞”，缺失显示“—”。
- 首页保留单层液态玻璃胶囊；展开窗口时居中显示，不再横向撑满。系统大字体会增加底栏高度，正文与系统栏预留空间相应更新。
- 首页顶部进入收藏版块，版块页在收藏/全部之间切换；全部版块中的星标只影响本机收藏。
- “我的 → 登录管理”选择扫码、账号密码或手机验证码。扫码需在官方 APP 确认授权，未自动跳转时点“确认登录”。
- “设置 → 自动签到”启用后台日任务与打开补签。只有服务器明确确认才记录当天完成；同日重复触发去重，后台执行时间由系统安排。

本轮沿用0.11.0的认证、接口、账号存储和签到实现；没有重新扫码、提交真实密码、发送短信、发布、上传、发送私信或改动云端收藏。历史登录、签到证据保持原版本归属。折叠适配范围为可用窗口尺寸、系统栏边距和重排；当前没有读取物理铰链间隙，不代表所有折叠真机或低版本系统已验收。

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
```

本地构建使用 `aapt2 → javac → d8 → zipalign → apksigner`，缓存位于 `.build/`。覆盖升级需要原签名；私钥不随源码交付，也可自行签名后安装。设备探针按 [设备测试说明](../tests/device/README.md) 单独构建，不进入生产 APK。离线样本不能代替真实授权、短信或发布验收。

二维码编码使用 Nayuki QR Code generator（MIT），许可见 [第三方声明](../THIRD-PARTY-NOTICES.md)。官方表情、图标与头像按已核实的公开地址加载，不捆绑官方 APK 或实现代码。图片、论坛内容与 NGA 名称归各自权利人所有，本项目与 NGA 官方无隶属关系。

## 验证资料

- [本版说明](NATIVE-0.12.0.md)、[验证总览](VALIDATION.md)、[设备检查](DEVICE-TEST-CHECKLIST.md)。
- 模拟器原始录屏（历史附件未收入源码仓库）、源码包（历史附件未收入源码仓库）、发布验证记录（历史附件未收入源码仓库）、设备记录（历史附件未收入源码仓库）。
- [0.11.0验证](VALIDATION-0.11.0.md)、[0.10.0验证](VALIDATION-0.10.0.md)、[0.9.1登录与签到](NATIVE-0.9.1.md)。
- [官方APK对照](OFFICIAL-9.9.69.md)、[头像/点赞字段证据](../tests/avatar/README.md)、[接口说明](API-NOTES.md)。
