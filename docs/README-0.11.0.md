# NGA 纯净版 · Android 0.11.0

> 历史／来源文档：保留原版本的实现说明和验证边界，不代表当前候选重新验收。原始设备附件未随源码公开；当前状态见[文档索引](README.md)。

原生 Android NGA 社区客户端，最低 Android 8.0 / API26，目标 API35。阅读、搜索、编辑、私信、资料和收藏使用原生界面；仅短信风控需要时加载官方腾讯验证控件。无广告 SDK、开发者统计或自建中转服务器。

本版优化快速切页、图片和 GIF 加载、文本解析、滚动恢复及缓存隔离；修复图形验证码刷新失败状态，并在本地历史/收藏中保留头像和赞数。详细变化和实测边界见 [0.11.0说明](NATIVE-0.11.0.md)。

## 安装与使用

- NgaPure-0.11.0.apk（历史附件未收入源码仓库），versionCode12，包名 `cn.ngapure.app`，与0.10.0使用相同签名，可覆盖升级。大小与 SHA 以 发布元数据（历史附件未收入源码仓库） 为准。
- 首页顶部进入收藏版块，版块页在收藏/全部之间切换；全部版块中的星标只影响本机收藏。
- “我的 → 登录管理”选择扫码、账号密码或手机验证码。扫码需在官方 APP 确认授权，未自动跳转时点“确认登录”。
- 支持数有明确数据时显示点赞；公开净值显示“净赞”，均缺失时显示“—”。已打开的帖子可按页尝试补全，列表不会逐主题抓取正文。
- “设置 → 自动签到”启用后台日任务与打开补签。只有服务器明确确认才记录当天完成；同日重复触发去重，后台执行时间由系统安排。
- 发帖、回复、上传、私信发送和云端收藏变更需要明确操作。本轮没有执行这些真实写入。

账号密码及短信流程已经实现和离线测试，真实图形验证码与匿名腾讯验证控件已检查加载；没有提交真实密码、发送短信或完成这两种方式的正向登录。本版也没有重新扫码授权。旧版实际扫码及自动签到证据保留原版本归属。新设备检查不代表自然跨日、Doze、铰链硬件、厂商后台或低版本系统已经验收。

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

本地构建使用 `aapt2 → javac → d8 → zipalign → apksigner`，缓存位于 `.build/`。覆盖升级需要原签名；私钥不随源码交付，也可自行签名后安装。离线样本不能代替真实授权、短信或发布验收。

二维码编码使用 Nayuki QR Code generator（MIT），许可见 [第三方声明](../THIRD-PARTY-NOTICES.md)。官方表情、图标与头像按已核实的公开地址加载，不捆绑官方 APK 或实现代码。图片、论坛内容与 NGA 名称归各自权利人所有，本项目与 NGA 官方无隶属关系。

## 验证资料

- [本版说明](NATIVE-0.11.0.md)、设备检查（历史附件未收入源码仓库）、[验证总览](VALIDATION.md)。
- [0.9.1登录与签到](NATIVE-0.9.1.md)、[0.9历史验证](VALIDATION-0.9.0.md)。
- [官方APK对照](OFFICIAL-9.9.69.md)、[头像/点赞字段证据](../tests/avatar/README.md)、[接口说明](API-NOTES.md)。
