# 原生社区协议离线测试

`NativeCommunity` 为提醒、私信、用户资料/主题和服务器收藏提供同步数据接口；`NativeCommunityView` 是独立的原生页面，包含载入、错误、明确空列表、分页及写操作确认。构造页面会读取对应数据；不会自动发送私信、删除收藏或打开官方网页。读取私信会话可能由服务器标记已读。

## 运行

要求 JDK 17 / Android SDK Platform 35：

```sh
JAVA_HOME=/path/to/jdk17 ANDROID_SDK_ROOT=/path/to/sdk \
  TEST_WORK_DIR=/absolute/path/to/community-tests bash tests/community/run.sh
```

全部测试使用手写合成 fixtures 和内存 Transport，没有 NGA 网络请求，不读取设备 Cookie、账号、昵称或真实私信。默认临时目录为 `.build/community-tests`，不加入 APK。test-only org.json 固定 SHA-256；已有测试依赖优先复用，缺少时从 Maven 下载。

新网络层、测试类和 View 使用 Java 8 严格编译。脚本单独编译现有 NgaApi/NgaStore 依赖时排除其既有 serial 警告；新 NativeCommunity 本身使用 `-Xlint:all -Werror`。View 严格编译由集成构建核对，不要求在纯 JVM 中实例化 Android 控件。

`CommunityTransportCancellationTest` 对真实 `NativeCommunity` 默认 HTTPS transport 安装仅能返回合成连接的 URL handler，未编排的连接一律拒绝。49 个断言、12 个场景覆盖请求前、响应头后及正文读取中取消、两个阻塞读主动断开后最新任务继续、只读 POST 取消，以及注入 transport 的晚返回。私信/用户资料、关注动态、活动页的读任务绑定各自页面 token，关闭后取消；明确的私信发送和取消收藏连同结果核对使用独立写任务，页面关闭不取消它们。测试同时确认语义为写入的 GET/POST 不受页面读 token 影响，失败、跳转或未知响应仍是 `UNCONFIRMED`、不会重试；纯 JVM 证明不代替 Android 页面关闭的设备验收。

## 协议与模型

| 功能 | 请求 |
| --- | --- |
| 提醒 | `nuke.php`，`__lib=noti` / `__act=get_all`，`time_limit` 时间游标 |
| 私信列表/会话 | `__lib=message` / `__act=message`，`act=list/read`，`page`，读取时 `mid` |
| 私信发送/回复 | 同上，`act=new/reply`，标题正文放 POST 表单；新会话指定 UID 收件人，回复指定 mid |
| 用户资料 | `__lib=ucp` / `__act=get`，`uid` 或 `username` |
| 用户主题/回复 | `thread.php`，`authorid`、`page`；主题不加 searchpost，回复显式 `searchpost=1` |
| 服务器收藏 | `thread.php`，`favor=folder`、`page`；默认收藏夹编号 1 |
| 收藏目录 | `topic_favor_v2/list_folder`；官方 `doRequest2` 的只读 POST，路由在 query，`__output=3`、`page` 等在 form；不把 POST 等同服务器写入 |
| 添加收藏 | `topic_favor_v2/add`；必须用户明确确认，未实测真实写入 |
| 删除收藏 | `topic_favor/topic_favor`，`action=del`、`tidarray`（主题或 `tid_pid`）、列表页码 |

模型字段见生产源码，另有测试/宿主集成时可用的 `Session`、`Transport` 构造器。默认生产网络限制到三个 HTTPS NGA 域名，不跟随跳转，不泄露 Cookie 给其他主机；读取和 POST 都不自动重试。Request 的 `toString()` 不含任何参数，解析错误不携带原始响应或私信内容。

`Request.parameters` 保留完整参数供协议检查，`queryParameters` / `bodyParameters` 明确描述实际传参位置，`method` 与 `mutation` 独立。收藏目录使用官方 `js_commonui.js` 的 `d.data[0]` 集合与 `d.data[1]` 更多标记，解析行的 `id/name/type/default`；公开位为 `type & 1`，不把默认位当公开位。目录的真实服务器读取仍由集成设备验证，fixture 通过不会改记为真实成功。

私信正文和未知送达后的只读核对均复用真实 `NativeRichText`：BBCode、代码、折叠与允许来源图片使用原生控件。每页 `NativeImages` 在重载时替换、close 时关闭，不携带会话 Cookie。主题/用户内链只路由到已有原生 Listener；其他 NGA 路径明确不支持，普通 HTTP(S) 外链必须确认后才触发系统 ACTION_VIEW。`CommunityLinkTest` 直接执行这套纯 Java 路由策略，不创建控件、不触发 Intent 或网络。其他图片来源仍遵守 NativeImages 的 HTTPS 允许列表。

只有服务端明确的发送/收藏成功语句才返回 `confirmed=true`。未知确认、写入 POST 跳转或中断属于 `UNCONFIRMED`，不能自动再发；只读目录 POST 的中断仍是 NETWORK。原生编辑器为每份 draft 使用真实的 `CommunityWritePolicy`：未知送达锁住本次发送，原正文保留，只提供关闭或只读刷新核对；刷新结果不自动判成功，也不解锁发送。确定拒绝才允许修正后再次确认。

