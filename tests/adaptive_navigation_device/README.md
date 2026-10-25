# 实际生产类自适应导航测量夹具

本夹具使用显式 SHA256 绑定的完整生产 classes.jar 与匹配 APK 的 public assets，不改写或替换任何生产类。独立包 `cn.ngapure.adaptivenavprobe` 没有 INTERNET 权限、没有共享 UID，另用 URL handler 拒绝所有 HTTP(S) socket。MainActivity 在本夹具 UID 下运行；所有偏好、列表与截图均属于夹具，不读取真实客户端账号、Cookie、私信或社区数据，不调用社区写操作。

测量按实际 `adaptiveLayout` 根宽 220 / 390 / 720dp，局部 density=160、fontScale=1 / 1.3 / 2 运行。大于当前物理窗口宽度的用例明确跳过，建议在至少720px宽的模拟器执行完整矩阵。覆盖收藏入口多行高度、窄窗toolbar可读宽度、论坛分页单行、装饰图标焦点，社区收藏/关注分页、版块分类与工具目标、阅读器主分页目标和窄窗更多菜单中的收藏/回复入口。快捷菜单只打开、测量、关闭，不触碰签到等动作。

构建沿用仓库其他exact-product夹具：提供不可变 `PRODUCT_JAR`、`PRODUCT_APK`、对应SHA256、`SIGNING_DIR` 和新的绝对 `BUILD_DIR`，执行 `bash tests/adaptive_navigation_device/build.sh`。严格 Java8 编译使用 `-Xlint:all -Werror`。binding.json记录所有production class哈希与public asset哈希。

安装后由拥有专用模拟器的审查者执行：

```sh
adb -s "$SERIAL" shell am instrument -w \
  -e syntheticAdaptiveNavigationOnly true -e expectFixed false \
  cn.ngapure.adaptivenavprobe/cn.ngapure.adaptivenavprobe.AdaptiveNavigationProbe
```

0.20.0基线用 `expectFixed=false`，要求至少复现一个可测缺陷；修订生产包构建同一夹具用 `expectFixed=true`，对布局/焦点执行断言；全部用例收集结束后统一判定，以便失败时仍保留本轮完整截图。这个flag只切换预期，绝不改变生产视图或布局算法。

机器报告位于夹具 files/adaptive-baseline.json 或 files/adaptive-fixed.json；UiAutomation.takeScreenshot 原始PNG位于files/baseline/或files/fixed/。manifest仅为读取夹具自己的结果设debuggable，可用`run-as`导出这些文件。截图需人工查看，JSON测量不能替代本轮视觉验收。夹具不操作ADB、不安装自身、不打开真实客户端、不自动改变系统显示配置。
