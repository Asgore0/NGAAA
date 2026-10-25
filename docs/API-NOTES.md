# NGA 接口与验证记录 · 0.3.0

> 历史／来源文档：保留原版本的实现说明和验证边界，不代表当前候选重新验收。原始设备附件未随源码公开；当前状态见[文档索引](README.md)。

验证日期：2026-10-18。0.3.0 沿用既有账号、读取与签到协议；本文的联网及账号实际结果来自 0.2.0，不表示 0.3.0 重测了全部流程。新版 UI 状态见 [0.3.0 记录](UI-0.3.0.md)。

新客户端使用 Android 原生界面与系统网络、JSON、Keystore；没有引入广告、统计或第三方运行依赖。公开开源客户端仅作为接口行为参考，没有复制其 GPL 实现。

## 已经核实的真实读取

| 功能 | 官方请求 | 结果 |
|---|---|---|
| 版块目录 | `https://bbs.nga.cn/app_api.php?__lib=home&__act=category&__output=11` | 匿名 HTTP 200；7 个分类、373 个去重版块 |
| 主题列表 | `https://bbs.nga.cn/thread.php?fid=7&page=1&__output=8` | 匿名 HTTP 200；46 个真实主题 |
| 简短帖子 | `https://bbs.nga.cn/read.php?tid=47738785&page=1&__output=8` | 匿名 HTTP 200；完整主题与正文 |
| 官方 HTML 数据包 | `https://bbs.nga.cn/read.php?tid=47738348&page=1&__output=17` | 匿名 HTTP 200；完整 20 楼、总计 2 页 |
| 现行推荐主题 | `https://bbs.nga.cn/nuke.php?__lib=app_inter&__act=recmd_topic&__output=11` | 匿名 HTTP 200；20 条主题，包含 2026 年 10 月更新 |

早期部分 `__output=11` 请求返回 403；后续测试已经验证实际可读的输出 8 与输出 17。不能用早期结果推断 NGA 匿名主题全部不可用。帖子年龄、版面与账号权限仍可能影响每个请求的结果。

最终额外运行了一次**实际 HTTP 方法集成验证**：直接在 JVM 上调用交付源码的 `NgaApi.topics("7",1,"")`、`hotTopics()`、`thread("47552220",1)`，沿用应用的 User-Agent、URL 参数、HttpURLConnection、解码和回退逻辑。仅用测试替身注入默认主机与空 Cookie，不携带账号。实际依次返回 **51 个版面主题、20 条官方推荐、20 个帖子楼层（第 1 / 3 页，fid=7）**，总耗时 379 毫秒。数量随论坛更新变化，因此与较早快照的 46 条不必相同。没有调用登录、签到或发布接口。这一检查验证真实联网方法串联成功，但没有替代 Android 真机 UI 与账号行为验收。

原生 API：`forums()` 离线读取完整公开目录；`topics(fid,page,search)` 读取版面；`thread(tid,page)` 读取帖子；`hotTopics()` 读取官方现行推荐。以上方法均可能抛出异常；联网方法必须在后台线程调用。Forum 字段为 fid/name/category；Topic 与 Post 保留服务器给出的编号和正文数据。

## 编码与长帖回退

优先 `__output=8` 并按 GB18030 解码，也兼容错误标注 UTF-8 的响应；输出 11 通常为 UTF-8。输入搜索参数使用 UTF-8 URL 编码与 `__inchst=UTF8`。旧式 `window.script_muti_get_var_store=...` 只提取 JSON 数据，绝不执行脚本。

真实长帖在旧 JSON 输出下可能被服务器截断。仅当 HTTP 200 的 JSON 无法解析时，客户端再请求一次官方输出 17。该格式返回包含 `template=read`、`htmlTitle` 和 `html` 的 JSON。客户端只提取楼层正文、用户 JSON、实际 PID/楼层与页码，不执行或渲染广告和官网脚本。权限错误、登录错误或 HTTP 403 不会触发这种重试。无法识别的格式明确提示用户使用官方网页，不拼接缺失楼层。

`data.__T` 为列表或主题元数据，`data.__R` 为楼层，`data.__U` 为用户映射。当前真实楼层字段为 `lou`，兼容旧 `index`。`__ROWS`、`__R__ROWS_PAGE` 与 `__PAGE` 描述分页。正文保留 BBCode，由原生安全渲染层显示。

现行推荐接口的 `data[0]` 含每项的 `topic`，`data[1]` 为版块名映射。显式标为 `is_market` 的卡片被跳过。旧 `/app_api.php?__lib=home&__act=recmthreads` 与 `home/appcolumns` 虽然返回 200，但实测内容停留在 2020/2021 年，因此没有用作当前首页。官方 `get_event_app` 当时只有过期占位或会员推广，没有作为活动源。

