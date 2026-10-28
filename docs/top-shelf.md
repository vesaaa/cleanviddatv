# 顶部展示区接口（Top Shelf）

CleanViddaTV 的焦点停在程序坞里某个应用上时，上方大图区（hero）可以显示这个应用提供的内容卡片，类似 tvOS 的 Top Shelf。
第一个接入的是 VsTV：显示收藏和最近观看的频道、正在播出的节目和进度，按确认键直接切到这个台。

接口是一个**只读的 ContentProvider**，由应用提供，桌面来读。桌面不联网、不解析直播源，所有内容都由应用自己准备好。
任何应用都可以按这个接口接入，桌面不认包名。

版本：1（2026-10-28）

## 1. 应用如何声明

在 `AndroidManifest.xml` 里声明一个带固定 action 的 provider：

```xml
<provider
    android:name=".shelf.LauncherShelfProvider"
    android:authorities="${applicationId}.shelf"
    android:exported="true">
    <intent-filter>
        <action android:name="io.github.vesaaa.cleanviddatv.action.SHELF" />
    </intent-filter>
    <!-- 可选：卡片区的标题，没有时桌面用应用名 -->
    <meta-data android:name="io.github.vesaaa.cleanviddatv.shelf.title" android:value="@string/shelf_title" />
</provider>
```

桌面通过 `PackageManager.queryIntentContentProviders(new Intent(ACTION_SHELF), 0)` 找到它，并按包名对应到程序坞里的图标。
authority 用 `${applicationId}.shelf`，同一个应用的不同包名（例如 VsTV 的两个 flavor）各自有一个，互不冲突。

**调用方限制（建议）**：provider 只回答桌面。在 `query()` / `openFile()` 里检查 `getCallingPackage()`，只允许
`io.github.vesaaa.cleanviddatv`，其他调用方返回空结果。内容只是频道名和节目名，不算敏感，但没必要给所有应用看。

## 2. 查询卡片

```
content://<authority>/items
```

`query(uri, projection, selection, selectionArgs, sortOrder)`：桌面只传 uri，其余参数都是 null。
按应该显示的顺序返回，**最多 12 行**，多余的桌面会丢掉。返回 0 行表示没有内容，桌面就照旧显示大字应用名。

| 列名 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `_id` | long | 是 | 稳定的行 ID，同一频道每次查询相同 |
| `title` | string | 是 | 卡片主标题，例如 `CCTV-1 综合` |
| `subtitle` | string | 否 | 第二行，例如正在播出的 `新闻联播`；没有时为 null |
| `image_uri` | string | 否 | 卡片图片：台标或海报。必须是本 provider 的 `content://<authority>/image/...`（见第 3 节），**不要用 http 地址** |
| `image_aspect` | string | 否 | `square`（台标，默认）或 `wide`（16:9 海报） |
| `progress` | int | 否 | 当前节目进度 0–1000；没有节目单时为 -1 |
| `start_ms` / `end_ms` | long | 否 | 当前节目的开始和结束时间（毫秒时间戳），没有时为 0；桌面用来显示 `19:00–19:30` |
| `badge` | string | 否 | 角标，最多 4 个字，例如 `直播`、`回看` |
| `section` | string | 否 | 分组：`recent`（最近观看）或 `favorite`（收藏）。桌面在两组之间加一个小间隔 |
| `intent_uri` | string | 是 | 按确认键时启动的 Intent，用 `intent.toUri(Intent.URI_INTENT_SCHEME)` 生成，桌面用 `Intent.parseUri` 还原后 `startActivity` |
| `preview_uri` | string | 否 | **第二阶段**用：可直接播放的预览流地址（HLS）。现在先留空 |

桌面会把 `intent_uri` 里解析出来的 Intent 的包名强制设成这个 provider 所在的应用，所以只能打开应用自己的界面。

## 3. 图片

```
content://<authority>/image/<任意 ID>
```

`openFile(uri, "r")` 返回 PNG / JPEG / WebP 文件。台标建议 256×256 以内，海报建议 640×360 以内，单张不超过 200 KB。
应用自己负责下载和缓存台标（VsTV 已有 `logoUrl`），桌面只读本地文件，读不到时显示频道名首字。

## 4. 内容变化

内容变了（换台、收藏增删、节目切换）时调用：

```kotlin
context.contentResolver.notifyChange(Uri.parse("content://$authority/items"), null)
```

桌面在程序坞获得焦点时查询一次，在显示期间注册 ContentObserver，收到通知后重新查询。
节目进度不需要每秒通知：桌面按 `start_ms` / `end_ms` 自己推算进度，节目切换时再通知一次即可。

## 5. 性能要求

- `query()` 在 50 ms 内返回，不在里面联网、不解析直播源。直接读内存或本地数据库里已有的数据。
- 应用没在运行时也要能回答：provider 会把应用进程拉起来，所以 `onCreate()` 里不要做重活。

## 6. VsTV 的建议实现

- 卡片：最近观看的频道（最多 4 个，按时间倒序）+ 收藏的频道（其余位置）。同一个频道只出现一次，以最近观看为准。
- 最近观看：VsTV 现在只记了最后一个频道（`iptvLastIptvIdx`），需要新增一个小列表，按 `IptvFavoriteEntry.stableKey()` 去重，保留 10 个。
- 打开频道：新增一个显式入口，例如 action `com.vesaa.mytv.action.PLAY_CHANNEL`，extra `channel_key` = `stableKey()`，
  找不到这个频道时退回打开 VsTV 首页。`intent_uri` 就用这个 Intent 生成。
- `subtitle` / `progress` / `start_ms` / `end_ms` 从已加载的节目单（EPG）里取，没有节目单的频道留空、`progress` 为 -1。
- 台标：用已有的 `logoUrl` 下载缓存后由 `openFile` 提供。

## 7. 第二阶段（暂不做）

- `preview_uri`：焦点在卡片上停留几秒后，桌面在大图区静音播放这个频道的直播画面。
  需要先在海信电视上确认系统播放器对直播源的兼容性和内存占用。

## 8. 桌面这边要做的事（CleanViddaTV）

- `<queries>` 里声明 `io.github.vesaaa.cleanviddatv.action.SHELF` 的 intent，Android 11 起才能看到别的应用的 provider。
- 程序坞里的应用获得焦点时，如果它有 shelf provider，大图区换成卡片行；没有内容时保持现在的大字应用名。
- 卡片行可以用方向键上移进入，左右切换，确认打开，返回键回到程序坞。
