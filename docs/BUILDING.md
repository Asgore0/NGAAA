# 构建

支持 Linux 和 macOS。安装 JDK 17、Android SDK Platform 35、Build Tools 35.0.0、Python 3、Bash 和 `zip`。构建脚本只使用本机工具，不联网下载 SDK、Gradle 或应用依赖。

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash build-local.sh --check
bash build-local.sh
```

也可设置 `ANDROID_HOME`。未指定时，脚本在常见 Linux/macOS SDK 目录查找；JDK 优先使用 `JAVA_HOME`，否则在 macOS 查询系统 JDK，或解析 PATH 中的 `javac`。仍要求版本 17。

默认产物为 `dist/NGAAA-0.20.13-debug.apk`，中间文件在 `.build/app/`。APK 构建后由 `apksigner` 和 `zipalign` 校验。可用 `OUTPUT_APK` 指定输出路径，`BUILD_TOOLS_VERSION` 指定兼容的本机工具版本。`.build/app/classes.jar` 可作为独立 Android 测试的 `PRODUCT_JAR`；配对的 `PRODUCT_APK` 必须来自同一次构建。

## 签名

默认在 `${XDG_DATA_HOME:-$HOME/.local/share}/NGAAA/signing/` 生成本机开发密钥。可用 `SIGNING_DIR` 指定另一个仓库外目录。密钥文件名为 `ngapure-debug.keystore`，别名 `ngapure-debug`；固定开发密码 `android` 仅用于本机调试。仓库不分发密钥。

已有自有签名可以通过 `SIGNING_KEYSTORE`、`SIGNING_ALIAS`、`NGAAA_STORE_PASSWORD`、`NGAAA_KEY_PASSWORD` 环境变量指定。脚本通过环境变量传递密码，不把密码写入源码或命令行参数；显式指定的密钥不存在时不会自动替换。不要在公开 Issue、构建日志或版本库中填写真实密码。

脚本拒绝将签名密钥放在仓库内部，包括经符号链接指向仓库的路径。自行生成的新签名不能覆盖安装其他签名的客户端；保留原有应用数据的升级必须由原签名持有人构建。

## Android Studio / Gradle

另提供常规 Android Gradle Plugin 8.7.3 配置，配合 Gradle 8.9、JDK 17 使用。仓库没有 Gradle Wrapper 二进制，优先使用上面的 SDK 构建脚本；如使用已安装的 Gradle，可执行 `gradle :app:assembleDebug`。首次解析 Gradle 插件需要网络，Gradle 的调试签名由 Android 工具链在用户目录管理。Release 未内置签名配置。

源码整理阶段的构建验证状态见 [仓库整理说明](REPOSITORY-PREPARATION.md)。环境配置检查不等于 APK 构建、真机兼容性或线上功能验收。
