# 评分接口离线检查

在项目目录运行：

```sh
bash tests/ratings/run.sh
```

需要 JDK 17 和 Android SDK API 35。脚本支持 `JAVA_HOME`、`ANDROID_SDK_ROOT`，默认探测本机 Homebrew 工具链。通过时输出 `PASS NgaRatings: 15 offline public-response checks`。

测试采用 2026-10-18 对官方 `nuke.php?__lib=vote&__act=get_score_list` 匿名 POST 得到的精简响应，保留真实分类、主题 ID、游戏名称、评分、人数、图片 URL、类型及平台。每页只截取少数项目，删除网站链接等无关字段；fixture 没有 Cookie、用户资料或个人凭据。测试不访问 NGA、不登录、不签到、不评分，也不下载图片。

15 项检查覆盖真实字段与分类、平台/类型标签、第二页与继续加载、手机游戏/影视缺省字段、特殊推荐分类误映射、服务器错误、错误结构、HTML 响应，以及 8 MiB 读取上限。它验证解析契约，不能替代模拟器网络和界面实测。

`RatingsTransportCancellationTest` 另以 Java 8、`-Xlint:all -Werror` 编译真实 `NgaRatings`、`ScreenReadTasks` 与 `RequestCancellation`。28 个断言、6 个场景通过只返回合成连接的 URL handler 验证请求前、响应头后、正文中取消，以及离开评分页主动断开不响应 interrupt 的读连接、释放 worker 后最新请求可在两秒内执行。正常空页与拒绝跳转仍保持原行为；只读评分 POST 不会被误当作评分提交。仅登录存储的 Keystore 边界使用现有合成 `NgaStore`，没有真实网络、设备账号或社区写入。

测试缓存默认写入 `.build/ratings-tests/`，可通过 `TEST_WORK_DIR` 更换。首次运行优先复用 `.build/api-parser-tests/json-20240303.jar`；不存在时从 Maven Central 下载固定测试依赖 `org.json:json:20240303`，并校验 SHA-256：

`3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed`

该 JAR 仅用于 JVM 测试，保存在 `.build/`，不进入 APK。应用实际使用 Android 系统 `org.json`。
