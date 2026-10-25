# 登录保存失败回归

运行 `bash tests/loginprefs/run.sh`，需要 JDK17 与 Android API35 SDK。

使用合成的加密串，模拟 SharedPreferences 在磁盘提交失败前已经改变内存 map 的行为。检查初次登录失败、切换账号失败、退出失败、回滚再次失败及之后明确重试成功；不读取真实账号、不调用网络或 Android Keystore。

运行时对旧加密串提供进程内读取保护并尽力回滚。它不把 `commit=false` 称为磁盘事务成功，也不承诺损坏存储下的跨进程恢复。
