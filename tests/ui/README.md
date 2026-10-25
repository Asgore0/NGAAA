# 原生导航物理手势测试

这个独立 UIAutomator 工具用于验证 LiquidSelection 的持续拖动、反向、取消、换指和点击，并增加 0.6.0 的快速连续折返、松手后立即接管、跨项点击及首页快速再选模式。它通过标准 Android UiAutomation 注入完整 MotionEvent 序列，无需安装测试 APK、申请新权限或启动常驻服务。源码不包含账号、Cookie、二维码、截图或录屏；工具不登录、不签到、不读取帖子。

0.5.0 已在专用 API35 headless 模拟器中实际运行六种模式，出现 `DRIVER_READY`、`TOUCH_DOWN`、`DRIVER_DONE`，并通过外部状态与画面验收。外部几何采样曾记录慢拖宽度约 1.382×、高度 0.890×；反向宽度约 1.519×、高度 0.848×。不同坐标与采样时刻的值会变化，须结合本次画面判断。

新增四种快速模式已通过独立严格编译，并在 0.6.0 专用 API35 模拟器执行。zigzag 完成 14 段移动 / 12 次反转，regrab 三次 contact，rapid taps 16 次点击，home reselect 实际首次 UP 到再次 DOWN 为 44 ms 且分组正确展开。完整 70 项设备检查（历史附件未收入源码仓库） 与 运行汇总（历史附件未收入源码仓库） 随交付保留；这些结果不等于每个像素帧均被捕获或 60 fps 认证。

## 独立构建

要求 JDK17、Android SDK Platform 35 和 Build Tools 35.0.0。设置 `JAVA_HOME` 与 `ANDROID_SDK_ROOT`（也接受 `ANDROID_HOME`）；未设置 SDK 变量时尝试常用 SDK 安装目录。脚本根据自身路径定位源码，可从任意当前目录运行，不依赖工作区路径。

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash tests/ui/build.sh
```

构建执行 Java 8 严格编译 `-Xlint:all -Werror` 与 D8，结果为 `tests/ui/.build/gesture-driver.jar`。该 jar 只有测试 dex 和 manifest，SDK/JUnit 仅用于编译与 D8 类型解析；不把 SDK 桩类或 JUnit 打包进去。`.build` 是临时缓存，不应放入源码 ZIP 或 APK。

可选变量：`ANDROID_API_LEVEL`（默认 35）、`ANDROID_BUILD_TOOLS_VERSION`（默认 35.0.0）、`UI_TEST_BUILD_DIR`（默认本目录 `.build`，指定时推荐绝对路径）。

## 选择专用设备与坐标

在运行前用 `adb devices -l` 确认专用模拟器 serial，所有后续命令显式加 `-s`。不要使用其他任务的模拟器。先自行打开已安装的 `cn.ngapure.app`，保持前台、底部导航可见、键盘与分组弹窗关闭，并把选中项复位到测试起点。

在手势开始前通过静止画面或 UI hierarchy bounds 观测当前底栏：`X0` 是起始项中心 x，`Y` 是导航行中心 y，`CELL` 是相邻项中心间距，均为物理显示像素。不要把 dp 当像素，也不要复用旋转或尺寸变化前的坐标。以下正向模式建议从最左项向右，reverse 至少需要两个 cell 的右侧空间。新增快速模式要求 `X0` 是最左项中心，四个导航项都可见。`rapid_zigzag`、`regrab`、`rapid_taps` 从首页已选中开始；`rapid_taps` 会先点最右项，避免重复点击已选首页打开分组。`home_reselect` 则必须从版块页 `main=1` 开始，首页尚未选中。

工具在 DOWN 前校验前台包名严格等于 `cn.ngapure.app`，并检查起点与轨迹没有越出显示范围；校验失败不注入。它不会自动打开应用、切换页面、清数据或恢复会话。

## 运行

在项目根目录执行；替换 serial 和当前观测到的坐标：

```sh
SERIAL=emulator-XXXX
X0=159
Y=2232
CELL=254
adb -s "$SERIAL" push tests/ui/.build/gesture-driver.jar /data/local/tmp/ngapure-gesture.jar
adb -s "$SERIAL" shell uiautomator runtest \
  /system/framework/android.test.base.jar /data/local/tmp/ngapure-gesture.jar \
  -c 'cn.ngapure.gesture.LiquidGestureTest#testGesture' \
  -e mode slow_hold -e x0 "$X0" -e y "$Y" -e cell "$CELL" \
  -e screenshots false -e states false -e samplePauseMs 0
