# 顶部展示区接口（Top Shelf）

CleanViddaTV 的焦点停在程序坞里某个应用上时，上方大图区（hero）可以显示这个应用提供的内容卡片，类似 tvOS 的 Top Shelf。
第一个接入的是 VsTV：显示收藏和最近观看的频道、正在播出的节目和进度，按确认键直接切到这个台。

接口是一个**只读的 ContentProvider**，由应用提供，桌面来读。桌面不联网、不解析直播源，所有内容都由应用自己准备好。
任何应用都可以按这个接口接入，桌面不认包名。

版本：1.1（2026-10-28）。1.1 新增可选列 `preview_image_uri`，旧的应用不给这一列也照常工作。

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
| `preview_image_uri` | string | 否 | 频道画面截图（16:9），同样必须是本 provider 的 `content://<authority>/image/...`。平时卡片显示 `image_uri`（台标），**选中时淡入截图**，台标缩到左上角。只给仍然有效的截图（见第 3.1 节），没有就留空 |
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
节目进度不需要每秒通知：桌面按 `start_ms` / `end_ms` 自己推算进度。

应用没在运行时没人发通知，所以桌面在卡片显示期间还会**自己重新查询**：取所有卡片里最早的 `end_ms`，到点后再等 5 秒重新 query 一次。
应用在 `query()` 里按当前时间算出正在播的节目，重新查询就拿到新节目。

应用也可以不返回某些内容（例如用户关掉了「在桌面显示最近观看」，只返回收藏或返回 0 行），桌面照常处理。

> VsTV 的做法（供参考）：维护一个小的 shelf 快照文件，包含这些频道接下来约 24 小时的节目，在换台、改收藏、刷新节目单时更新；
> 台标预先缩到 256px 存成单独文件。`query()` 只读这个快照，不碰完整的节目单缓存。

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

## 3.1 频道截图（`preview_image_uri`）

平时一排卡片只显示台标，保持整齐；选中某张卡片时，卡片淡入这个频道的截图，左上角保留一个小台标，类似 tvOS 选中后展开内容。

- **怎么来**：应用在换台或退出播放时，从播放器截一帧（缩到 640×360 以内，WebP，单张不超过 200 KB），记下截图时间和当时在播节目的开始时间。
- **只给有效的截图**，过期由应用判断，桌面不判断：
  - 有节目单：截图时的节目和现在在播的是**同一个节目**（开始时间相同），并且截图**不超过 2 小时**。节目一换或超过 2 小时就不给。
  - 没有节目单：截图**不超过 30 分钟**。
- **截图更新时换一个地址**（例如 `content://<authority>/image/still-<频道>-<截图时间>`）：桌面按地址缓存图片，地址不变就不会重新读取。
- 截不到画面（加密频道、硬件解码不允许截图）就不给，桌面照常显示台标。
- 只有看过的频道才有截图；收藏但没看过的频道没有截图，这是正常的。
- 截图过期的时间点也算「内容变化」：应用在 `query()` 时按当前时间判断即可，桌面会在最早的 `end_ms` 后自动重新查询。

## 7. 第二阶段（暂不做）

- `preview_uri`：焦点在卡片上停留几秒后，桌面在大图区静音播放这个频道的直播画面。
  需要先在海信电视上确认系统播放器对直播源的兼容性和内存占用。

## 8. 桌面这边的实现（CleanViddaTV）

已实现（`TopShelf`、`ShelfSource`、`ShelfItem`，开关 `top_shelf`，默认开）：

- `<queries>` 声明了 `io.github.vesaaa.cleanviddatv.action.SHELF`，也按 authority 列了 VsTV 的两个包名。
- 程序坞里的应用获得焦点时，如果它有 shelf provider 且返回了卡片，大图区换成卡片行（标题为「应用名 · shelf 标题」）；
  没有 provider、查询失败或返回 0 行时保持原来的大字应用名。
- 结果缓存 30 秒；回到桌面、装卸应用后重新查询；显示期间注册 ContentObserver，并在最早的 `end_ms` 后 5 秒自动重新查询。
- 上键从程序坞进入卡片行，左右选择，确认打开，下键或返回键回到程序坞，卡片行再按上键到顶栏最近的按钮；按住左右键在两端停住。
- 外观接近 tvOS：选中的卡片浮起放大，开着视差效果时会倾斜、回弹、缓慢摆动；标题和节目信息只显示在选中的卡片下面；
  焦点在卡片行时，背景出现一团和卡片图片主色一致的柔和光晕，切换卡片时跟着变色，离开卡片行就淡出。
- 只显示 provider 自己的 `content://<authority>/image/` 图片，单张最多读 1 MB，缩到 512 像素以内。
- 打开卡片时，Intent 的包名强制设为 provider 所在的应用，去掉 selector、ClipData 和所有 URI 授权标志。