`NativeMessageDrafts` 把新私信和每个回复会话的收件人、标题、正文、目标 mid、更新时间与 pending 状态写入私有 SharedPreferences。当前账号身份仅用于 `SHA-256(host + accountId)` 范围键，不保存本账号 UID、Cookie、token 或密码；收件人/目标会话编号是草稿必要字段。输入稍作停顿自动保存，窗口隐藏、关闭及旋转销毁时刷新；重新打开相应编辑器恢复。未改动的服务端预填标题或预填收件人不会因为取消而新建草稿，已有恢复数据仍保留。发请求前必须成功同步 commit pending，失败不发送。

`CommunityMessageDelivery` 在网络 worker 中处理明确确认/拒绝后的 exact-snapshot 持久收尾，再向 UI 展示；旋转或关闭后的 UI generation guard 不会丢掉已经取得的服务器终态。已授权提交中的 executor 允许 worker 正常结束，未用 UI 销毁中断它的持久更新。确认成功只移除同版本快照，明确权限等拒绝只清同版本 pending；未知结果、HTTP500 和未识别响应保守保留。进程中断恢复后保持锁定，编辑/只读核对不能自动解锁或重发。旧页面会话绑定原账号范围，不得发送新切换账号的凭据。

用户可从编辑器明确选择“清除本机草稿”，经第二次确认只删除 exact snapshot。pending 提示必须先核对、原请求可能已经送达、不会撤回或删除服务器消息；清除后关闭旧编辑器，由用户另建草稿，不自动重发。进程内版本也在删除/重建之间保持递增，旧的晚回调或清除确认不能命中新草稿。该本机清除与自动判定服务器未送达是两件不同的事。

草稿全局最多 20 份、序列化正文合计最多 4 MiB，满额时拒绝新增并保留已有项，不淘汰 pending；单份收件人/标题/正文长度分别最多 400/200/30,000 字符。损坏存储失败关闭，不覆盖成可发送新草稿。`NativeMessageDraftsTest` 用内存 SharedPreferences 执行真实存储代码，覆盖进程重建、隔离、commit 失败、未知锁、晚结果、容量、未修改取消、精确清除和损坏；没有真实请求。`CommunityMessageDeliveryTest` 覆盖完全没有 UI callback 时的明确成功/拒绝、未知保留、晚结果及本地收尾失败。状态机测试另覆盖恢复锁及只读核对不可解锁。它们不代替实际旋转、输入或真实私信发送的设备验收。

`error` 数组/对象、错误容器、HTML 验证页、未知行结构不会变成空列表。官方 output3 的精确 HTML 单脚本外壳只提取已知 JSON 赋值后解析，绝不执行；额外脚本或任意网页不能冒充此格式。无会话、验证码/安全挑战、权限、限流、网络、服务器及无效响应分别有错误码。实测脱敏结构显示私信最后一页可能没有 nextPage，此时 currentPage + rowsPerPage 仍识别为已知页，hasNext=false；不凭行数猜造下一页。对应 fixture 为手写合成结构，不含实际账号值。

Fixture 覆盖稀疏数字键/数组、分页、BBCode 数据、GB18030、错误 UTF-8 回退、原始 tab、已知 legacy 包装、明确空集合、缺失结构、错误优先、未知确认、单次 POST、参数/主机/Cookie 校验与响应大小上限。它们验证解析及请求契约，不等于已在真实账号发送或修改服务器收藏。

协议只核对参数和字段，未复制参考客户端实现。参考：

- [open-nga MessageListModel](https://github.com/mlzzen/open-nga/blob/master/nga_phone_base_3.0/src/main/java/gov/anzong/androidnga/mvvm/model/MessageListModel.java)、[MessageDetailModel](https://github.com/mlzzen/open-nga/blob/master/nga_phone_base_3.0/src/main/java/sp/phone/mvp/model/MessageDetailModel.java)、[ProfileActivity](https://github.com/mlzzen/open-nga/blob/master/nga_phone_base_3.0/src/main/java/gov/anzong/androidnga/activity/ProfileActivity.java)：可互操作请求的第一手源码证据，非 NGA 官方支持承诺。
- [NGA 原协议说明主题](https://bbs.nga.cn/read.php?authorid=58&tid=6406100)：本轮匿名访问被拒绝，未据此声称完成当前账号认证测试。
- [官方当前 js_commonui.js](https://img4.nga.cn/common_res/js_commonui.js)：本轮匿名取得原文；`doRequest2` 参数说明、`selectForum.genFav` 和 `commonui.favor` 证实目录的 POST、output3、集合和分页标记。仅参考协议，没有复制实现。原始证据留在任务研究目录，不加入运行时 APK。
- [协议整理补充](https://github.com/AgMonk/nga-api-doc/blob/main/README_v2.md)：收藏目录等参数交叉参考，未保证服务器当前开放所有版本；未知/拒绝状态明确保留。

UI 仅经用户点击及完整二次确认调用写接口；本任务的离线测试没有执行真实发送、发帖或删除。
