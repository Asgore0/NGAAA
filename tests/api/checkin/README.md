# 签到账号切换竞态离线测试

运行 `bash tests/api/checkin/run.sh`。需要 JDK17、Android API35 SDK，以及本地已校验的 test-only org.json JAR（可用 `CHECKIN_JSON_JAR` 指定）；脚本不会联网下载依赖。

编译并执行真实 `NgaApi.java` 和真实 `LoginPreferences.java`。测试专用 `NgaStore` 只替代 Android Keystore、时钟和 Context 边界；账号身份比较、替换及条件缓存写入调用真实 `LoginPreferences`。测试不验证 Android Keystore、JobScheduler 或生产 `NgaStore` 的加解密实现。

独立 JVM 中的 `URLStreamHandlerFactory` 接管所有 HTTPS 连接，只返回已排队的 `HttpURLConnection` 假连接；未脚本化的连接立即失败，没有真实网络回退。Latch 确认 A 请求已经发出并挂起，再用同一个真实登录保护组件切到 B／注销／续期同 UID，之后释放成功或异常响应。另在捕获会话之后、记录尝试之后安排确定性切换，验证发出的 Cookie 与 host 确实来自同一捕获会话。

覆盖旧账号成功、网络异常、HTTP 异常不污染 B 缓存，B 随后可正常签到，同 UID 更换 token／Cookie 家族可接受其结果，注销后晚结果不恢复签到状态，无会话／发送前身份变化不发请求，以及明确成功、今天已签到、服务器拒绝、统计文本和默认入口。全部 Cookie、UID、响应和时间均为手写合成数据；不读取设备、账号、真实私有文件或应用存储。

Fixture 文件有 SHA-256 清单；测试 JAR 和编译 class 均不进入 APK 运行时。测试目录只有源文件、脚本、说明及合成 JSON。

The foreground scheduler also passes an immutable expected-session ticket into the real API. Tests reject pre-entry account, host and same-UID cookie mismatches without a request or attempt mutation. Forced switches immediately after validation and after attempt recording prove that a permitted request keeps its original host/cookie, cannot become a request for the new account, and retains the existing account-scoped result fence. An already started original-UID request may complete after same-UID token renewal; this preserves the established completion semantics. No lock is held during HTTP and no failed/unknown delivery is automatically replayed.
