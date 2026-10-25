# 通用弹窗按钮的生命周期边界

独立包 `cn.ngapure.glassaction` 不声明 INTERNET，不启动真实客户端，也不读取账号。它使用指定 product jar 内未改写的 NativeGlassDialog，只给本地按钮安装计数器。测试不导航、不调用任何社区功能。

正常模式覆盖 29 项：正、中、负按钮在显示前、禁用、替换 listener、移除 listener、关闭后的 `performClick` 与 `callOnClick`；取消退出动画期间的迟到正/中按钮；已接受的默认正、中、负操作在退场后仍恰好回调一次；Activity finishing/destroyed 后拒绝旧按钮事件。正常自定义验证可以留在原窗并继续编辑，不被强制关闭。退出验证等待真实关闭边界，不用固定动画延时作为通过条件。

构建需显式提供 `PRODUCT_JAR`、`PRODUCT_APK`、各自独立摘要 `PRODUCT_JAR_SHA256`、`PRODUCT_APK_SHA256`，以及外部 `SIGNING_DIR` 和隔离 `BUILD_DIR`。执行 `bash tests/recreation_device/glass_action/build.sh` 只构建 helper，不操作设备。

在专用模拟器运行 `cn.ngapure.glassaction/cn.ngapure.glassaction.GlassActionProbe -e syntheticActionsOnly true`，读取 `safe_glass_action_report`。正常模式须 `completed`、`passed` 为 true。

旧包观察使用 `-e observeBaseline true`：在同一个 UI 帧中触发默认取消、迟到确认的 `performClick` 与 `callOnClick`，只记录计数器。此模式没有 `passed`，不能作为新版本通过证据。

仅本地补丁验证可额外显式设置 `GLASS_OVERLAY_PROVENANCE`。构建会核对 overlay jar 与原 APK 的摘要以及仅 NativeGlassDialog 及内部类改变的证明，APK binding 明确标记 `GLASS_DIALOG_ONLY_OVERLAY`、`productionClassesRewritten=true`。这不是已安装生产 APK 的验证；最终包必须再使用真实候选 product jar/APK 且不设置 overlay 变量复验。
