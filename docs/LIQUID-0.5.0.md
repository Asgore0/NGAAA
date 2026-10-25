# 0.5.0 单层导航、流动融合与实测

> 历史／来源文档：保留原版本的实现说明和验证边界，不代表当前候选重新验收。原始设备附件未随源码公开；当前状态见[文档索引](README.md)。

> 历史记录：本文保留 0.5.0 的实现、48 项设备结果和当时录屏。0.6.0 已重做连续时钟、接管、轮廓与光学更新，请看 [0.6.0 专项](LIQUID-0.6.0.md) 和 [当前验证总览](VALIDATION.md)；本文通过不自动作为新版证据。

日期：2026-10-18，Asia/Shanghai。最终 APK `0.5.0 / code 5` 已在专用 API 35 模拟器安装，48 项设备检查全部通过。常驻第二层分组栏已删除，改为单一 64 dp 主导航；选中材质使用头尾差速、连续轮廓和同步局部折射，随持续触点拉伸及回缩。

这是原生 Android 对 Apple Liquid Glass 风格的近似实现，未移植 AppKit、UIKit 或 SwiftUI，也不承诺与 Apple 私有材质逐像素一致。

## 设计依据与当前实现

1. **触摸过程中就反馈。** Apple 的手势指南强调即时反馈，让人能在操作中预测结果。本版在 DOWN/MOVE 期间改变轮廓，保持触点时仍可观察形变。[Apple HIG：Gestures](https://developer.apple.com/design/human-interface-guidelines/gestures)。
2. **融合具有连续轮廓。** Apple 的 `GlassEffectContainer` 以几何与距离组织玻璃形状的融合。本版采用头尾不同响应和连体外轮廓，避免将两个半透明圆角块简单叠放。[GlassEffectContainer](https://developer.apple.com/documentation/swiftui/glasseffectcontainer)、[Applying Liquid Glass to custom views](https://developer.apple.com/documentation/swiftui/applying-liquid-glass-to-custom-views)。
3. **新操作能够接管。** Apple 的动态效果指南重视目的明确、简短且可以中断的反馈。本版处理反向、取消及活动指切换，新输入不等待旧弹簧播放完，提交后只保留一个选中项。[Apple HIG：Motion](https://developer.apple.com/design/human-interface-guidelines/motion)。
4. **减少动态效果与透明度仍可操作。** Apple 的 Liquid Glass 采用指南要求考虑相关辅助设置。本版保留实色模式；应用关闭动画或系统动画为零时移除惯性回缩，直接到终态，保留拖动与菜单功能。[Adopting Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass)。

头尾弹簧、连接曲线、折射、透明度与提交规则均为本项目的 Android 实现选择，不是 Apple 公开的物理规格。

| 区域 | 0.5.0 行为 |
| --- | --- |
| 常驻导航 | 单一 64 dp“首页 / 版块 / 评分 / 我的”，删除旧 48 dp 分组栏 |
| 首页分组 | 再点已选首页展开临时“关注 / 推荐 / 热帖 / 活动”；选择、返回或点外部后收起，保留分组阅读位置 |
| 拖动材质 | 可从未选中项起拖；头部跟随触点，尾部差速追随，轮廓连续拉伸、压扁、回收 |
| 光学与采样 | 局部折射随几何更新，持触窗口复用底图；正文、图标和文字不作为玻璃模糊层 |
| 键盘与空间 | 内容为唯一底栏留出空间，末行可完整露出；键盘显示时隐藏底栏，并停止相关帧与底图更新 |

API 31 起使用 RenderEffect 模糊，API 33 起叠加 AGSL 光学效果，更低版本提供 CPU 模糊或实色回退。当前只验证 API 35 的 AGSL 与实色切换，不据此推断低版本路径通过。[Android RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect)、[Android AGSL](https://developer.android.com/develop/ui/views/graphics/agsl)。AppKit 属于 macOS 框架，不能直接用于 Android APK。

## 最终产物与实际环境

- 包名 `cn.ngapure.app`，versionName `0.5.0`，versionCode `5`，127,541 字节。
- SHA-256：`bd03c96faeeeb466777b1a14054fdb545d87486fa3361e990c9d04e944914aea`；取回的已安装 base.apk 与交付 APK 字节一致，V2/V3 签名及 zipalign 通过。
- 渲染器 165 / API 41 / 评分 15，共 221 项回归通过；最终 lint 0 错误 / 1 个既有官方 WebView JavaScript 警告。
- 本轮已核验并运行专用 `emulator-5582 / NgaPureUiV3`：官方 Emulator 37.2.12，API 35 Google APIs arm64-v8a rev9，1080×2400 / 420 dpi，headless、2 CPU。
- 启动请求 1536 MiB RAM，模拟器实际提升到 2560 MiB；Guest Vulkan feature disabled，但宿主 ANGLE 仍使用 SwiftShader Vulkan 后端。
- 此前有窗口的宿主 QEMU 出现 Qt accessibility / main-loop hang，有宿主冻结证据，未当作应用崩溃。恢复 headless 后完成测试；结束后已正常关闭此专用 VM，userdata、Keystore 和账号保留，未 wipe。

## 持续触点、形变与底图

[UI 手势工具](../tests/ui/README.md) 独立构建，源码位于 [LiquidGestureTest.java](../tests/ui/src/cn/ngapure/gesture/LiquidGestureTest.java)。UiAutomation 注入同一 `downTime` 的 DOWN/MOVE/UP/CANCEL：首指 ID 7，多指加入 ID 11，并支持活动指交接。多次独立点击不能替代这些持续触点证据。

| 场景 | 峰宽 px | 相对静止宽度 | 最低高度比例 | 已通过行为 |
| --- | --- | --- | --- | --- |
| 慢拖保持 | 322.07 | 1.382× | 0.890 | 持续按住并拉伸、压扁 |
| 快速反向 | 353.93 | 1.519× | 0.848 | 未抬指即反向，结束后归位 |
| 取消 | 370.11 | 1.588× | 0.889 | CANCEL 直接回到已提交的 index 0 |
| 多指 | 354.06 | 1.520× | 0.862 | 换指继续拖动，不误开首页菜单 |
| 垂直移动 | 233.00 | 1.000× | 1.000 | 不进入横向形变，不误开菜单 |
| 未选中项起拖 | 365.65 | 1.569× | 0.848 | 从 index 2 起拖，头端到 2.8573，最终归 index 3 |

UP 后 1 秒，head/tail 均到目标，静止尺寸 233×136 px，`animating=false`。数据是本设备采样，不是跨设备固定尺寸。见 motion-summary-0.5.0.json（历史附件未收入源码仓库）。

各持触窗口的底图计数只出现一个不同值：慢拖 25、反向 31、取消 36、多指 36、未选中起拖 41。它证明各窗口内复用底图，形变及局部折射无需逐帧捕获全背景；不表示不同动作之间永远不刷新。静止及键盘隐藏时也确认停止相关帧/底图工作。

## 已通过范围与画面证据

48 项设备检查（历史附件未收入源码仓库） 全部通过；最终状态（历史附件未收入源码仓库） 保留单栏、AGSL、稳定几何及登录/自动签到配置的非敏感诊断。

- 慢拖、快速反向、CANCEL、多指、垂直移动、未选中项起拖，以及松手回缩和归位。
- 单层主导航、临时四分组、标签更新、返回/外部关闭、分组阅读位置保留。
- 应用关闭动画及恢复、系统零动画及原设置恢复，选择均到稳定终态。
- 键盘隐藏及恢复、隐藏时停止帧/采样、版块搜索文本跨导航保留。
- 深色分组、AGSL、实色单栏及恢复玻璃；末行完整露出，原生评分可用。
- 快速跨项后回到唯一稳定底栏；已安装 APK 字节一致，录屏后无应用崩溃，账号及自动签到配置保留。

已完成 真实操作录屏（历史附件未收入源码仓库），720×1600、16.6036 秒、2,842,784 字节；预览图（历史附件未收入源码仓库） 使用真实画面。内容包括慢拖、反向、快速跨项、临时分组及底栏后的内容滚动。

视频校验记录（历史附件未收入源码仓库） 包含原视频 SHA-256 与检查范围。91 个请求采样对应 60 个不同实际视频时间，重复时间不计为新的独立帧。motion-frames.json（历史附件未收入源码仓库） 记录以下裁剪帧与实际视频时间：

| 画面 | 原视频时间 |
| --- | --- |
| 静止导航（历史附件未收入源码仓库） | 0.0000 s |
| 慢拖拉伸（历史附件未收入源码仓库） | 2.5303 s |
| 反向过程（历史附件未收入源码仓库） | 5.6789 s |
| 反向后状态（历史附件未收入源码仓库） | 6.4511 s |

其他设备截图：单栏（历史附件未收入源码仓库）、临时分组（历史附件未收入源码仓库）、滚动（历史附件未收入源码仓库）、键盘（历史附件未收入源码仓库）、深色（历史附件未收入源码仓库）、深色分组（历史附件未收入源码仓库）、实色（历史附件未收入源码仓库）、最后一行（历史附件未收入源码仓库）。这些来自实际模拟器，未用生成式界面图替代；模拟器运行速度不能作为 60 fps 或真机性能认证。

## 复现与未覆盖边界

构建入口为 `bash tests/ui/build.sh`。运行时指定专用 serial 及当前布局的物理像素坐标，并把设备自身 `/system/framework/android.test.base.jar` 作为额外 runtest jar；否则可能在真正执行手势前缺少 `RepetitiveTest`。须完整出现 `DRIVER_READY → TOUCH_DOWN → DRIVER_DONE` 并结合外部状态确认，单独框架成功文字不够。

同步 PNG 编码和内部状态调用曾影响短手势时序，本次原速测试关闭二者，以外部低频状态及独立录屏核验。操作墙钟事件与视频实际时间属于不同时间基准，不直接相减推断输入延迟。模式及参数见 [tests/ui/README.md](../tests/ui/README.md)。

尚未覆盖 API 26/31、低版本 CPU 回退、厂商真机、低内存、完整 TalkBack/更多辅助输入、横屏/低矮屏幕及自然跨日 24 小时调度。全新安装、重新扫码、过期退出、实际发帖/上传和更多手势越界组合也不由本轮升级保留结果推断通过。

扫码、服务器补签与同日去重历史见 [0.2.0](EMULATOR-VALIDATION.md)；[0.4.0](LIQUID-0.4.0.md) 为旧双层结构，均不自动成为本版全部流程证据。完整边界见 [验证总览](VALIDATION.md) 和 [设备清单](DEVICE-TEST-CHECKLIST.md)。
