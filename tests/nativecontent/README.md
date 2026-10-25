# 原生内容边界回归

项目根目录运行 `bash tests/nativecontent/run.sh`。需要 JDK17 和 Android SDK35，可设置 `JAVA_HOME`、`ANDROID_SDK_ROOT`、`TEST_WORK_DIR`。

测试执行真正用于原生渲染的纯 Java 内容分块、实体解码和图片采样规则，覆盖代码空白、折叠嵌套、引用与代码/折叠混排、32 层引用、原生对齐转换、字面标签、巨幅尺寸与图片来源边界。fixture 是合成帖子，不含账号数据；不联网、不访问设备。测试也严格编译三项 Android 组件。

纯 JVM 断言不替代设备上的字体、图片动画、折叠交互和缩放验证。GIF/WebP 原生动画需要 API28；API26/27显示首帧。
