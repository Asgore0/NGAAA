# 0.4.0 Liquid Glass 设计与验收

> 历史／来源文档：保留原版本的实现说明和验证边界，不代表当前候选重新验收。原始设备附件未随源码公开；当前状态见[文档索引](README.md)。

> 历史记录：本文仅描述 0.4.0 的两层底部结构与当时实测。当前 0.5.0 已改为单一主导航及临时分组，连续形变与本版证据请看 [0.5.0 专项记录](LIQUID-0.5.0.md) 和 [当前验证总览](VALIDATION.md)；本文结果不自动成为新版重测通过。

日期：2026-10-18，Asia/Shanghai。本版在 0.3.0 的阅读与底部操作结构上增加接近 Apple Liquid Glass 的材质及交互。本文区分官方依据、本项目实现与实际设备验证；跨版本设备结果不由当前模拟器推断。

## 四项具体依据与本项目选择

1. **玻璃限定在操作层。** Apple 将玻璃用于导航和控件，并要求避免在正文层泛用或叠放玻璃。本项目将底部分组、主导航作为浮动功能区，正文卡片保持稳定表面；捕获背景时排除玻璃节点，避免重复采样。[Apple HIG：Materials](https://developer.apple.com/design/human-interface-guidelines/materials)、[Applying Liquid Glass to custom views](https://developer.apple.com/documentation/swiftui/applying-liquid-glass-to-custom-views)。
2. **依底图保留对比度。** Apple 区分 regular 与 clear，较大菜单倾向更不透明。本项目以可读的半透明遮盖、细亮边和轻阴影承托文字，局部边缘采样产生透镜感；不让模糊滤镜作用于文字和图标。[Meet Liquid Glass，6:00 起](https://developer.apple.com/videos/play/wwdc2025/219/)、[Build a UIKit app，21:23 起](https://developer.apple.com/videos/play/wwdc2025/284/)。
3. **反馈跟随触点。** Apple 将即时触摸反馈与连续材质变化结合。本项目让选中胶囊沿手指横向移动，高光跟随触点，松手落到目标项；页面保持稳定，不恢复整页回弹。具体拖动与提交规则属于本项目选择。[Meet Liquid Glass，3:10–5:11](https://developer.apple.com/videos/play/wwdc2025/219/)。
4. **动画可减少，功能仍可用。** Apple 要求考虑降低动态效果、降低透明度与提高对比度。本项目保留实色模式；关闭动画时取消胶囊过渡和高光动画，直接到终态，保留选择与菜单操作。[Adopting Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass)。

以上是参考原则；透明度、阴影、采样位移、动画时长和刷新频率均是本项目的实现参数，不作为 Apple 公开规格引用，也不承诺与 Apple 私有材质逐像素一致。

## 0.4.0 实现范围

| 区域 | 本项目实现 |
| --- | --- |
| 底部功能区 | 首页四分组与图标主导航采用悬浮轻玻璃，保持两层操作的清楚分区；正文底部预留实际浮层高度与系统安全区，确保末行可滚动露出。 |
| 选中胶囊 | 支持点击与横向拖动，局部平移及跟随触点的高光；处理取消、越界及快速切换，避免残留半选中状态。拖动期间只移动胶囊，松手后按最近项提交一次内容切换；点击时立即提交，胶囊局部过渡 240 ms。 |
| 快捷菜单 | 保留紧凑底部菜单及可滚动内容，不恢复大面积侧滑抽屉。 |
| 背景更新 | 内容滚动期间合并有限次数的小尺寸底图刷新，停止滚动后补一次最终更新；不要求逐帧捕获，不让滚动位置因刷新重置。 |
| 实色模式 | 继续提供可读、稳定的实色背景；菜单、分组和主导航保持相同的功能与命中区域。 |
| 减少动态效果 | 结合应用动画开关与系统动画是否启用，跳过过渡、直接设置最终几何位置与选中状态。 |
| 原有功能 | 保留账号、读取、评分、阅读与签到能力；历史成功结果不自动扩展为新版重测通过。 |

## Android 实现边界

这是 Android 原生近似实现，不是 [AppKit](https://developer.apple.com/documentation/appkit) 移植，也不打包 UIKit、SwiftUI 或 Apple 私有材质。AppKit 属于 Apple 的 macOS 开发框架，不能直接用于 Android APK。

Android 的 [RenderEffect](https://developer.android.com/reference/android/graphics/RenderEffect) 从 API 31 提供模糊；它处理指定 RenderNode 的绘制内容，不会自动采集 View 后面的界面。因此先把本应用指定内容层绘制到独立的小尺寸背景层，再仅对该层模糊，文字和按钮放在上方。

[RuntimeShader](https://developer.android.com/reference/android/graphics/RuntimeShader) 和相关运行时效果从 API 33 提供，可用 [AGSL](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl) 对已采样背景做有限的边缘位移与着色。Google 文档说明作用于父 View 及所有子 View 的效果成本较高；本项目限制处理区域、图像尺寸和更新次数，不为整页文字套滤镜。

API 31–32 可保留 RenderEffect 模糊与 Canvas 高光；更低版本、低内存或着色器失败时按实际可用能力回退至低成本模糊或实色。具体回退路径、刷新预算和资源释放情况需要设备验证，不能根据 API 35 的结果推断低版本通过。

Android 的 [ValueAnimator.areAnimatorsEnabled()](https://developer.android.com/reference/android/animation/ValueAnimator#areAnimatorsEnabled()) 从 API 26 可读取系统动画是否启用，包含动画倍率为零等情形。本项目用它配合应用设置处理减少动态效果；这不是声称 Android 提供与 Apple 同名、完全对应的 Reduced Motion 设置。

## 材质与性能参数

首页正文铺满底部操作层后的实际区域，底图从独立的 `homeCanvas` 捕获，两个玻璃面板与文字都不进入采样。正文列表只在末尾增加 136 dp（首页）或 80 dp（其他主页面）加 20 dp 余量，不通过缩短视口制造假背景。主导航 64 dp，分组 48 dp，两面板间隔 8 dp，左右 12 dp。

底图最长边最多 384 px；低内存路径最多 256 px。滚动刷新间隔至少 160 ms，停滚 240 ms 后补末次刷新；静止、隐藏、分离或关闭玻璃时不进行持续采样。API 33+ 缓存每个面板的 RuntimeShader，使用圆角 SDF、约 7 dp 边缘位移和轻微色散，再叠加细亮边、内阴影与透明遮盖。文字和图标在独立清晰层。

导航浅色遮盖约 26%、深色约 40%；大菜单实测底图过透后，分别提高至 76% / 78% 以便阅读。胶囊只做局部 240 ms 平移和轻微形变，不让整页晃动。应用动画关闭或系统 animator 比例为零时直接到终态；触点移动属于直接操控，不是自动播放的动画。

## 实际结果与范围

以 [构建与设备验证记录](VALIDATION.md) 及脱敏 `device-checks-0.4.0.json` 为最终记录。当前 API 35 已确认两个悬浮面板使用 AGSL，静止快照数不增加，滚动时更新；横拖、快速换向、防垂直误触、滚动恢复、键盘避让和账号保留均有设备证据。

新版手动重复签到获得 NGA 服务器“今天已签到”。首次扫码与首次自动补签属于 0.2.0 历史验证，不当作本版重新登录或自然隔日调度通过。

API 26 CPU 模糊、API 31–32 独立模糊、低内存设备、厂商真机、横屏/低矮屏幕、TalkBack 朗读和长时间内存稳定性仍未实际覆盖；源码与静态检查支持相关路径不等于设备验收。没有等待完整 24 小时证明自然后台调度。

最终 APK 的实色开关与恢复玻璃、应用关闭动画与恢复、系统动画比例为零及恢复、末行可达、浅深菜单、原生评分和帖子阅读均实际操作通过。最终包又复验了拖动与主导航切换、785 px 阅读位置恢复、键盘隐藏/恢复与隐藏停止采样；最终崩溃缓冲为空。累计 46 项设备检查全部通过，阶段范围见验证记录。随附约 8 秒实际操作录屏，不是概念动画。