```

API35 旧版 `/system/bin/uiautomator` 启动脚本会漏掉设备自身的 `android.test.base.jar`；其 WatcherResultPrinter 在测试方法执行前就解析 `android.test.RepetitiveTest`。因此必须把上面的**系统 base.jar 作为额外 runtest jar**，由启动脚本加入同一 CLASSPATH。不这样做可能抛 `NoClassDefFoundError`，即使同时打印 `OK (1 test)` 也没有实际执行手势。不要将电脑 SDK 的 stub jar 推送到设备作为运行库。

确认完整出现 `DRIVER_READY` → `TOUCH_DOWN` → `DRIVER_DONE`，且无异常，再结合 App 画面和外部状态判断通过。单独的 `OK (1 test)` 不足以判定通过。App 或设备无响应时先恢复测试环境，不继续注入。

## 十种模式

一次 DOWN 到 UP/CANCEL 始终复用同一 `downTime`，初始 pointer ID 为 7。多段快速模式会明确打印每次新 DOWN 和 UP，不将不同触摸伪装成同一次。MOVE 默认约每 16 ms 发送，时间点以实际输出的 uptime / elapsedMs 为准。

| mode | 输入序列与验证点 |
|---|---|
| `slow_hold` | 0 → 0.6 cell / 600 ms → 保持 250 ms → 1 cell / 300 ms → UP；验证前端跟手、尾部滞后及回缩 |
| `reverse` | 0 → 2 cell / 300 ms → 0.7 cell / 180 ms → UP；验证快速反向从当前轮廓接续 |
| `cancel` | 0 → 0.8 cell / 450 ms → 保持 180 ms → CANCEL；应回到已提交项，不提交新选择 |
| `multi` | ID7 → 0.35 cell / 200 ms → 加 ID11 于 0.55 cell → 双指 +0.3 cell / 200 ms → 抬 ID7（index0）→ ID11 续拖至 1.3 cell / 200 ms → UP；换指不跳变，只提交一次 |
| `vertical` | x 不变，y 向上移动 0.75 cell / 350 ms → UP；不误开首页分组选项 |
| `tap` | 原点保持 80 ms → UP；已选首页再次点击应打开分组选项 |
| `rapid_zigzag` | 同一 DOWN 内依次到 2.8 / 0.2 / 2.65 / 0.35 / 2.9 / 1.1 / 2.3 cell，各段 150 / 140 / 130 / 120 / 160 / 140 / 130 ms，默认重复两轮后 UP：共 14 段移动、12 次方向反转，轮间两段同向；没有中途抬指或等待归位 |
| `regrab` | 拖至 2.2 cell / 220 ms → UP → 间隔 24 ms → 原松手位置重新 DOWN 并反向至 0.35 cell / 180 ms → UP → 间隔 24 ms → 原位置再 DOWN 至 2.65 cell / 180 ms → UP；共三次触摸，检验弹簧未结束时再抓取 |
| `rapid_taps` | 按 3 → 1 → 2 → 0 → 2 → 1 → 3 → 0 跨项点击，默认两轮；每次按住 32 ms、UP 到下一 DOWN 间隔 24 ms，不在每项等待动画完成 |
| `home_reselect` | 从版块页 `main=1` 开始，在首页中心 DOWN → 保持 32 ms → UP → 间隔 24 ms → 同处再次 DOWN → 保持 32 ms → UP；记录两次独立 contact，验证延迟首页渲染期间再次点首页不会丢失分组打开意图 |

新增模式强制 `screenshots=false`、`states=false`、`samplePauseMs=0`，不允许同步采样改变连续折返或接管时序。可沿用上方命令，将 `mode` 改为相应名称并先设置该模式的起始页。可选参数如下，均在注入前校验：

| 参数 | 默认 / 范围 | 作用 |
| --- | --- | --- |
| `repeatCount` | 2 / 1..10 | `rapid_zigzag` 折返轮数、`rapid_taps` 八次点击序列轮数；不改变 `regrab` 的三次或 `home_reselect` 的两次触摸 |
| `gapMs` | 24 / 0..250 ms | `regrab`、`rapid_taps` 的 UP 到下一 DOWN 计划间隔；`home_reselect` 限定 16..24 ms。实际包含系统注入/打印耗时，以事件 uptime 差为准 |
| `tapHoldMs` | 32 / 16..120 ms | `rapid_taps` 的计划按住时间 |

默认 `rapid_zigzag` 应有 1 次 DOWN/UP、14 个段末 TURN marker，对应 14 段移动及 12 次方向反转；TURN 名称不等于反转计数。`regrab` 有 3 次 DOWN/UP；`rapid_taps` 有 16 次 DOWN/UP。每次重抓使用新的真实 `downTime`。`durationScale` 也缩放这些计划时长，不调整系统动画倍率；需要保留原速证据时用默认 1。

`home_reselect` 固定两个 32 ms contact，要求 `durationScale=1`，默认 `gapMs=24`，也可指定 16..24。marker 明确输出 `HOME_RESELECT_CONTACT_1_DOWN/UP` 与 `HOME_RESELECT_CONTACT_2_DOWN/UP`，对应 sequence 1、2。先确认版块页已稳定，再执行；预期最终为 `main=0` 且临时分组打开。若实际 UP 到第二次 UP 超出 72 ms 窗口，须记录实际时间，不能仅凭配置值声称覆盖了窗口内再选。

异常路径会尽力发送 CANCEL，防止遗留触摸。换指保留镜片与手指之间的抓取偏移，不会把镜片跳到另一根手指的位置。

## 观测与已知限制

每个关键 MOVE 终点、换指点、RELEASED、SETTLED 均打印 `GESTURE_MARKER`，包含实际 uptime、当前 downTime、全轮 runElapsedMs、sequence、累计成功注入事件数、held、pointer IDs 和当前首指坐标。每次 DOWN/UP 单独打印 `TOUCH_DOWN`/`TOUCH_UP`；`DRIVER_DONE` 汇总触摸次数和事件数。打印与注入仍有运行开销，不能将计划毫秒数直接当作设备实际时序。`held=true` 是注入序列状态；App 是否识别为拖动须看外部 `NgaPureMotion dragging=true`。

**内部 `-e states true` 在已测运行环境只返回 `MOTION_STATE_UNAVAILABLE`，尚不支持作为状态验收来源。保持 `states=false`，使用独立外部 ADB 在目标时刻采样：**

```sh
adb -s "$SERIAL" shell dumpsys activity cn.ngapure.app/.MainActivity --ui-state
```

可在另一终端或宿主采样进程中定时执行，避免阻塞手势 driver；只保存 `NgaPureMotion` 行即可，无需保存账号或其余 activity dump。无需依赖 UIAutomator idle 等待，禁止用高频 dump 干扰 App。状态行包含 head/tail、width/height、heightScale/activity、animating/dragging；静止尺寸可作本次倍率基准。

`-e screenshots true` 会在 marker 使用 UiAutomation.takeScreenshot 保存 PNG，`-e out /data/local/tmp/名称` 指定设备临时目录。`-e samplePauseMs N` 可在 held marker 后延长按住（0..5000，默认 0），方便外部取样；它改变原始时序，不能用来证明快速反向没有停顿。

截图、PNG 编码与内部状态查询是同步操作，会干扰短手势时序。精确逐帧验收应关闭上述同步采样，在另一个宿主进程中提前启动屏幕录制，再运行原速 gesture。截图适合对照静态轮廓，精确形变与反向连续性以视频帧及实际时间戳核验。

快速接管验收建议分别记录三种模式：先启动独立录屏，再执行 driver，保存完整 marker；外部几何采样仅作头尾位置、宽高、接管/归位和 snapshot 计数的辅助证据。不要在 MOVE 之间同步运行 dumpsys 或截图。低频 dumpsys 会漏掉短暂跳变，因此即使每次采样连续，也不能称为“逐帧无跳变”。视频应按实际解码时间检查反向前后和 UP/新 DOWN 前后帧；缺帧、重复帧或模拟器低帧率时，明确记录可观察的时间范围，不能由静止截图或稀疏采样补称未捕获画面通过。不同触摸的几何应从当前状态接续，但直接操控的允许变化仍须与真实触点及设计规则共同判断，不能把所有位置变化都当作错误。

其他参数：`sampleMs` 8..50（默认16）、`durationScale` 0.25..10（默认1）。最终额外等待1000ms后打印 SETTLED。工具不改变系统动画倍率；验证应用关闭动画或系统倍率0时，应由验收者事先设置，再观察是否立即落终态。

## 0.8 原生文字夹具

同一测试 jar 还包含 `NativeTextFixtureTest`。仅在目标客户端已打开对应原生编辑器、且现有草稿为空或明确是本工具测试草稿时输入合成中文；使用公开 AccessibilityNodeInfo ACTION_SET_TEXT，写后逐字验证，避免旧键盘字符注入漏掉中文。`testPreviewFixture` 只打开原生预览；新私信夹具不填真实收件人，不按检查、发送、发布或上传。工具不读取凭据，也不将私信截图写入交付资料。