## 登录、签到和发布限制

登录由官方 HTTPS WebView 页面完成。强制真实扫码入口为 `https://bbs.nga.cn/nuke.php?__lib=login&__act=account&qrlogin`；普通登录入口末尾为 `&login`。官方页面生成会话二维码，使用手机上已登录的 NGA 官方 APP 扫描确认；不是将普通网址转成二维码。客户端不接收或保存账号密码；Cookie 仅限明确允许的 NGA 主机，使用设备 Android Keystore 的 AES-GCM 密钥加密保存。主机仅允许 `bbs.nga.cn`、`ngabbs.com`、`nga.178.com`。拒绝 HTTP、未知端口、用户信息 URL 与越界跳转，不向外站发送登录信息。

签到为登录后的 POST `/nuke.php?__lib=check_in&__act=check_in&__output=11`；统计为 `/nuke.php?__lib=check_in&__act=get_stat&__output=11`。匿名统计实测返回“你必须先登录论坛”。只有服务器明确返回签到成功或今天已签到，客户端才记录当天成功；异常、未知响应或统计数字均不会冒充成功。没有伪造客户端 checksum 或绕过验证的逻辑。

后台签到使用 JobScheduler 的 24 小时周期和 6 小时弹性窗口，重启恢复；打开应用时异步补签。日期按 Asia/Shanghai 计算。系统省电与厂商后台管理可能延后任务，不能保证精确到点。默认关闭，可由用户启用。

发帖与回复使用官方页面处理登录、权限、验证码、频率与审核。文档中的原生发布流程为准备 `/post.php?action=new|reply|quote|modify`，再提交 `step=2`、`post_content` 等字段，发布响应需输出 8；本版本没有把未验证的原生发布包装成成功。

0.2.0 阶段已在 Android 15 / API 35 arm64 模拟器实际完成用户手机 NGA APP 扫码授权，客户端接收会话并通过本机 Keystore 保存；启用自动签到后，打开应用补签取得服务器明确成功响应。JobScheduler 任务 26018 强制启动返回 `Running job`，该状态本身只证明任务可启动。手动重复签到已得到“今天已签到”，强制任务未新增后台尝试，证实同日去重；多次模拟器完整重启和覆盖安装后会话、自动签到设置保留，重启后任务状态为 `waiting`。仅 API 35 实测，24 小时自然调度与厂商省电行为尚待继续验收；发帖、回复及附件实际发布未验收，保留服务器原始错误提示。详见 [本轮模拟器记录](EMULATOR-VALIDATION.md)。

## 沿用的社区评分

游戏库展示“传统游戏 / 手机游戏 / 影视”真实社区评分，POST `/nuke.php?__lib=vote&__act=get_score_list`，表单含 `__output=14&__inchst=UTF8&page=<page>&catid=<catid>`。GET 实测返回请求错误；分类分别为 2 / 4 / 8，分页以响应 `next` 为准，空评分不伪造为 0。官方独立商业“游戏推荐”位使用另一交易相关接口，匿名响应未成功，本版本省略该位，不伪造签名或分类。

## 可复现检查与来源

0.2.0 运行 `bash tests/api/run.sh` 的历史结果为 41 项断言通过，覆盖 GB18030、错误 charset、真实响应结构、截断拒绝、20 楼 HTML 回退、作者/PID/分页、20 条官方推荐、373 个目录、主机边界，以及签到成功/已签/否定与错误响应。工程内仅发布最小脱敏样例；开发时也使用未修改的实际公开响应运行并通过相同检查。测试依赖的固定 URL、SHA-256 与复用规则见 [测试说明](../tests/api/README.md)。Android 构建独立编译通过，APK 验证由构建流程记录。

接口参考：[公开接口文档](https://github.com/AgMonk/nga-api-doc/blob/main/README_v2.md)、[Android 读取调用](https://github.com/mlzzen/open-nga/blob/main/nga_phone_base_3.0/src/main/java/sp/phone/mvp/model/ArticleListModel.kt)、[Android 签到调用](https://github.com/mlzzen/open-nga/blob/main/nga_phone_base_3.0/src/main/java/sp/phone/task/CheckInTask.java)、[WebView Cookie 解析](https://github.com/mlzzen/open-nga/blob/main/nga_phone_base_3.0/src/main/java/sp/phone/mvp/presenter/LoginPresenter.java)。原始官方文档链接为 `https://bbs.nga.cn/read.php?tid=6406100&authorid=58`，本环境访问此老主题时返回年龄/权限限制；没有将其内容当作本次已读取的证据。


正文回归增加对齐、受限字号、命名颜色与交叉闭合处理；相对附件基址使用既有白名单 `https://img.nga.cn/attachments/`。实际公开帖验证该 CDN 可达并显示图片；渲染器共 165 项检查通过。
