# 0.6.0 连续接管、轮廓与光学更新

> 历史／来源文档：保留原版本的实现说明和验证边界，不代表当前候选重新验收。原始设备附件未随源码公开；当前状态见[文档索引](README.md)。

日期：2026-10-18（Asia/Shanghai）。本版保留单一 64 dp 主导航和临时首页分组，重点改进连续快速往返、跨项点击及松手后立即重新抓取。物理模型 15 场景 / 25,031 断言、221 项文件回归及最终 70 项设备检查全部通过，实际输入、UI 与视频证据已随交付保留。

这是原生 Android 的 Apple Liquid Glass 风格近似，不是 AppKit 移植，也不承诺逐像素一致。Apple 资料提供直接操控、连续融合与降低动态效果的设计原则，不提供本项目的弹簧和折射参数。[HIG：Gestures](https://developer.apple.com/design/human-interface-guidelines/gestures)、[GlassEffectContainer](https://developer.apple.com/documentation/swiftui/glasseffectcontainer)、[HIG：Motion](https://developer.apple.com/design/human-interface-guidelines/motion)、[Adopting Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass)。

## 本版改动

| 部分 | 实现与目的 |
| --- | --- |
| 连续运动 | 独立 LiquidMotion 使用连续时钟推进。切换目标保留当前几何、速度和时钟，避免快速新目标重新初始化运动 |
| 重新抓取 | captureOffset 从当前位置连续衰减，保留抓取瞬间的状态；从胶囊外开始拖动时逐渐追上触点，不直接搬到触点 |
| 松手提交 | 使用最终触点的 indexAt(event.getX())，避免视觉 offset 令 motion.target 临近边界而误选邻项 |
| 拉伸限制 | soft gap 只平滑限制可见分离，不把原始尾端硬投影到长度边界 |
| 外轮廓 | 左右边缘始终有序，圆角与偏置连续变化，降低反向过零时轮廓交换的突变 |
| 光学 | 光照平滑跟随，rim 方向保持稳定；同次 draw 合并 optics 更新，缓存渐变并更新矩阵，减少重复分配与滤镜构建 |
| 绘制路径 | API 28+ 不再强制 SOFTWARE；API 26/27 保留兼容路径，胶囊复用三个渐变与 Matrix |
| 页面切换 | 胶囊选择立即响应；页面在 72 ms 安静窗口后只构建最新目标，按住期间延期，避免快速连续输入中拆页 |
| 首页再选 | 待切换目标为首页时，快速再次点首页先完成首页切换，再展开分组，避免第二次点击被旧 mainTab 拦截 |

实色模式、应用动画开关和系统零动画路径继续保留。API 31 起使用 RenderEffect，API 33 起叠加 AGSL，更低版本提供 CPU 模糊或实色回退；本轮不据 API 35 推断低版本运行通过。[Android RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect)、[Android AGSL](https://developer.android.com/develop/ui/views/graphics/agsl)。

Android 官方绘制支持表将非文本 `setShadowLayer()` 的硬件支持起点列为 API 28，因此本版不再为现代系统强制整块胶囊软件绘制；API 26/27 仍保留兼容处理。该支持表是路径选择依据，不等于本项目已经在这些低版本或所有厂商设备验收。[Android 硬件加速支持表](https://developer.android.com/topic/performance/hardware-accel?hl=en)。

## 已完成的模型与构建验证

[LiquidMotion.java](../app/src/main/java/cn/ngapure/app/LiquidMotion.java) 独立于 Android View。[MotionContinuityTest.java](../tests/motion/MotionContinuityTest.java) 已用可控时钟完成 15 场景、25,031 条断言：目标接管保留状态、胶囊外抓取、按住回程、换指、立即 UP 和首个合并 MOVE、快速反向、软限制、轮廓过零、60/120 Hz 时间步重放、抖动/丢帧、归位/空闲、镜像和有界 trace。

复现：`JAVA_HOME=<JDK17> bash tests/motion/run.sh`。模型结果及输入哈希（历史附件未收入源码仓库）、模型完整输出（历史附件未收入源码仓库） 随交付保留；四项输入 SHA 已核对。View 严格 Java 编译与独立手势工具 D8 已通过。模型中 60/120 Hz 是输入时间步重放，不代表模拟器或真机达到了相应绘制帧率。渲染器 165 / API 41 / 评分 15，共 221 项回归通过。

构建为 `cn.ngapure.app / 0.6.0 / code 6`，131,637 字节，SHA-256：`4efea13cc1d2178bcee79b534467f361f888e697f0c2c44c1cf9631bd2e4c80c`。最终 V2/V3 签名及 zipalign 通过，取回的已安装 base.apk 与交付 APK 字节一致；lint 0 错误 / 1 个既有官方 WebView JavaScript 警告。

## 实际测试方法与结果

本轮已核验并实测专用 `emulator-5582 / NgaPureUiV3`，API 35、headless、1080×2400 / 420 dpi，保留 userdata/Keystore，未 wipe。使用 host GPU，实际 GLES 为 `Google (Apple), Android Emulator OpenGL ES Translator (Apple M4), OpenGL ES 3.0 (4.1 Metal - 91.7)`；与前版 SwiftShader 分别记录。采集结束后已正常关闭此专用 VM，保留数据。

[独立手势工具](../tests/ui/README.md) 保留旧六模式，并新增：

| mode | 注入与目标 |
| --- | --- |
| `rapid_zigzag` | 同一 DOWN 内默认两轮共 14 段移动、12 次方向反转；每段末有 TURN marker，轮间两段同向，不中途抬指 |
| `regrab` | 拖动释放后默认 24 ms 再抓取并反向，共三个独立 contact，检查旧运动尚未结束时接管 |
| `rapid_taps` | 非相邻序列 3→1→2→0→2→1→3→0，默认两轮，每次按住 32 ms / 间隔 24 ms |
| `home_reselect` | 从版块页开始，两次 32 ms 首页 contact，中间 16..24 ms，覆盖 72 ms 待切换窗口 |

新模式强制关闭同步截图、内部 states 和 samplePause。每次 DOWN/UP、关键转折 marker 带实际 uptime、downTime、序列号与坐标；按实际时序判断是否覆盖窗口，不能只看配置毫秒数。

本轮已结合默认关闭的有界事件/绘制 trace、外部低频几何和独立录屏核对。trace 记录运行时数值，不等于像素画面；稀疏 dumpsys 也会漏掉短暂跳变。实际视频需按解码时间检查反向和新 DOWN 前后，缺帧/重复帧应明确范围，不能宣称未捕获的每帧均连续。

70 项设备检查（历史附件未收入源码仓库） 包括 37 项手势压力、28 项日常 UI、5 项最终核对，全部通过。十个运行场景中包含未选中项起拖，普通点击另在日常 UI 验证；数据见 motion-summary-0.6.0.json（历史附件未收入源码仓库）。

- 83 对接管/重定向 before-after，记录的几何和速度分量最大差为 0；trace 数值有限、轮廓边缘有序。它说明记录的接管点保持状态，不等于所有像素帧已捕获。
- zigzag 完成 14 段移动 / 12 次反转；regrab 完成三次真实 contact 并正确提交至 index 3；快速跨项为 16 次点击、9 次页面构建，构建次数取决于本次运行时序。
- 首页再选第一次 UP 到第二次 DOWN 实际 44 ms，小于 72 ms，最终首页与分组弹窗均正确。
- 单层四入口、临时分组、返回/外部关闭、标签与阅读位置、键盘隐藏/恢复及停止隐藏采样、搜索文本、深色/实色/AGSL、应用和系统零动画及恢复、末行与评分全部通过。
- 已安装 APK 一致，录屏后无应用崩溃，登录和自动签到配置保留。最终非敏感状态（历史附件未收入源码仓库） 显示单栏、AGSL、归位和无待切换项。本轮没有重新扫码或等待自然隔日签到。

## 真实视频与截图索引

约 26.06 秒真实操作录屏（历史附件未收入源码仓库） 为 720×1600，实际 26.06475556 秒，3,869,579 字节；设备显示仍为 1080×2400 / 420 dpi。预览图（历史附件未收入源码仓库） 使用真实 UI 和四张视频裁帧。

视频校验记录（历史附件未收入源码仓库） 保留 SHA-256、323 个请求解码采样 / 178 个不同实际时间及检查范围。主代理已核对三张 contact sheet；没有插帧或调速，重复时间不算新的独立帧，录像不作为 60 fps 或真机性能认证。

| 视频实际时刻 | 原视频裁帧 |
| --- | --- |
| 2.198611 s | motion-025（历史附件未收入源码仓库） |
| 6.037844 s | motion-073（历史附件未收入源码仓库） |
| 12.244156 s | motion-151（历史附件未收入源码仓库） |
| 21.973856 s | motion-273（历史附件未收入源码仓库） |

裁帧元数据（历史附件未收入源码仓库） 记录请求/实际时间及原视频哈希。UI 截图：单栏（历史附件未收入源码仓库）、临时分组（历史附件未收入源码仓库）、滚动（历史附件未收入源码仓库）、键盘（历史附件未收入源码仓库）、深色（历史附件未收入源码仓库）、深色分组（历史附件未收入源码仓库）、实色（历史附件未收入源码仓库）、最后一行（历史附件未收入源码仓库）。

## 边界与历史

尚未实测 API 26/31、CPU 回退、厂商真机、低内存、完整 TalkBack、横屏/低矮屏幕及自然跨日 24 小时调度。新安装、重新扫码、会话过期退出及实际发帖/上传不由升级保留或模型断言推断通过。

[0.5.0 历史](VALIDATION-0.5.0.md) 保留 48 项、截图及当时录屏；[0.2.0](EMULATOR-VALIDATION.md) 保留用户扫码、服务器补签与同日去重。它们不自动成为本版全部功能重测证据。当前状态见 [VALIDATION.md](VALIDATION.md) 与 [DEVICE-TEST-CHECKLIST.md](DEVICE-TEST-CHECKLIST.md)。
