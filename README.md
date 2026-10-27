# CleanViddaTV

**国行海信 / Vidda 电视的清爽桌面**：停用预装广告、数据上报和推送，把原厂桌面"聚好看"换成仿 tvOS 的轻量桌面，开机直接进入，不需要 root，随时可以一键恢复。

> CleanViddaTV 是个人开源项目，与海信（Hisense）、Vidda、聚好看以及 CleanYourTV 均无隶属关系。"海信""Vidda""聚好看""VIDAA"是其各自所有者的商标。

## 来源与致谢

CleanViddaTV 基于 **[CleanYourTV](https://github.com/Gathin23/cleanyourtv)**（作者 [Gathin](https://github.com/Gathin23)，MIT 协议）改造而来，取自其主分支 2026-10-27 的快照（提交 [`5773336`](https://github.com/Gathin23/cleanyourtv/tree/57733360177173c880a023380a864114a03feb25)，v3.0.0 之后）。桌面程序、清理与还原工具、安全检查都出自 CleanYourTV 和它的两百多位贡献者；海信 / Vidda 的机型清单与设备笔记也来自 CleanYourTV 社区的实测。谢谢你们。

海信私有系统的一些行为（Home 键、信号源面板、开机拦截）参考了 [aerial-tv](https://github.com/pengchujin/aerial-tv) 的[海信 VIDAA 笔记](https://github.com/pengchujin/aerial-tv/blob/main/docs/devices/hisense-vidaa.md)。

CleanViddaTV 在此基础上做的事：只针对国行海信 / Vidda、开机直接进入新桌面、接管遥控器 Home 键、桌面设置里的 ADB 开关、不用电脑的一键恢复原厂桌面、在线更新、替代系统缺失的文件选择器（其他应用"选择文件 / 文件夹"时用），以及一步完成的安装脚本。

## 支持的机型

| 机型 | 系统 | 状态 |
|---|---|---|
| 海信 65V1N-X（`VIDAA_TV`，MediaTek MT9653，固件 02.02.70.01） | Android 11，聚好看 JuUI | **实机验证**：安装、开机直接进桌面、冷启动、Home 键、信号源切 HDMI、一键恢复、在线更新、文件选择器 |
| 其他 Vidda 电视（`VIDAA_TV`，MediaTek MT9653） | Android 11，聚好看 JuUI | 同一平台；清单经 CleanYourTV 社区实测 |
| 海信 65D62QH（同平台） | Android 11 | 同上 |
| 海信 E5Q Pro（同平台） | Android 11 | 同上 |

**不支持**：海外版海信（VIDAA OS 不是 Android，没有 ADB）、Vidda C1S 等 Android 9 的投影、Android 8 以下的老机型。安装脚本会先检查型号，不符合就什么都不改。

## 装完之后

- **仿 tvOS 的桌面**：一行 5 个应用卡片浮在壁纸上，选中的卡片放大、抬起并带投影；顶部显示选中应用的名字。时钟、日期、信号源、网络和设置在顶栏。
- **内置壁纸**：6 张风景照每小时轮换，也可以换成抽象光影或固定一张（设置 → 壁纸）。
- **文件选择器**：这一代海信系统里根本没有"选择文件"界面，很多应用（比如 Lemuroid 选择游戏文件夹）点了没反应。CleanViddaTV 补上了它：其他应用要选文件或文件夹时，会弹出一个用遥控器操作的选择界面，能浏览内部存储和 U 盘。
- **在线更新**：设置 → 更新 → 检查更新，先从国内 CDN 下载，连不上再走 GitHub；校验签名后由系统安装器确认安装，设置和布局都保留。桌面每天也会自己检查一次，有新版本时"设置"按钮上会出现小红点。
- **开机直接进 CleanViddaTV**。系统还在启动时先显示黑屏过渡，解锁后立刻进入新桌面。
- **遥控器 Home 键**回到 CleanViddaTV（这台电视的 Home 键是扫描码 102，原本交给聚好看处理）。
- **信号源**：桌面上的"信号源"按钮打开海信自己的信号源面板，可以切换 HDMI。
- **清理掉的**：聚好看的推荐栏和广告、数据上报、推送、酒店/卖场演示和预装内容。完整清单见 [`examples/vidda-mt9653/debloat.txt`](examples/vidda-mt9653/debloat.txt)，每一行都写了原因。
- **保留的**：系统更新、投屏、输入法、蓝牙、遥控器、信号源相关服务（`com.hisense.tv.tiletransition` 不能停用，否则切 HDMI 会悄悄失效）。

**代价**：语音助手和遥控器的麦克风键会失效。海信的第二个原厂桌面 `com.keylab.speech.view.vidaa` 开机时会盖在新桌面上面，而且只能整包停用，语音界面就在这个包里。恢复原厂桌面后语音会回来。

## 安装

需要一台和电视在同一局域网的电脑（Linux、macOS，或 Windows 的 Git Bash），以及 Python 3。

### 第一步：提前打开电视的 ADB

安装要通过 ADB（电视的调试接口）完成，**请先把它打开**。打开"USB 调试"后，5555 端口的网络 ADB 也会一起打开，不需要配对，也不会弹出确认。两种方法：

- **按电视自己的菜单打开**：国行海信 / Vidda 的开发者选项藏在工厂菜单后面，步骤见 [`examples/hisense-e5q-pro/README.md`](examples/hisense-e5q-pro/README.md)（需要一个 USB 鼠标）。
- **让 AI 助手带着你开**：把电视型号（设置 → 关于）和设置界面的照片发给 Claude 这类 AI 助手，让它一步步告诉你按哪里。不同固件的菜单位置不一样，AI 看着截图更容易找对。

> ⚠️ 网上有一些号称"一键开启 ADB"的 APK，往往冒用华为、谷歌等厂商的包名，用公开的测试密钥签名，并申请大量系统权限。它们能完全控制电视，来源和代码都无法核实，**本项目不提供、也不推荐**。

装好以后就不再需要电脑了：以后要用 ADB，在 CleanViddaTV 的设置 → 维护里打开（30 分钟后自动关闭）。

### 第二步：在电脑上运行安装脚本

1. **运行**：

   ```bash
   git clone https://github.com/vesaaa/cleanviddatv
   cd cleanviddatv
   python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
   TV_HOST=<电视的 IP> ./install-vidda.sh
   ```

   默认会在本机用 Gradle 构建桌面（需要 JDK 17 和 Android SDK）。不想装开发环境的话，从 [发布页](https://github.com/vesaaa/cleanviddatv/releases/latest) 下载 `CleanViddaTV-<版本>.apk`，用 `APK_FILE=CleanViddaTV-<版本>.apk TV_HOST=<电视的 IP> ./install-vidda.sh`。发布页的 APK 都用同一把正式密钥签名，以后可以直接在线更新。

2. **重启一次电视**，确认开机直接进入 CleanViddaTV、Home 键正常。

安装脚本会依次：检查型号 → 记录当前状态 → 按清单停用（先备份，并有防止"没有桌面可用"的保护）→ 安装并启动新桌面，确认它成为默认桌面后才停用聚好看 → 开通遥控器按键服务、文件选择器和在线更新所需的权限 → 把恢复所需的记录写到电视上 → **关闭 ADB**。以后需要 ADB 时，在桌面设置里打开。

## 更新

装好以后不用再碰电脑：设置 → 更新 → **检查更新**。也可以打开 **接收测试版**，提前用上 beta 版本。

## 恢复原厂桌面

1. **在电视上一键恢复（推荐）**：CleanViddaTV 里，在"设置"按钮上长按确认键 → **CleanViddaTV 设置** → **维护** → **恢复原厂桌面…**。它会临时打开 ADB，以系统命令行的身份重新启用安装时停用的所有内容（聚好看最先），把默认桌面设回聚好看，再关闭 ADB。完成后可以顺手卸载 CleanViddaTV。
2. **用电脑恢复**：桌面本身出问题、进不了设置时，用 `TV_HOST=<电视的 IP> tools/restore.sh examples/vidda-mt9653/debloat.txt com.jamdeo.tv.vod`（需要电视的 ADB 是打开的）。
3. **恢复出厂设置**：最后的兜底。停用状态会被清掉，一切回到原样，但电视上的数据和应用也会被清空。

<details>
<summary><b>桌面设置与默认桌面</b></summary>

在桌面的"设置"按钮上长按确认键，选 **CleanViddaTV 设置**，从上到下是：

- **调整应用顺序**（只在 `launcher_settings_button` 打开时出现）。
- **每行应用数量** 4、5、6 或 7；**程序坞下方行数** 0 到 3；**程序坞透明度** 100%、70%、50% 或 30%。
- **壁纸**：内置的风景照或抽象光影（整组每小时轮换，或单选一张）、主题自带的图片、无，或跟随主题；最后一项"图片来源与授权"列出照片的作者和许可。
- **动态效果** 开或关；打开后还可以开 **高级动态效果**（更占内存）。
- **开机时打开**（只在 `open_on_start_choice` 打开时出现）：开机后先打开的应用。
- **跟随主题（重置电视端设置）**：清除上面所有在电视上做的选择。
- **选择默认桌面** 打开系统的默认桌面选择页；**电视系统设置** 打开电视自己的设置。
- **更新**：
  - **检查更新**：先从 CDN（download.aikit.stream）查，连不上再查 GitHub；有新版本时下载、校验签名，再由系统安装器确认安装，设置和布局都保留。桌面每天也会自己查一次，有新版本时"设置"按钮上会出现小红点。
  - **接收测试版**：开着时也会收到 beta 版本；装着 beta 时默认打开。
- **维护**：
  - **ADB 调试**：打开前会提醒风险——打开期间同一网络里的任何设备都能直接控制电视，而且电视上没有确认提示。30 分钟后、或电视重启后自动关闭。行上会显示 `IP:5555`，方便告诉帮你排查的人怎么连。
  - **恢复原厂桌面…**：见上一节。

</details>

## 安全

- 这一代海信打开 ADB 后**不需要授权确认**（`ro.adb.secure=0`）：同一网络里的任何设备都能拿到命令行。所以安装结束会关闭 ADB，桌面里的开关也会自动关闭。只在可信的家庭网络里使用。
- 只停用，不卸载，不 root，不刷机。所有改动都能用上面的方法还原。

## 开发

- 桌面：`launcher/`，纯 Java，`minSdk` 26。构建：`cd launcher && ./gradlew testReleaseUnitTest lintRelease assembleRelease`。
- 测试：`tools/venv/bin/python -m unittest discover -s tests`（包括用模拟电视测试 `install-vidda.sh`）。
- 海信相关的配置都在 `launcher/app/src/main/res/values/config.xml`：开机进桌面 `power_on_home_always`、Home 键 `home_key_scancodes`、信号源 `source_panel_service`。

## 协议

MIT，见 [`LICENSE`](LICENSE)：保留 CleanYourTV 原作者的版权声明，并加上本项目的。桌面使用的 Outfit 字体为 SIL Open Font License 1.1，见 [`launcher/FONT-LICENSE-OFL.txt`](launcher/FONT-LICENSE-OFL.txt)。内置的风景照来自 Wikimedia Commons，按各自作者的 CC BY-SA / CC BY 许可分发，作者和许可见 [`launcher/app/src/main/assets/wallpapers/CREDITS.md`](launcher/app/src/main/assets/wallpapers/CREDITS.md)，电视上在设置 → 壁纸 → 图片来源与授权里也能看到。
