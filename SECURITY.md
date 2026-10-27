# 安全问题报告

发现 CleanViddaTV 的安全问题（比如恢复功能、ADB 开关被滥用的可能），请通过 GitHub 的
[私密漏洞报告](https://github.com/vesaaa/CleanViddaTV/security/advisories/new) 提交，不要发公开 issue。

已知的设计取舍：国行海信 / Vidda MT9653 打开 ADB 后不需要授权确认（`ro.adb.secure=0`），
所以安装脚本结束时会关闭 ADB，桌面里的 ADB 开关会在 30 分钟后或重启后自动关闭。
