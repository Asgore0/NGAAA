# 收藏前台准备与自动重试生命周期

运行：`TEST_WORK_DIR=/absolute/scratch bash tests/favorite_startup/run.sh`。仅本地 JVM，编译实际 `ForumFavoritesCoordinator`、`FavoriteForegroundTokens`、`StartupTaskQueue` 和 `ForumFavoriteAutoPolicy`；存储、Android Handler/ConnectivityManager 与云端边界是测试替身，无真实凭据、HTTP、ADB 或 AndroidKeystore。当前 12 项纯 token 断言与 123 项协调器断言通过。断言含任务 drain 与 latch 前置条件，数量不代表独立场景数。

生产契约：

- `onForeground` 同步登记 token 的新 generation，再提交有界前台准备队列。队列读取全部仍活跃 token，合并快速触发不会丢另一 Activity。捕获前后重验 generation；停止/同 token 再进入不能复活旧票据。
- 自动重试使用独立有界队列，主线程 Handler 只负责投递，不能让 retry 与 foreground 两类 pending 工作互相覆盖。每个队列最多一个执行任务和一个最新触发。
- 账户捕获、pending 读取不持 UI 状态锁；需要串行捕获时，锁顺序仅为 `captureLock` → 状态锁。`onBackground` 不取 captureLock，因此凭据读取、HTTP finally 或网络恢复卡住时，停止仍可撤销 token、取消定时器和释放网络回调。
- 自动 foreground/retry/network/finally 只捕获身份，不切换全局收藏投影。Main 的初始化/账户导航与同步显式 `schedule`、`requestRefresh` 负责激活投影。旧 A 捕获晚到不能覆盖已由 Main 激活的 B；HTTP 保留 exact-session guards，store merge/confirm 保留显式 account scope。显式收藏/刷新不经前台队列，仍保持原有后台执行语义。

确定性场景涵盖 capture/pending 阻塞期间退出、1000 次快速前台触发、多 Activity 替换、同 token stop/resume、捕获失败后恢复、排队与在途换号、旧捕获不能回退投影、finally/网络恢复期间退出、后台显式收藏与刷新、自动重试退出和重试与新前台触发交错。所有等待都有超时；测试不通过墙钟耗时比较声称性能改善。

平台边界：实际注册使用 `registerDefaultNetworkCallback(NetworkCallback)`，不要求在主线程调用；回调由默认内部 Handler 分发。JVM 替身验证注册/释放次数与线程归属，不替代真机回调验证。参见 [ConnectivityManager 文档](https://developer.android.com/reference/android/net/ConnectivityManager) 与 [网络状态文档](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)。
