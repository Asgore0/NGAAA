# NGAAA · 保留旧形的黑金 N

> 历史／来源文档：保留原版本的实现说明和验证边界，不代表当前候选重新验收。原始设备附件未随源码公开；当前状态见[文档索引](README.md)。

用户更喜欢 0.19.8 的黑金图标。0.20.0 因此恢复旧版 N 与右侧竖线的原始路径、棕黑 `#29251F` 底色和金色 `#D8B66B`，只为 Android 自适应与主题图标补齐必要的安全留白。客户端玻璃 UI 保持原样，桌面标识使用简洁的静态黑金配色。

本轮先从已经交付的 `NGAAA-Android-0.19.8-source.zip` 逐字节提取原生矢量，与“保留旧形的自适应”和“暖金轻修”两个本地候选对照。已实际查看 `work/native-v200-gallery-votes/icon-research/comparison.png` 中的大图、圆形裁切、32/48 px 和深色桌面预览，最终采用中间的“保留旧形 · 自适应”，没有使用暖金候选的渐变或新造字形。

## 原生资源

Application 继续使用 `android:icon="@mipmap/ic_launcher"` 和 `android:roundIcon="@mipmap/ic_launcher"`。

| 文件 | 作用 |
| --- | --- |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | Android 8+ 自适应前景与背景 |
| `res/mipmap-anydpi-v33/ic_launcher.xml` | 相同自适应图标，另提供主题单色层 |
| `res/drawable/ic_launcher_background.xml` | 满幅棕黑背景 |
| `res/drawable/ic_launcher_foreground.xml` | 原版 N 与竖线，以画布中心统一缩放至 0.8 |
| `res/drawable/ic_launcher_monochrome.xml` | 相同几何形状和留白的纯白前景，由启动器着色 |
| `res/mipmap-anydpi/ic_launcher.xml` | 与 0.19.8 原始矢量字节相同的兼容回退 |
| `res/drawable/ic_launcher.xml` | 保留原有引用名，同样恢复原始矢量 |

原始金色图形边界为 `x=24…84, y=28…80`。自适应前景以 `(54,54)` 为中心等比缩放后，边界为 `x=30…78, y=33.2…74.8`；最远顶点距中心约 `31.76`，位于直径 66 的保守圆形安全区内。前景和单色层使用同一变换，背景独立填满 108 dp 画布，圆形遮罩不会截掉图形边角。

## 验证范围

图标源是可直接编辑的 Android VectorDrawable 与 adaptive-icon XML，不依赖位图下载、运行时模糊、页面背景采样或帧动画。制作阶段的 SVG、Swift CoreGraphics 渲染器和 PNG 对照图仅用于本地审图，未作为应用资源或产品构建依赖。路径、配色、安全区和七个资源入口的本轮检查元数据保存在工作区 `icon-research/production-icon-check.json`。

这些本地预览与资源检查不等于 Android 启动器实测。最终 APK 的桌面外观、自适应遮罩及主题单色效果，以本轮模拟器记录为准；Android 13 的主题单色样式还取决于启动器设置，与应用内深色模式独立。
