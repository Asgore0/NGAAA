# 测试

测试与客户端分开。JVM 回归使用协议夹具和 Android 替身；Android helper 根据各自说明使用独立 UID、生产类绑定或专用测试设备。二者不能相互替代，也不代表所有线上功能已经验收。

## JVM 回归

准备 [构建环境](BUILDING.md)，并安装 `curl`、`ripgrep`（`rg`）和 `shasum`（Linux 通常由 Perl Digest::SHA 提供）。列出及运行一个套件：

```sh
bash scripts/test.sh --list
bash scripts/test.sh renderer
bash scripts/test.sh api
bash scripts/test.sh checkin_service
bash scripts/test.sh startup_tasks
```

统一入口设置 `JAVA_HOME`、SDK 路径、隔离的 `TEST_WORK_DIR` 和测试 JSON 库路径，避免依赖原开发工作区。测试 JSON 库为 org.json 20240303，SHA-256 固定为 `3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed`；首次缺失会从 Maven Central 下载到 `.build/test-deps/`，不进入应用运行时。

完整离线运行时，先自行取得且验证该依赖，再设置：

```sh
export TEST_JSON_JAR=/path/to/json-20240303.jar
OFFLINE=1 bash scripts/test.sh api
```

每套测试的场景、输入来源和限制位于 `tests/<suite>/README.md`。其中旧版断言数量是当时的记录；当前结果以实际执行输出为准。不要将本地 fixture 通过写成新的真人登录、短信发送、签到、发帖或私信成功。

## Android helper

这类 helper **不会由统一 JVM 入口自动安装或执行**。先阅读对应 README，设置配对的 `PRODUCT_JAR` / `PRODUCT_APK`、预期 SHA、全新的构建目录和仓库外 `SIGNING_DIR`，再用以下入口准备环境：

```sh
bash scripts/with-env.sh bash tests/session_reads_device/build.sh
```

示例依赖这些变量已经正确设置。部分历史 helper 会访问已安装客户端或真实服务；`tests/device`、`tests/auto_device` 等不能当作无网络合成测试使用。不要把个人主设备或日常账号作为默认测试目标，不提交设备 dumps、Cookie、二维码、真实账号 fixture 或私信记录。

## 性能结论

0.20.13 的冻结候选 c8 已于 2026-10-25 通过本地预设门槛：3 轮、21 对、42 次计分启动，每轮中位数至少缩短 5%，至少 15 对更快，配对差中位数的分层 bootstrap 95% 区间上界小于 0，合并 P90 不劣化超过 5%，并且全部功能检查与三轮完整性通过。实际三轮中位数缩短 8.38%、17.02%、13.02%，17 对更快；完整数值和限制见 [版本验证说明](NATIVE-0.20.13.md)，匿名样本和复算方法见 [性能记录](performance/README.md)。

这项比较保留既有登录态和 Keystore；每次安装先完成不计分预处理，再 force-stop 并清空应用缓存，以新进程测量。系统、DNS、ART 和服务端缓存没有重置。主指标从 `onCreate` 到完整板块内容首次绘制，不包含之前的接收器或进程启动工作，也不等同于普通系统回收后的启动。

本候选离线证据为 18 套、10829 项：9 套实际重跑 10069 项，9 套依赖及原日志哈希未变的 760 项复用；公开源码入口的重复执行不再次计数。这些结果是本地验证，不表示 GitHub 发布或 CI 状态。基准必须保留完整样本、固定门槛以及源码/构建产物绑定；未通过候选的结果继续保留，不能只选较快样本。历史录屏只证明相应版本的交互，不作为当前版本性能证据。
