# 独立 UID 的 MainActivity 会话刷新回归

这套 helper 不启动已登录的 `cn.ngapure.app`。它把明确指定的、未改写的 production classes.jar 放在独立包 `cn.ngapure.scopelifecycle` 中，真实启动生产 MainActivity，只使用这个 helper 自己的 SharedPreferences 和 Android Keystore 中的合成凭据。Manifest 不声明 INTERNET，自动签到与通知均关闭，不会发出社区写请求。

覆盖场景：Main 被本地 Activity 覆盖后，合成账号切换、同账号 Cookie 更新、连接域名改变、退出登录，再真正回到 Main；以及 Main 一直可见时的会话变化。检查旧内容、旧阅读锚点都移除且当前 scope 更新。相同 scope、主题/字号/签到等无关键变化不应重建，单次事务多键变化仅重建一次，同一 UI 轮最终回到原 scope 时不显示中间账号。

只构建，不连接设备；显式提供同次生产 jar/APK 和独立记录的 SHA256：

```sh
PRODUCT_JAR=/absolute/path/production.jar \
PRODUCT_APK=/absolute/path/client.apk \
PRODUCT_JAR_SHA256=... PRODUCT_APK_SHA256=... \
SIGNING_DIR=/absolute/private/signing \
BUILD_DIR=/absolute/tmp/scope-probe \
bash tests/recreation_device/scope/build.sh
```

仅在专用模拟器安装 helper 后运行：

```sh
adb shell am instrument -w -e syntheticSessionsOnly true \
  cn.ngapure.scopelifecycle/cn.ngapure.scopelifecycle.ScopeLifecycleProbe
```

输出 `safe_scope_lifecycle_report` 只含布尔值、检查数量和阶段名；不输出合成 Cookie、账号标识、URI 或文本。要求 `passed` 和 `completed` 为 true。测试后卸载 helper，删除它自己的合成数据与独立密钥。
