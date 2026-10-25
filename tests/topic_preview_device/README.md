# 原生楼主图片预览设备夹具

该测试运行真实 `TopicPreviewView`、`TopicPreviewImages` 和 `NativeImages`。构建时要求最终候选 APK 与完整生产 JAR 的独立 SHA-256；把完整 JAR 原样交给 d8，并记录所有生产 class 的 SHA-256，不复制改写产品类，也不允许 helper shadow 同名产品类。

独立包 `cn.ngapure.previewprobe` 使用独立 UID，Manifest 没有 INTERNET 或共享 UID。合成首帖元数据来自真实管理器的 Loader 构造接口；附件图片由进程内 URLStreamHandlerFactory 返回已知 PNG，不访问 NGA、账户、Cookie、真实 API 或云端写入。夹具注册并启动真实 Android Activity，测实际 ScrollView 布局、可见性、窗口 detach 和异步结果。字体 1.5、density 160 的覆盖只作用于夹具 Activity，不修改模拟器全局设置。

覆盖：离屏 0 请求与 0 高度，持续可见滚动抑制请求，停止后读取，无图与失败不留空位，最多 3 图、原始图片 URL 点击与父行隔离，离屏取消实际元数据及媒体工作，释放 ImageView 像素引用，返回复用缓存，已排队结果和晚到图片遵守账户 generation，关闭后不复活，220 dp 大字号三图不溢出。图片总数角标放在首张图内，不额外占一行；测量角标文字和实际边界，检查总数 4 张不被显示数量 3 替代、4096+ 张保留未知总量的下界语义，以及完整计数的无障碍描述。

0.20.6 增加需求感知与退避边界：已知无图、三图已加载的行各调用真实产品滚动监听器 120 次，检查没有 discovery 定时任务、静置后无额外 current 校验。这里直接调用监听器，不等同 Android 框架派发或 120 个真实绘制帧；原有实际 ScrollView 滚动验证继续保留。图片加载器真实占满 32 个订阅后，分别验证容量恢复会重试、持续繁忙在 3 次自动重试后停止、离屏取消退避、实际 404 不循环。元数据失败不能变成已知无图；两次独立场景等待真实 30 秒错误缓存 TTL，验证同一可见行可恢复、第二次真实失败停止自动重试。没有修改产品时钟、deadline 或缓存私有数据来缩短等待，设备 runner 应至少允许 120 秒。

焦点边界另用真实 Dialog：普通弹窗保留已经显示的缩略图，关闭后不闪烁或重下；如果失焦期间父 ScrollView 已移到离屏位置，恢复焦点时必须释放保留像素，回滚可复用解码缓存。重试预算跨离屏和焦点往返保留，避免滚动重置预算导致无限自动重试。

实际 `scrollTo` 后按条件等待离屏像素释放和退避任务取消；`waitForIdleSync` 本身不保证下一次 traversal 的 `ViewTreeObserver` 滚动通知已经派发。测试记录滚动返回时的像素数量和后续释放等待时间，不调用产品 `pause` 或强制派发监听器来满足离屏断言；等待有 4 秒上限，未释放仍失败。缓存失败行也先等待真实 attach/layout 建立 TTL 任务后再检查，产品定时和时钟保持原样。

繁忙恢复、重试耗尽、离屏退避和真实 404 四个用例先等元数据成功创建一个缩略图及对应状态数组，再观察异步失败状态；轮询在数组尚为空时只表示“未就绪”，不能越界，也不能当作恢复成功。焦点变更等待 Activity 实际焦点；恢复后的像素状态另行等待，所有原功能断言保持。

最后只通过 PackageManager 读取已安装产品的公开 ApplicationInfo 图标并原生绘制 `files/icon-native.png` 和 `files/icon-monochrome.png`，不启动产品 Activity 或读取其私有目录。单色图是原生 monochrome 图层加固定色，不能用它声称系统启动器已开启主题图标。实际系统桌面截图仍由主任务完成。

构建：

```sh
PRODUCT_JAR=/absolute/candidate/production.jar \
PRODUCT_APK=/absolute/candidate/candidate.apk \
PRODUCT_JAR_SHA256=independently_recorded_sha \
PRODUCT_APK_SHA256=independently_recorded_sha \
SIGNING_DIR=/private/signing-directory \
BUILD_DIR=/absolute/fresh/topic-preview-build \
bash tests/topic_preview_device/build.sh
```

主任务在专用模拟器安装匹配产品和 helper 后执行：

```sh
adb shell am instrument -w -e syntheticPreviewOnly true \
  cn.ngapure.previewprobe/cn.ngapure.previewprobe.TopicPreviewProbe
```

输出 JSON key 为 `safe_topic_preview_report`。夹具没有公网能力，`realNetworkRequests` 和 `realCommunityWrites` 必须为 0。图标文件可以使用 `run-as cn.ngapure.previewprobe cat files/icon-native.png` 导出，它们仅位于夹具自己的私有目录中。主任务负责检查实际安装产品 APK 的 SHA、保存首次失败和重试记录、卸载 helper。
