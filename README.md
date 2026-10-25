# NGAAA

无广告 SDK 的原生 Android NGA 社区客户端。界面以 Android View、Canvas 和平台媒体组件构建，包含固定玻璃材质、跟手底部胶囊、深色模式与折叠窗口适配。论坛阅读页面不是网页套壳；官方安全验证码仍使用独立受限 WebView。

**当前源码：0.20.13（versionCode 47），已于 2026-10-25 通过本地预设的三轮性能门槛。** 对比 0.20.12，指定模拟器中保留登录态、清空应用缓存的板块首屏中位数分别缩短 8.38%、17.02%、13.02%；21 对启动中 17 对更快。验证范围与限制见 [0.20.13 说明](docs/NATIVE-0.20.13.md)。

安装包见 [Releases](https://github.com/Asgore0/NGAAA/releases)，自动构建见 [Actions](https://github.com/Asgore0/NGAAA/actions)。本轮未重新验收所有线上操作；实测范围与环境限制已记入版本说明。

性能门槛、匿名数值样本和复算方法见 [性能记录](docs/performance/README.md)。

## 功能

- 板块、子版块与合集浏览，收藏/最近访问、搜索、分页和阅读位置恢复。
- 原生帖子排版、代码/引用/折叠/表格，首帖图片预览、可左右切换的图片集、直链音视频。
- 原生二维码、账号密码、手机验证码登录；可选自动签到与未读通知。
- 板块收藏自动同步、主题/回复收藏、关注与屏蔽，资料与私信页面。
- 发帖、回复、引用、附件、草稿，以及明确确认后的点赞/点踩等社区操作。

支持范围受 NGA 服务端权限和协议影响。未知或失败响应不会作为成功；后台任务受 Android 和设备省电调度影响。完整历史功能说明见 [文档索引](docs/README.md)。

## 本地构建

最低 Android 8（API 26），目标 API 35，包名 `cn.ngapure.app`。在 Linux 或 macOS 安装 JDK 17、Android SDK Platform 35 / Build Tools 35.0.0、Python 3、Bash 和 `zip`：

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash build-local.sh --check
bash build-local.sh
```

默认输出 `dist/NGAAA-0.20.13-debug.apk`。SDK 构建不需要 Gradle 或网络下载；签名在仓库外保存。自行生成的密钥不能覆盖安装其他签名版本。自有签名和 Android Studio 构建见 [构建说明](docs/BUILDING.md)。

## 测试与参与

```sh
bash scripts/test.sh --list
bash scripts/test.sh renderer
bash scripts/test.sh api
```

测试入口会准备固定 SHA-256 的测试专用 JSON 库；首次下载需要网络，也可指定已有文件离线执行。Android helper 的构建和设备运行是独立流程，不会被这些命令自动安装。详见 [测试说明](docs/TESTING.md) 和 [参与开发](CONTRIBUTING.md)。

本仓库只保留源码、公开服务素材、合成/脱敏协议夹具和说明；不包含签名密钥、账号、Cookie、授权二维码、真实设备记录、官方 APK 或历史录屏。整理范围与待验证项见 [仓库整理说明](docs/REPOSITORY-PREPARATION.md)。

## 许可与来源

项目自身代码采用 [MIT License](LICENSE)，Nayuki QR 编码器保留其 MIT 许可头。本项目与 NGA 官方无隶属关系；NGA 名称、论坛内容、公开板块图标及默认头像等第三方素材不因本仓库而改用 MIT 许可。来源及边界见 [第三方声明](THIRD-PARTY-NOTICES.md)。
