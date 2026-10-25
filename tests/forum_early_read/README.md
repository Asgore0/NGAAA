# 板块请求与 UI 构造重叠

`TEST_WORK_DIR=/absolute/scratch bash tests/forum_early_read/run.sh` 编译实际 `ForumReadDelivery`、`ScreenReadTasks` 和 `RequestCancellation`，使用 latch 验证两种完成顺序、一次交付、取消/引用释放、原读取 worker 的取消作用域、回调不持 monitor 和完成/绑定并发。无真实 HTTP、账户、Android UI 或墙钟性能断言。

Main 在 `go` 取消旧任务、更新 screen/generation 并清理旧原生页后，提前创建新的 per-navigation `ForumReadLaunch`。合法路由且没有有效 back snapshot 时，真实 session/cache/network 读取先进入现有 ScreenReadTasks，再构造颜色、toolbar 与 body。路由、parent、页码和 scope 在 launch 冻结。

`forum` 绑定同一次读取结果，不另发 HTTP。通常网络晚于绑定完成，交付直接留在原读取线程及 RequestCancellation scope，再执行原有后台过滤与 UI owner/scope/revision guards。若响应早于绑定，bind 只将过滤工作排入 ScreenReadTasks，绝不在 UI 等待结果或构造过滤器。离开页面会取消网络与 delivery，已排队但未消费的结果释放引用。back snapshot 保持直接渲染、零网络；显式刷新仍先失效 cache；cache 仅在 UI 验收通过并渲染后写入。

queued/worker/session/cache/network/response 诊断在实际发生位置记录，onCreate 起点不变。纯 JVM 无法证明 Android 的实际 go 调用位置或首帧改善；需要最终 APK 的缓存、快速离页、账号变化及三轮真实性能设备验证。总耗时是否改善按预先登记协议判断，不能用阶段前移代替首屏结果。
