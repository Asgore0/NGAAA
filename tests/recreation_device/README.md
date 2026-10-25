# Activity 重建与头像选图回归

这是运行在 `cn.ngapure.app` 中的 Android instrumentation 测试。源码与 0.19.4 模拟器验收使用的 helper 相同，helper 不打包生产类、不声明网络权限。它启动真实 MainActivity 并调用 `Activity.recreate()`，验证评分分类/页码、版块筛选、列表精确像素锚点、触摸与离页取消恢复，以及头像系统选图结果恢复。

头像验证只从测试 provider 读取现场生成的 240×160 渐变 PNG，检查真实原生裁剪界面并取消；不会点击真实账号的上传按钮。旧账号与刷新凭据场景使用合成 Session 和只计数的 Host，不改变真实账号。输出只有布尔结果、锚点数字和产物摘要，不导出筛选文本、账号或 Cookie。

需要 JDK17、Android API35 与 build-tools35.0.0。显式指定同一次构建的生产 jar/APK，以及私有签名目录；签名密钥必须与被测 APK 相同。源码包不包含密钥或编译产物：

```sh
PRODUCT_JAR=/absolute/path/classes.jar \
PRODUCT_APK=/absolute/path/NgaPure.apk \
SIGNING_DIR=/absolute/private/signing \
BUILD_DIR=/absolute/tmp/recreation-device \
bash tests/recreation_device/build.sh
```

脚本只构建，不连接或操作设备。默认构建输出在项目 `.build/recreation-device`。`JAVA_HOME` 和 `ANDROID_SDK_ROOT` 可覆盖本机工具路径。构建产生的 `binding.json` 记录输入 APK/jar、helper 与源码摘要；运行前还须独立核对模拟器实际安装的 APK。

运行仅限专用测试模拟器：先安装匹配的生产 APK 和 `recreation-probe.apk`，确认已登录且当日签到已完成，然后执行以下 instrumentation。该 helper 本身不声明网络权限；由于运行在目标应用进程，MainActivity 仍可进行正常的只读加载与已开启的后台任务。

```sh
adb shell am instrument -w -e recreationOnly true -e testAvatar true \
  cn.ngapure.recreationprobe/cn.ngapure.recreationprobe.MainRecreationProbe
```

读取 `safe_recreation_report`，要求 `passed`、`completed`、`accountAndSessionUnchanged` 为 true，`finalAvatarUploadButtonClicked` 为 false。列表恢复必须保留完全相同的行与偏移，不能以放宽像素阈值代替修复。测试结束后卸载 `cn.ngapure.recreationprobe`，也会移除其只提供合成图片的公开测试 provider。

0.19.5 增加裁剪窗口本身的重建与资源归属测试：

```sh
adb shell am instrument -w -e localCropOnly true \
  cn.ngapure.recreationprobe/cn.ngapure.recreationprobe.AvatarCropLifecycleProbe
```

读取 `safe_crop_lifecycle_report`。它要求真实 MainActivity 重建后恢复相同的归一化位置与裁剪像素，取消后不再恢复，旧窗口 Bitmap 已回收，跨会话或非法 URI 在读取 provider 前被拒绝。另用合成 Session、只排队的 Executor 和必定抛出异常的合成 Transport 验证提交开始立即清除恢复状态、重复按钮被禁用；这一步只点击隔离测试弹窗的提交按钮，运输层在任何图床请求之前终止，不使用真实账号或真实网络。

另覆盖取消的 150ms 离场动画：点击取消后在同一个 UI 轮完成排队的本地解码，以及立即重建 MainActivity，都不能再弹出或恢复预览。基线观察可额外传 `-e observeCancelBaseline true`；该模式只记录全部取消竞争观察、不因第一个旧行为提前停止，`observedBaselineOnly` 会标记此模式。发布验收使用默认完整模式，要求全部断言通过。

图片查看器的 MainActivity 重建测试（API 29 及以上）：

```sh
adb shell am instrument -w -e generatedImageOnly true \
  cn.ngapure.recreationprobe/cn.ngapure.recreationprobe.ImageViewportLifecycleProbe
```

读取 `safe_image_viewport_report`。它在每次 MainActivity 的 `onCreate` 之前关闭媒体加载器，使用两个永不请求网络的合成附件 URL 与程序生成的 600×1600 色块。验证 exact URL + 三个 float 的有界状态、异步图片尚未加载时连续重建、最后交付图片后的缩放与位置，以及切到另一张图片/离开查看器时没有旧状态或 View 引用。测试不输出图片、不读取真实照片，结束后关闭测试 Activity 释放生成图片。
