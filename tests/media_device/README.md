# Android 原生媒体隔离验证

本探针运行于独立包 `cn.ngapure.imageprobe`，有自己的 UID，未申请 INTERNET 权限。它使用原样打包的产品 `classes.jar`，并以进程内假 HTTPS 传输返回本目录生成的双帧 GIF、静态 PNG，不读取主客户端账号或数据。Android 图片解码、动画和原生文本控件均为真实实现。

需要 JDK 17、Android SDK 35/build-tools 35.0.0，以及产品构建产物。依赖路径可通过 `JAVA_HOME`、`ANDROID_SDK_ROOT` 配置。脚本不下载依赖，也不自动安装或操作模拟器。

在项目根目录构建：

```sh
PRODUCT_CLASSES=/absolute/path/classes.jar \
PRODUCT_APK=/absolute/path/NgaPure.apk \
SIGNING_DIR=/absolute/path/signing \
BUILD_DIR=/absolute/path/media-device-build \
bash tests/media_device/build.sh
```

`SIGNING_DIR` 需包含与产品调试构建一致的 `ngapure-debug.keystore`。输出 APK、生产类绑定和生成的 `assets/binding.json` 均放在 `BUILD_DIR`；源码目录不生成绑定文件。比较历史版本时可额外设置 `SOURCE_ARCHIVE` 为其不可变源码 ZIP，绑定会记录归档及源文件哈希；不同版本使用不同 `BUILD_DIR`。

人工安装探针后，组件为 `cn.ngapure.imageprobe/cn.ngapure.imageprobe.NativeImagesProbe`，结果字段为 `safe_native_images_report`。每次运行前先结束探针进程，保证进程级假 URL 处理器重新初始化。运行参数：

- `mode=all`：30 个相同 GIF 一次下载，动画实例独立且真实启动/停止；静态图片共享位图；单订阅/最后订阅取消；关闭时抑制排队回调；24 个任务、32 个全局待交付订阅的上限；60 个表情的繁忙退避恢复；连续 6 次解密替换释放旧图片且保留外部图片。
- `mode=soak soakRounds=50`：逐轮加载、取消、关闭和替换解密内容，核对队列、任务、订阅、连接全部归零及工作线程结束。轮数上限 200。记录运行时堆与线程观察值，不声称据此证明无内存泄漏。
- `mode=retention expectBoundedRetention=true`：真实滚动包含50张不同512×512合成PNG的原生长帖，记录span强持位图的数量/像素字节，校验离屏decoded像素约束、回顶部零新增传输、阅读位置和总排版高度不跳变。对旧版可传 `expectBoundedRetention=false` 只记录基线；初始预取可能因快速滚动取消，逐图transport次数单列，回滚阶段仍严格要求没有新增下载。此处测量的是强持的Bitmap对象，不把GC后堆总量当作结论。
- `mode=parser`：ART 下分别解析 200/800 个引用块，每组 2 次预热、5 次计时，核对块数并输出中位时间；耗时不作为硬性通过阈值。此模式不调用图片解码器，报告会明确标识。

实际设备运行由操作者单独执行，构建脚本不会调用 ADB。

每个隔离 loader 会在探针自己的 cacheDir 下创建独立缓存目录，以免不同场景互相命中缓存；生产只配置固定的应用私有目录。旧版类没有缓存配置接口时自动跳过，便于绑定旧代码比较。

`cn.ngapure.imageprobe.NativeMediaPlayerProbe` 使用产品原样播放器和本机生成的45秒全静音PCM WAV，真实播放/暂停系统 `MediaPlayer`，仅在主线程模拟焦点事件。`expectFixed=true` 校验连续两次瞬时丢焦点后恢复播放，显式暂停/永久丢焦点/进入后台后不自行重播，停止/关闭释放解码器和焦点；`expectFixed=false` 仅用于旧版缺陷基线复现。构造器通过反射使用本机文件是隔离fixture机制，产品对外的媒体来源校验没有放宽。

`NativeImagesProbe mode=retention_soak soakSeconds=1200` 在50图预热后执行实际20分钟墙钟往返滚动（600ms一步，范围60–1800秒），每5秒通过 `safe_native_images_soak_progress` status事件及最终报告采样强持的Bitmap像素字节、Java堆观察值、线程、队列和连接。每步要求排版总高不变、预热后零新增下载，解码驻留窗口与视口尺寸相符；报告分开记录requested/actual duration，短预验不会标为长时运行。

其他可选独立模式：

- `mode=image_layout expectSourceDimensions=true`：1080×3000、2560×1440、160×90、1081×3001四种PNG，分别记录原始header、真实采样像素与原生span尺寸；校验位图cache新wrapper的原始逻辑尺寸、防御拷贝、离屏释放后重解码不改高度/不新增下载。小原图不强制铺满，inline实际解码像素仍至多800000。旧版传false可记录被采样intrinsic限制的基线。
- `mode=image_exif expectSourceDimensions=true`：带EXIF方向6的1600×900合成JPEG，记录BitmapFactory原始header、ImageDecoder已定向Info尺寸、真实旋转后输出和原生span；校验角像素方向，防止逻辑尺寸把竖图拉成横图。BitmapFactory独立路径的实际输出方向也单列。
- `mode=original_read_cancel expectReadCancellation=true`：ART下真实production原图字节读取，fake HTTPS socket在headers/body阶段忽略Thread.interrupt，只由Token关闭连接；覆盖预取消、阻塞header、阻塞body、headers后取消四阶段。旧版传false用于明确复现缺陷。
- `mode=decode_backpressure expectDecodeBackpressure=true`：短暂阻住helper自己的主回调队列，对30个512×512双帧GIF订阅统计“已独立解码、待主线程交付”的结果数，验证仅一个结果先行、最终仍30独立原生动画/一次传输，close能中断等待。旧版传false记录30个提前解码的基线。native heap数值为观察项，不当作确定的pixel预算。
