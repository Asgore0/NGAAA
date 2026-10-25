# 原生搜索和跳页输入的生命周期回归

独立包 `cn.ngapure.promptlifecycle` 内运行明确指定的、未改写的 production.jar。它不声明 INTERNET，不启动真实已登录客户端；只使用自己的合成账号和输入。测试没有发帖、上传、点赞、收藏或实际搜索请求。

覆盖真实 MainActivity 的未提交搜索文字和光标恢复、取消离场动画内立即重建、长关键词和内部控制字符的本地验证、直接搜索入口验证、已关闭或正在关闭弹窗的迟到按钮/IME 事件、会话变更同一 UI 帧的旧输入提交拒绝、同会话有效跳页、版块跳页输入/光标恢复且不自动翻页。线程场景将新页数作为本地合成元数据交付给生产恢复方法：原输入在网络等待、同页重试和再次重建期间保留，新总页数 10 必须覆盖之前的 50，旧目标 21 只能继续编辑而不能提交。此场景验证本地状态和 UI 绑定，不表示测试了真实 NGA 服务端的页数变化。

构建脚本只生成 helper，不操作模拟器。必须指定同次生产 jar/APK 和独立记录的摘要：

```sh
PRODUCT_JAR=/absolute/path/production.jar \
PRODUCT_APK=/absolute/path/client.apk \
PRODUCT_JAR_SHA256=... PRODUCT_APK_SHA256=... \
SIGNING_DIR=/absolute/private/signing \
BUILD_DIR=/absolute/tmp/prompt-probe \
bash tests/recreation_device/prompts/build.sh
```

仅在专用测试模拟器安装后运行：

```sh
adb shell am instrument -w -e syntheticInputOnly true \
  cn.ngapure.promptlifecycle/cn.ngapure.promptlifecycle.PromptLifecycleProbe
```

读取 `safe_prompt_lifecycle_report`，要求 `passed` 与 `completed` 都为 true。只输出布尔、阶段和检查数量，不输出输入文本、账号或 Cookie。结束后卸载 helper，其合成数据与独立密钥一起删除。

`-e observeGuardsBaseline true` 仅供绑定已固定旧版本观察：不运行正常断言，也不因旧版本控制字符/已关闭按钮/同帧会话切换导致的旧导航而提前中止。四个旧行为布尔和 `expectedGuardRegressionsObserved` 必须一同判读；此模式不提供 `passed`，不能当作新版本通过记录。

当前回归另覆盖线程补齐 fid 元数据不会改变同页输入身份、真正换页仍拒绝旧输入，以及子版块展开的当前页/返回栈 Bundle 恢复与布尔类型、错误版块、超界栈校验。展开生命周期使用合成 Screen 元数据；实际子版块卡片展开和返回另由主客户端 UI 流程检查。`-e observeMetadataBaseline true` 只观察旧版同 thread/page 补 fid 导致 pageKey 漂移，旧包尚无新提交围栏，因此不声称旧包已拒绝这次搜索。
