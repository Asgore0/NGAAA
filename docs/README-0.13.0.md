# NGA 纯净版 · Android 0.13.0

> 历史／来源文档：保留原版本的实现说明和验证边界，不代表当前候选重新验收。原始设备附件未随源码公开；当前状态见[文档索引](README.md)。

原生 Android NGA 社区客户端，最低 Android 8.0 / API26，目标 API35。阅读、搜索、编辑、私信、资料和收藏使用原生界面；仅短信风控需要时加载官方腾讯验证控件。无广告 SDK、开发者统计或自建中转服务器。

本版针对0.12.0实装反馈，改善胶囊快速反向、拉伸融合和松手衔接，并补齐版块图标、用户头像格式与加载生命周期。详见 [0.13.0说明](NATIVE-0.13.0.md)。17组离线回归、44项设备检查通过，约56.6秒原始模拟器录屏已解码并完成抽样目视审核。

## 安装与使用

- NgaPure-0.13.0.apk（历史附件未收入源码仓库），6051851字节（约6.05 MB），versionCode14，包名 `cn.ngapure.app`，沿用0.12.0签名，已覆盖安装并核验设备字节与交付APK一致。SHA256为 `6ecc1685703ed1f9111cd737f6dfc27a58149e4c0a2b7b3d288afb6087b6a339`。完整制品与SHA见 发布元数据（历史附件未收入源码仓库）。
- 胶囊拖动保持当前几何与速度，缩短反向预测和尾部融合；松手直接承接当前运动，远距离点按保留短意图渐变。同一模拟器、驱动和几何下，快速反向平均中心偏差较0.12.0下降约35–48%，慢拖下降约61–69%；这是原生帧轨迹对照，不是物理触控延迟或帧率承诺。页面提交静默窗口由72 ms缩短至32 ms，胶囊持续独立响应。
- 373个原有唯一版块ID中，352个有已核验官方原图，比旧映射新增255个；图片优先本机读取。536张原PNG、约5.56 MB已全部在Android分批解码，无网络请求。其中184张为合集资产；合集独立路由与列表展示尚未接入，不能计作当前可见版块覆盖。其余缺图版块保留文字占位。
- 作者头像补充官方候选格式；元数据缺失时按当前登录态查询同站作者头像。设备使用全新解析器与图片缓存，3名已知有真头像作者从空元数据绑定后均补齐并解码真实照片，未计入默认图。仅可见头像加载，离屏取消，繁忙重试，同作者共享缓存；账号或站点变化取消旧查询和缓存。仍无可用个人头像时显示并标记官方通用头盔“默认头像”，不保证所有作者均有个人头像。
- 帖子操作保持一行赞数、引用回复、更多；复制和本人编辑位于更多菜单。明确正赞显示“点赞”，仅净值显示“净赞”，缺失显示“—”。
- “我的 → 登录管理”选择扫码、密码或手机验证码；扫码需在官方APP确认授权，未自动跳转时点“确认登录”。“设置 → 自动签到”启用后台日任务与打开补签，只有服务器明确确认才记录当天完成。

本轮保留认证、账号存储和签到实现，没有新扫码、提交真实密码、发送短信或执行内容写入。保留会话与当天签到记录不等于新登录或新签到。窗口适配基于可用尺寸与系统栏边距，未读取物理铰链间隙。

## 构建与测试

准备 JDK17、Android SDK Platform35、Build Tools35.0.0 和 `zip`：

```sh
export JAVA_HOME="/path/to/jdk17"
export ANDROID_SDK_ROOT="/path/to/android-sdk"
./build-local.sh
./test-renderer.sh
bash tests/api/run.sh
CHECKIN_JSON_JAR=/path/to/verified-org-json.jar bash tests/api/checkin/run.sh
bash tests/avatar/run.sh
bash tests/boardicons/run.sh
bash tests/nativeauth/run.sh
bash tests/credentialauth/run.sh
bash tests/community/run.sh
bash tests/navigation/run.sh
bash tests/loginprefs/run.sh
bash tests/nativecontent/run.sh
bash tests/posting/run.sh
bash tests/motion/run.sh
bash tests/ratings/run.sh
bash tests/imageexport/run.sh
bash tests/reliability/run.sh
bash tests/screenreads/run.sh
```

构建使用 `aapt2 → javac → d8 → zipalign → apksigner`，缓存位于 `.build/`。覆盖升级需要原签名；私钥不随源码交付，也可自行签名后安装。设备探针按 [设备说明](../tests/device/README.md) 单独构建，不进入生产APK。17组离线脚本退出码均为0，统一输入哈希已核验；离线样本不能代替真实授权、短信或发布验收。

二维码使用 Nayuki QR Code generator（MIT），见 [第三方声明](../THIRD-PARTY-NOTICES.md)。本版内置核验过的官方公开版块PNG及通用默认头像图形，不包含官方APK或反编译实现代码。图片、内容与NGA名称归各自权利人所有，本项目与NGA官方无隶属关系。

## 验证资料

- [本版说明](NATIVE-0.13.0.md)、[验证总览](VALIDATION.md)、44项设备记录（历史附件未收入源码仓库）、[设备检查](DEVICE-TEST-CHECKLIST.md)。
- 原始模拟器录屏（历史附件未收入源码仓库）56.628秒，720×1600；抽样目视包括8个时间线帧、30个底栏裁切、2个补充整帧和8张公开截图，不是逐帧审核或FPS认证。源码ZIP（历史附件未收入源码仓库）、发布验证（历史附件未收入源码仓库）。
- [0.12.0说明](NATIVE-0.12.0.md)、[0.12.0验证](VALIDATION-0.12.0.md)、0.12.0设备记录（历史附件未收入源码仓库）、0.12.0录屏（历史附件未收入源码仓库）。
- [0.11.0验证](VALIDATION-0.11.0.md)、[0.10.0验证](VALIDATION-0.10.0.md)、[0.9.1登录与签到](NATIVE-0.9.1.md)。
- [官方APK对照](OFFICIAL-9.9.69.md)、[头像/赞数字段](../tests/avatar/README.md)、[接口说明](API-NOTES.md)。
