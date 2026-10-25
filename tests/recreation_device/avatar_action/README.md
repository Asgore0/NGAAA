# 本地头像确认的取消边界回归

独立包 `cn.ngapure.avataraction` 不声明 INTERNET、不启动真实客户端、不读取真实账号。它加载指定的未改写 production.jar，只使用自己生成的 240×160 PNG 和可变合成会话。所有 NativeUserActions 请求都由必定抛错的 Transport 接管，不能到达头像图片服务。

默认覆盖：取消退出的 150ms 内迟到确认不能入上传队列；已关闭弹窗、已关闭 owner、失效会话不能提交；合法同会话的合成确认只排一次任务，不能恢复为待上传草稿，双击不能重复排队。仅合法的合成确认任务会执行到阻断 Transport，输出检查数量、阶段和布尔，不导出输入、图片或凭据。

`-e observeBaseline true` 是旧包观察模式，仅生成本地预览，在同一 UI 帧依次触发取消和迟到确认，记录是否错误排队。排入的上传任务不会执行。该模式没有 `passed`，不能作为修复通过证据。

构建需设置 `PRODUCT_JAR`、`PRODUCT_APK`、独立记录的 `PRODUCT_JAR_SHA256`、`PRODUCT_APK_SHA256`、私有 `SIGNING_DIR` 和可选 `BUILD_DIR`，然后执行 `bash tests/recreation_device/avatar_action/build.sh`。脚本只生成 helper，不操作模拟器。

在专用模拟器运行 `cn.ngapure.avataraction/cn.ngapure.avataraction.AvatarActionProbe`，显式传入 `-e syntheticAvatarOnly true`；输出 `safe_avatar_action_report`。正常模式要求 `completed`、`passed` 为 true，且 `imageServiceRequestExecuted` 为 false。测试完成后卸载整个 helper 包及其合成数据。
