# 柔光影视 GlassBox

> **当前版本 1.0.16（2026-11-09）：播放器「画面」面板（按 BieCuiLe 1.1.5 对齐）**
>
> **本轮变更（1.0.16）**
>
> - 播放页「画面」入口由「点了只弹一句未接入」改为真实面板：居中深色卡片、5 条 −100%~+100% 滑杆（亮度 / 对比度 / 饱和度 / 色相 / 伽马，默认 `+0%`）、`+存为预设` 与预设胶囊、`恢复原始`；数值写入 `AppSettings`（`pictureBrightness/pictureContrast/pictureSaturation/pictureHue/pictureGamma/picturePresets`），退出播放页或重启后保留。
> - 内核能力如实标注，不做「点了没反应」的空滑杆：MPV 五项全支持（`vo` 由 `gpu` 换成 libplacebo 管线的 `gpu-next`，锐化走内置 `vf=unsharp`）；ExoPlayer 经 `media3-effect`（`RgbAdjustment`/`Contrast`/`HslAdjustment`）支持亮度/对比度/饱和度/色相，伽马置灰并注明；IJK 明确不支持（ffmpeg 未编 `eq/color` 滤镜，SurfaceView 也无法叠 `ColorFilter`），面板置灰并给出「切换到 MPV」按钮。
> - 切内核（含自动降级）与切集后自动重新下发画面参数：`LaunchedEffect(engine)` 补发一次，`MpvPlayer` 在新文件加载完成后再应用一次。
> - 未做功能回退。原「画面」按钮本来只提示「画面调节未接入」，本轮是补齐而非替换既有能力；上一轮被中断的半成品改动经核对全部可编译且已接线，没有回退任何 1.0.15 行为。
> - 面板里的「Anime4K 增强」「黑边虚化」按原版位置保留，但标「未接入」并置灰：当前构建未内置 Anime4K 着色器、黑边虚化未接内核层，不做可点但无效果的开关。
>
> **工作区说明**：`temp_20261107_03\vbox_glass` 只是一个孤立的 `SearchScreen.kt` 空壳（无 gradle 配置，无法构建），真实工程在 `temp_20261107_03\tvbox_glass`，本轮核对、改动与构建都在后者完成。工程无 git，回退基线取自 `GlassBox-1.0.15-source.zip`。
>
> **构建**：`.\gradlew.bat :app:assembleDebug` 通过（1.0.16 / versionCode 17）。
> **APK**：`GlassBox-1.0.16-debug.apk`；源码包：`GlassBox-1.0.16-source.zip`。
> **真机**：本轮 `adb devices` 无设备（此前 54938313 不在线），**未做安装与冷启动验证**；面板实际出画效果（尤其 `vo=gpu-next` 切换）需有设备时复核。
>
> **1.0.15（历史）：统一展示名去后缀（全入口）**
>
> **本轮变更（1.0.15）**
>
> - 新增统一 `displayName()`（`core/DisplayName.kt`）：仅当**最后一个点**后的扩展名属于 `json/js/py/txt/m3u/m3u8` 时才截断，`A.B.C`、`v1.2` 等正常名字不受影响；内部 key、文件名与类型判定完全不变。
> - 所有展示位置统一接入：首页左上接口胶囊、右上站点胶囊、切换平台面板、切换资源弹窗、资源页卡片、源详情面板标题与站点行、导入预览名称（含名称输入框预填与「已导入」toast）、仓库条目、搜索页平台分组/筛选项/结果标签、删除与切换 toast、首页/搜索错误文案。
> - 真机逐处核对（54938313）：首页左上「XX01」（原 `xx01.py`）、右上「PY 源」；切换资源列表（XX01/51木瓜/18JTV…）与站点面板均无 `.json/.js/.py` 残留；资源页卡片与源详情面板标题均为无后缀名称；`uiautomator` 文本扫描匹配 `\.(json|js|py|txt|m3u)` 为 0 条。
>
> **构建**：`.\gradlew.bat assembleDebug` 通过（1.0.15 / versionCode 16）。
> **APK**：`GlassBox-1.0.15-debug.apk`；源码包：`GlassBox-1.0.15-source.zip`。
>
> **1.0.14（历史）：资源页卡片对齐原版 + 批量操作**
>
> **本轮变更（1.0.14）**
>
> **① 接口小卡片对齐原版截图**
> - 2 列网格（16dp 页边距 + 12dp 间距），卡片高 128dp、圆角 18dp、内边距 12dp、浅色描边；
> - 左侧 38dp 圆角灰底图标块（Dns 图形），标题 15sp 加粗单行省略，副标题 11sp：当前源显示蓝色「当前使用」，其余显示灰色类型（JSON/JS/PY）；
> - 右上角 `…` 更多图标；状态行绿色校验结果 + 灰色相对时间；底部 `Layers 站点数` 与 `Search 可搜索数` 两组计数；
> - **当前源**使用蓝色 1.5dp 描边（其余为 1dp 浅灰），与原版截图一致；
> - **名称不显示 `.json/.js/.py/.txt` 后缀**（`displaySourceName()` 仅用于展示），内部 `name` 原样保留用于类型判定。
>
> **② 「校验」按钮改为批量操作**
> - 原三横校验按钮替换为 ✓ 圆形「批量操作」入口；点击进入页内多选态：
>   - 顶部显示「已选 N / 总数」，提供 **全选 / 反选**（随筛选结果联动），右上角 ✕ 可随时取消；
>   - 卡片右上角出现勾选圆点，已选卡片高亮描边；未选中时「启用/停用/更新/删除」按钮置灰不可点；
>   - 四个动作：启用、停用、更新、删除；**删除**先弹二次确认（标题为「删除 N 个源？」），取消即撤回，避免误触；
>   - 执行中显示转圈 +「执行中 x/y」进度，完成后 toast 反馈「动作完成：成功 N（，失败 M）」并自动退出多选态；
>   - 批量逻辑在 `MainViewModel.batchSources()` 串行执行（`SourceBatchAction.ENABLE/DISABLE/REFRESH/DELETE`），每个源独立 `runCatching`，结束后统一 `resolveSelection()` + `refreshHome()`。
>
> **验证（真机 54938313）**：进入批量态「已选 0/20」且动作置灰 → 全选「20/20」全部勾选 → 反选回「0/20」；选 1 个源点删除弹出「删除 1 个源？」→ 取消后源仍在；执行启用后 toast「启用完成：成功 1」。真机对照图见 `1.0.14-sources-compare.png`。
>
> **构建**：`.\gradlew.bat assembleDebug` 通过（1.0.14 / versionCode 15）。
> **APK**：`GlassBox-1.0.14-debug.apk`；源码包：`GlassBox-1.0.14-source.zip`。
>
> **1.0.13（历史）：切换面板收敛 + 接口面板按钮上移 + 文件夹导入提速 + 详情返回状态恢复**
>
> **本轮变更（1.0.13）**
>
> **① 切换平台面板（仅当前接口 / 两列 / 滑动不关面板 / 只显示原名）**
> - `siteRows` 改为只取 `sources.filter { it.id == currentSourceId }` 下的站点，其它接口的站点不再出现；两列 `LazyVerticalGrid(GridCells.Fixed(2))`，独立 `LazyGridState`，支持上下滑动。
> - 面板只在**顶部把手拖动**或**点击遮罩**时关闭：面板内容使用 `NestedScrollConnection.onPostScroll` 把子列表消费后剩余的滚动量一并吃掉，内容区上下滑动只滚列表，不会拖动 sheet（原 `confirmValueChange` 拦截方案会让遮罩点击也失效，已移除）。
> - 站点项改为 `compact` 行：**只显示站点原名**，去掉前缀 logo/占位首字母块与后缀源类型标签（JSON/JS/PY/jar）；保留当前项高亮与「当前」标记，超长名称仍按原规则省略（行高 44dp、13sp）。
>
> **② 资源页接口面板：底部按钮上移**
> - 「删除该源」「关闭」从面板最下方移到顶部操作行，排在「全部启用 / 全部停用 / 更新」之后（同一行右侧）；整行 `horizontalScroll`，窄屏可横向滑动；`ActionPill` 加 `defaultMinSize(36dp × 60dp)` 保证点击区；底部原按钮行与分隔线已删除。
>
> **③ 本地文件夹导入提速（先测构成，再逐项优化）**
> - 耗时构成（旧实现，39 文件 = 16 源 + 23 个 `.txt`）：文件遍历 88ms、逐个读文件判类型、**每个候选文件都跑一次网络探测**（JS/PY 的 `probe` 会执行 `home/homeContent` 联网），`.txt` 单文件约 3486ms，串行执行把总耗时拖到 5129ms（PY 集合同规模约 43.3s），且全过程在 UI 侧表现为长时间「解析中」。
> - 优化：目录扫描移到 `Dispatchers.IO`；遍历深度限 3 层、目录上限 80、候选上限 40；**按扩展名先筛**（仅 `json/js/py`，其余在扫描阶段直接丢弃）；哈希去重（批内 + 已导入同源文件）；`async` 并发但 `Semaphore(3)` 限流；每完成一个文件即回写 `ImportUiState.progress`（「N/M · 文件名」），不再等全部扫完；PY 因 chaquopy 单实例加 `Semaphore(1)` 串行；探测加 10s 兜底（独立 `CoroutineScope + withTimeoutOrNull`，原生阻塞调用不可中断时直接放弃该线程）并 `ensurePython()` 双检加锁修复并发 `Python already started`。
> - 实测（54938313，logcat `GlassImport`，同结构 39 文件）：**1.0.13 JSON 集合** `遍历 2 个目录，收集 16 个源文件 21ms` → `扫描完成：候选 16 个 73ms` → 每文件读 21–98ms + 解析 50–92ms → `完成：导入 14，跳过 2，失败 0，总计 875ms`；**1.0.12 同集合** 约 1.3s 且仍会探测 23 个 `.txt`。**JS 集合**过滤 `.txt` 前 `累计 39 候选 / 5129ms`（`.txt` 各 ~3486ms、失败 30），过滤后 `16 候选 / 81ms 扫描 / 3509ms 总计`（失败 7 为脚本自身联网不可达）。
> - 对比局限（如实说明）：1.0.12 为降级安装环境，PY 运行时不可用、部分 JS 校验失败，因此 PY 的 43.3s 只能作为「旧版同规模目录逐个探测」的对照，不代表真实导入成功率；JSON 集合两版均可导入，是主要同条件基准。
>
> **④ 详情页返回：恢复现场，不重新加载**
> - 数据层为 Activity 级单例 `MainViewModel` + `HomeUiState`，返回时复用同一实例，不做重建；分页数据（`items/page/hasMore`）原样保留，第 3 页返回仍是第 3 页。
> - `HomeScreen` 用 `rememberSaveable` 保存 `scrollKey = "categoryId|extend"` 与 `userScrolled`；`LaunchedEffect(selectedCategoryId, extend)` 仅在 key 变化时重置到顶部并重新请求，key 未变时只记录「正常恢复」日志，**不触发任何请求**；`LazyGridState` 通过 `rememberSaveable` 持久化，返回后按同一 item 位置恢复（不回到顶部）。
> - 触发加载的 `LaunchedEffect` 已加「仅首次/key 变化」条件；触底加载由 `snapshotFlow { gridState.isScrollInProgress }` 标记用户真实滚动后才启用，避免恢复瞬间误触发翻页。
> - 失效场景：源被停用或站点被替换时走 `refreshHome(force = true)`，日志区分 `正常恢复：不重新请求 key=… page=… items=…` 与 `失效重建：refreshHome(force=…)`。
> - 真机验证：`page=3 items=63` 时进详情「铁汉柔情」→ 返回，可见项坐标完全一致（如 `三只凶猪 [44,417]`），logcat 仅有 `GlassHome 正常恢复：不重新请求 key=1|{} page=3 items=63`（进详情前已清空 logcat）；切分类 2 加载 2 页（`total=33`）→ 详情 → 返回，坐标一致、日志 `正常恢复 key=2|{} page=2 items=33`。
>
> **构建**：`.\gradlew.bat assembleDebug` 通过（1.0.13 / versionCode 14）。
> **APK**：`GlassBox-1.0.13-debug.apk`；源码包：`GlassBox-1.0.13-source.zip`。
>
> **1.0.12（历史）：分类分页（触底无限加载）**
>
> **本轮变更（1.0.12）**
> - **触底加载**：首页三列网格滚到倒数第 4 项时自动请求下一页（`page + 1`），不使用「点击加载更多」；`snapshotFlow` 监听 `LazyGridState`，请求在飞/已到底/有未重试错误时不会再触发。
> - **分页参数贯通四类源**（页码统一 **1 基**，与原版一致）：CMS `ac=videolist&t=tid&pg=N`（额外拼当前 `extend`）、JAR `categoryContent(tid, N, false, extend)`、JS `category(tid, N, true, extendJson)`、PY `category(tid, N, True, extend)`；翻页沿用当前筛选 `extend`。
> - **状态闭环**：底部 `PagerFooter` 三项 —— 加载中（`CircularProgressIndicator` +「加载中…」）、失败可重试（保留已加载条目，只显示「加载失败：…」+「重试」）、终止态「没有更多了」；切分类/改筛选清空列表并重置 `page=0 → 1`，同时取消在飞的翻页任务；同页并发由 `loadingMore` 闸门拦截，迟到结果按 `siteKey + categoryId + extend` 校验丢弃。
> - **去重保序**：跨页结果按 `globalKey`（`siteKey::id`）去重，`distinctBy` 保持原顺序；整页都是重复条目或空页即判定到底（`hasMore=false`），避免无限请求空页。
>
> **真机验证（54938313 / KaiFan jar 源 / logcat `GlassBoxPager`）**
> - 剧集（category=2）：`page=1(刷新) raw=21 total=21` → `page=2 raw=21 fresh=13 total=34` → `page=3 raw=21 fresh=14 total=48` → `page=4 … total=65` → `page=5 … total=86`，连续 4 次上滑逐页加载，每页只发一次请求；
> - 快速上下往返滑 12 次：只新增 `page=5` 一条请求，**无同页重复请求**；
> - 切分类（剧集→短剧）：`page=1(重置) category=5 extend={} raw=21`，列表从顶部第 1 页重来；改筛选：`page=1(重置) category=5 extend={year=2016} raw=1`，旧列表清空后按新筛选重载；
> - 终止态：筛选 2016 后再触底 `page=2 … raw=0 fresh=0 total=1 hasMore=false`，界面出现「没有更多了」；
> - 短剧（category=5）连续上滑至 `page=9 total=171`，每页 `raw=21 fresh=21`，请求参数均为 `tid=5&pg=N&extend={}`。
>
> **构建**：`.\gradlew.bat assembleDebug` 通过（1.0.12 / versionCode 13）。
> **APK**：`GlassBox-1.0.12-debug.apk`；源码包：`GlassBox-1.0.12-source.zip`。
>
> **1.0.11（历史）：MPV「1 秒」修复 + 竖屏播放页对齐原版（§5.8 / §5.9）**
>
> **当前版本 1.0.10（2026-11-08）：Fish JAR 加密 ext 桥接修复 + jar 地址解析**
>
> **本轮变更（1.0.10）**
> - **JAR 桥接**：Fish 系列 jar 的加密 `ext` 依赖宿主在启动时调用 `InitOrigin.d(context, bridgeUrl)` 注册桥接（`FishGuard` so 加载 + `FishCrypto` 解密）。此前未注册，加密 ext 的站点 init 抛「地址格式错误 / bridge URL is invalid / 桥接字段格式错误 / ext 仅接受 url」。现于 `DexClassLoader` 加载后、`Spider.init` 前按 loader 注册 `http://127.0.0.1:9978/proxy`。
> - **jar 地址解析**：`SourceParser.resolveAssetUrl` 按配置地址的 base 解析相对 `jar`（`lib/x.jar`、`./x.jar`、`../x.jar`）、协议相对 `//host/x.jar`，剥离 `;md5;hash` 与 `#` 后缀；`parseSites/detect` 新增 `baseUrl` 并贯通导入/校验/在线刷新链路。
> - **错误可见化**：`SiteDef` 记录 `jarRaw`（原始字段）；下载失败、类加载失败、实例化失败（含 `NoClassDefFoundError`）、init 失败均显示「原始 jar / 解析地址 / api / 原因」并打印完整栈，不再静默空结果。
>
> **真机验证（54938313 / 2026-11-08）**
> - `fish.json` 104 站全部无 `jar` 字段，`api=csp_*`，统一使用顶层 `spider=https://qiniu.gongxueyun.com/...jar`（3,789,278 字节，含 `classes.dex` + `FishGuard` so），下载/`DexClassLoader` 加载成功。
> - 修复前报错的加密 ext 站点恢复：`bl`（App88，首页出现 剧集/电影/综艺/动漫/短剧）、`FaDai`（首页真实片单：八仙！/功夫女足/蜘蛛侠：崭新之日…）、`dadun`（青之芦苇第二季/JOJO 飙马野郎…）、`KaiFan`（`homeContent` 25064 字节、`categoryContent` 2648 字节，首页真实片单）。
> - 慢/不可达站点如实记录：`竹隐`（NiuLai）`homeContent` 15s 上游超时；`兰芷`（AppUn）init 连接 `43.254.106.169:8167` 30s 超时后才加载成功 —— 均为**源站网络不可达**，非解析错误，且错误已在 UI/logcat 可见。
>
> **1.0.9（历史）：三类源修复收口 + 播放地址层诊断**
>
> **本轮变更**
> - **PY**：`exec` 命名空间补 `__file__/__package__/__cached__/__spec__/__loader__`，脚本用 `__file__` 推导目录不再 `NameError`；`PySpider` 把逻辑路径（SAF `tree:` → 真实 `/storage/emulated/0/...`）传入 Python；两处异常分支 `traceback.print_exc()` 打到 logcat；`home()` 返回无 `class/list` 的空结构时抛可见错误（不再静默空首页）；`MANAGE_EXTERNAL_STORAGE` + 设置页授权入口，脚本扫描本地目录才拿得到列表。
> - **JS**：QuickJS 补 drpy 加载期 API（`muban` 自动生长 Proxy、`pdfh/pdfa/pd`、`base64Decode`、`unescape/escape`、`urljoin`、`console`、`require` 抛可见错误），剥离 BOM；新增 `HtmlDom`（jsoup 1.17.2）+ `DrpyRule` 执行 `模板/一级/二级/tabs/lists`，MacCMS `player_aaaa` 播放页解析；`probe` 对 drpy 规则在导入时就跑一次 `home()`，有数据才标 ok。
> - **JSON**：`fish.json`（`https://6800.kstore.vip/fish.json`）实测 HTTP 200 / 无 302 / `application/json` / 35,106 字节 / 无 BOM，结构为 TVBox `sites`（104 站全 `type:3`，`api=csp_*`），顶层 `spider` 指向 3.79MB jar（含 `classes.dex` + `FishGuard` so）；真机导入「解析到 104 个可运行站点」，`csp_WenCai` 首页 4904 字节、详情 585 字节、可起播；Fish 加密 ext（LaiChuang/Niulai）初始化报「需要 bridge URL」，改为可见错误。
> - **播放地址层**：新增 `MediaProbe` 起播前探测（最终 URL / 状态 / 类型 / 首 80 字节 / 请求头），HLS 额外探测第一条分片；地址层失败显示「地址解析失败：…」并**禁止触发内核降级**；ExoPlayer 显式按地址设 `application/x-mpegURL` 并真正接入请求头（修复 `PARSING_CONTAINER_UNSUPPORTED`）；MPV 注册 `LogObserver` 把 libmpv 错误打到 logcat，并清空上一集残留请求头。
>
> **真机验证（54938313 / 1080×2400 / 2026-11-08）**
> - PY：`自动加载.py` 首页 4 个真实子站；`稀饭动漫.py` 首页 24 项 → 详情 → 选集 → 起播；`青花瓷影视.py` 首页 → 详情 → 播放。
> - 同一集（青花瓷「洺河往事 · 蓝光」）三内核：MPV `frames=148635 fps=25.0 decoder=mediacodec size=1280x544 dur=5945439`，UI 时间从 `00:00` 走到 `00:23`；Exo `mime=application/x-mpegURL`、`decoder=c2.qti.avc.decoder`、`video=1280x544`、首帧；IJK `pos=388 → 789ms`、`mediacodec`、`1280x544`。
> - 地址层失败（`2048ai` 线路）：m3u8 `status=200 type=application/vnd.apple.mpegurl`，但首条分片 `status=403`；界面只报「地址解析失败：HLS 分片被拒绝 HTTP 403…」并附原 URL/分片地址，**不再连跳三个内核**。
>
> **构建**：`.\gradlew.bat :app:assembleDebug` 通过（1.0.9 / versionCode 10）。
> **APK**：`GlassBox-1.0.9-debug.apk`。

> **1.0.8（历史）：播放控件逐项接入（真机验证）**
>
> **本轮变更**
> - **后台播放 + MediaSession**：新增 [PlaybackSession.kt](app/src/main/java/com/glassbox/app/engine/PlaybackSession.kt)，用系统 `MediaSession` + `Notification.MediaStyle` 提供通知栏/锁屏控制（播放暂停、上一集、下一集），动作经 `PlaybackActionReceiver` 转发回播放页；manifest 增加 `POST_NOTIFICATIONS` 与 receiver，Android 13+ 进播放页请求授权。
> - **线路切换保留同名剧集**：播放页切线路不再强制回到第 1 集，按原版行为先找同名集，找不到才回第 1 集。
> - **内核证据日志**：MPV 的 pause/resume、`setHardwareDecoding`（回读 `hwdec`）、`setLoop`（回读 `loop-file`）、`track-list` 读取都写 `GlassBoxPlayer` 日志；`MediaSession` 回调与通知栏动作分别留日志。
> - **投屏/弹幕/画面**：确认为依赖缺口，提示写明所缺依赖（见 §6.15）。
>
> **真机验证（54938313 / 1080×2400 / MPV 内核）**
> - 后台播放：按 HOME 后位置继续前进（`pos` 327096 → 363029），会话 `state=3`；
> - MediaSession：`cmd media_session dispatch pause/play` → `MediaSession onPause/onPlay` + `MPV pause=true/false`，会话状态 `state=3 → 2 → 3`；
> - 通知栏：`dumpsys notification` 可见 `channel=glassbox_playback category=transport`，动作「暂停 / 上一集 / 下一集」各 1 个；
> - 硬解切换：`MPV setHardwareDecoding=false read=no`（此前 `true read=mediacodec`）；
> - 循环：`MPV setLoop=true read=inf`，切集后仍 `setLoop=true read=inf`；
> - 切集 MP4：下一集切到「第三集 · Sintel MP4」出帧 `854x480 dur=52208 decoder=mediacodec`，比例 `mode=4 override=1.33333` 保持；
> - PiP：小窗进入后 `mode=pinned`、窗口 `508,2015-1036,2312`（528×297 ≈ 16:9）；
> - 退出停音：BACK 关闭播放页后日志 `MediaSession 已释放`，`dumpsys audio` 中 uid 10861 的 `state:started` 计 0，`active=true` 会话计 0。
>
> **未通过 / 未实测**（如实记录）：外挂字幕文件加载、音轨切换后的听觉确认、多线路切线路保留同名集（样本源仅 1 条线路）、通知栏按钮点击（adb 无法点通知栏，仅验证了同一回调路径）、投屏 / 弹幕（依赖缺口）。
>
> **构建**：`.\gradlew.bat :app:assembleDebug` 通过（1.0.8 / versionCode 9）。
> **APK**：`GlassBox-1.0.8-debug.apk`。


> **1.0.7（历史）· 按 BieCuiLe 1.1.5 对齐七项 + 播放器换用 libmpv**
>
> **本轮变更**
> - **两个抽屉按原版重做**：接口切换 = 「切换资源」抽屉，站点切换 = 「切换平台」抽屉；24dp 顶圆角、40×4dp 拖拽条、56dp 标题行（20sp Black）、右上 4 个 34dp 玫红圆形动作 + 30dp 灰色关闭、51dp 列表行 / 22dp logo / 17sp 主标题 / 11sp 副信息 / 左 50dp 缩进分隔线、外层暖白 `#F2F3EF` + 内嵌白色圆角列表卡；点 X、下滑、点遮罩均可关闭。
> - **顶部胶囊自适应宽度**：最短 = 5 个中文字宽 + 内边距；最长受屏幕中线约束，左侧胶囊止于中线左侧，右侧胶囊（含 16dp 右边距）整体不越过中线；超长名称自动省略。
> - **分类栏**：去掉整条大胶囊底板，每个分类各自一个 32dp 小胶囊，选中项玫红实底白字。
> - **筛选悬浮图标**：改为原版 2×2 四格（26dp 画布、格间距 3dp、圆角 2.5dp、描边 1.5dp）。
> - **详情页**：修复解析失败（对象型详情兜底、JSON↔XML 交叉回退、XML 嵌套字段 `tagStack`、剧集串 `$$$`/`#`/首个 `$` 鲁棒解析、真实站点不再静默回退 demo，错误可读并写 `DetailLoad` 日志）；布局改为原版层级（出血头图、居中大标题、meta、评分/标签、全宽淡玫红播放钮、5 个圆钮、选集头 + 线路胶囊 + 刷新 + 正序 + 定位 + 分页 4 列、推荐）。
> - **点击行为**：点击播放直接起播当前集；点击剧集直接切集，不弹选集层；有观看记录时「继续 第X集」，无记录只显示「播放」；默认第 1 集、第 1 条线路；切线路保留同名集，找不到回第 1 集。
> - **播放器**：换用原生 `dev.jdtech.mpv:libmpv:0.4.1`（与 BieCuiLe 的 media_kit/MpvSurfaceView 同源 MPV 内核），重写横竖屏控制层（顶栏/左右竖列/底部主控与 8 个工具钮、锁屏、亮度/音量/横滑/双击手势、倍速、字幕/音轨、循环、比例、PiP、后台）。**未内嵌 Flutter、未移植 media_kit**，差异见 §6。
> - **脚本源起播解析**：详情只返回 `vid|nid|0` 的 PY 源，起播前调用脚本 `playerContent` 解析成真实 m3u8/mp4（真机已出画）。
>
> **真机验证（54938313 / 1080×2400 / 密度 2.75）**
> - 详情：`洛河往事` 正常加载、无解析错误；播放按钮「播放」；线路「蓝光」+ 单集。
> - 播放：libmpv 打开 HLS，`VO: [gpu] 1280x544 mediacodec`，音频 audiotrack，时间轴前进；竖屏半屏与横屏全屏控制层均可显示和操作。
> - 抽屉/胶囊/分类/筛选截图见 `../shots/1.0.7/`。
>
> **构建**：`.\gradlew.bat :app:assembleDebug` 通过（1.0.7 / versionCode 8）。
> **APK**：根目录 `GlassBox-1.0.7-debug.apk`（约 59MB，libmpv 占约 23MB）。
>
> **未完成**：弹幕、投屏、画面增益、听歌识曲未接入（按钮给提示）；截图改用 PixelCopy（libmpv 0.4.1 未编译 PNG/JPEG 图像编码器）。

一款对标 TVBox 资源聚合能力的 Android 手机影音应用。界面按「柔光玻璃」规范重做，支持在线/本地导入 JSON、JS、PY 三类源，播放走 libmpv（主）+ Media3（备用）。

- 包名：`com.glassbox.app`
- 版本：1.0.16（versionCode 17 · 播放器画面面板）
- minSdk 26 / targetSdk 34 / compileSdk 35
- 仅打包 `arm64-v8a`（Chaquopy + libmpv 均只带 arm64 so）

---

## 1. 技术选型与理由

| 层 | 选择 | 理由 |
|---|---|---|
| UI | Kotlin + Jetpack Compose Material3 | 半透明层叠、渐变、自绘光晕与手势都可用声明式 API 精准表达；玻璃组件（填充/描边/内高光）可封装为可复用 Composable |
| 播放 | libmpv 0.4.1（主）+ Media3 ExoPlayer 1.4.1（备用） | libmpv 与 BieCuiLe 的 media_kit/MpvSurfaceView 使用同一 MPV 内核，HLS/MP4/ASS 字幕/音轨切换能力一致；控制层完全自绘 |
| 图片 | Coil 2.7 | Compose 原生、协程友好、缓存与淡入完善 |
| JSON | Gson 2.11 | 生态成熟，字段别名映射实现简单 |
| 网络 | OkHttp 4.12 | 连接/读取超时、UA、重定向可控，同时供 ExoPlayer 数据源复用 |
| JS 引擎 | QuickJS Android 0.9.2 | 内嵌、无 JIT、体积小；提供 `set(interface)` 把 Kotlin 同步桥接进脚本 |
| PY 运行时 | Chaquopy 16.0.0（CPython 3.10） | 真正的 Python 解释器，能执行现有 PY 源的 `home/category/search/detail`，而不是空接口 |
| 状态 | StateFlow + Mutex + filesDir JSON | 规模不需要 Room；JSON 持久化便于导出/调试，写入串行化避免损坏 |

> 没有选 Flutter/WebView 方案：玻璃观感与播放器手势需要贴近原生，且 WebView 播放 HLS 在部分机型不稳定。

---

## 2. 目录结构

```
tvbox_glass/
├─ app/src/main/
│  ├─ java/com/glassbox/app/
│  │  ├─ MainActivity.kt            导航与主题入口
│  │  ├─ GlassApp.kt                Application，持有 Store/Engine，懒启动 Python
│  │  ├─ data/                      Models / AppStore / DemoContent
│  │  ├─ core/Net.kt                OkHttp 封装与 URL 工具
│  │  ├─ engine/                    源解析与执行引擎
│  │  │  ├─ SourceParser.kt         JSON/JS/PY 文档识别、站点展开、多仓索引
│  │  │  ├─ SourceEngine.kt         站点 → Spider 分发、校验
│  │  │  ├─ CmsSpider.kt            type 0/1 CMS（JSON + XML）
│  │  │  ├─ JsSpider.kt             QuickJS 执行 + drpy rule 兜底
│  │  │  ├─ PySpider.kt             Chaquopy 调用
│  │  │  └─ SpiderJson.kt           统一字段别名映射
│  │  ├─ ui/
│  │  │  ├─ MainViewModel.kt        首页/搜索/详情/导入/播放编排
│  │  │  ├─ theme/                  视觉 token 层（Palette / Dimens / Type / Theme）
│  │  │  ├─ components/             通用组件（Glass / MediaCards / 抽屉 / 悬浮按钮 / 三态 / 剧集格 / Toast）
│  │  │  └─ screens/                Home/Search/Detail/Player/Sources/Settings/Library
│  │  ├─ python/glass_spider.py     PY 运行时宿主（probe / invoke）
│  │  └─ assets/                    demo_sources.json、demo_spider.js、demo_spider.py
│  └─ res/                          图标、主题、字符串
├─ docs/                            设计规范与视觉稿（随源码附带）
└─ README.md
```

数据流：

```
导入 → SourceParser 识别类型/展开站点 → AppStore 持久化
首页 → 选中单个站点 → Spider.catalog() 取站点分类与筛选 → 选中分类 → Spider.category(tid, 1, extend) → 三列网格
站点切换 / 接口切换 → 更新选择 → 重新取分类并按新站点加载
分类切换 / 筛选应用 → 同一站点内按 tid + extend 重新拉取第一页
详情 → 详情页按 siteKey 调 Spider.detail → 线路/剧集（直链线路优先）
播放 → PlayRequest → PlayerScreen(libmpv，脚本源先 playerContent 解析地址) → 进度写入 WatchRecord
```

### 2.1 首页交互（1.0.7）

- 首页只加载**当前站点**。左上角胶囊 = 接口切换，右上角胶囊 = 站点切换；两个胶囊按名称长度自适应：最短 5 个中文字宽，最长受屏幕中线约束（右侧胶囊含 16dp 右边距整体不越中线），超长省略。
- 两个切换入口都使用原版抽屉：标题行 56dp、4 个玫红圆形动作（刷新 / 定位 / 排序 / 搜索）+ 灰色关闭，列表行 52dp、内嵌白色圆角卡；点 X、下滑、点遮罩均可关闭，打开自动定位当前项。
- 双胶囊下方是站点自身分类的单行横滑栏：**每个分类独立小胶囊**，当前分类玫红实底白字，点选后清空筛选并按新分类拉取第一页。
- 分类下方为三列网格（`GridCells.Fixed(3)`），2:3 海报 + 海报下方标题（最多 3 行）。
- 搜索圆钮正上方为筛选圆钮（原版 2×2 四格图标），打开面板后按当前分类的 `filters[tid]` 动态渲染分组（单选取值、可重置、应用后刷新网格）；分类无筛选条件时明确提示。
- 加载预算：15s `withTimeout`，失败自动重试 1 次；分类、片源失败与空结果均有独立提示与重试入口。
- 切换站点时清空旧站点的分类、筛选与条目；切换分类时清空筛选；迟到的旧请求结果会被丢弃。
- 站点列表按 `key` 去重，避免 LazyGrid 键冲突。

### 2.2 视觉 token 与通用组件（1.0.2 结构重构）

外观仍是 01 号柔光玻璃规范，只做了「设计与实现解耦」，为后续按新规格整层替换做准备：

- `ui/theme/Palette.kt`：`GlassPalette`（亮/暗两套语义色 + 播放器专用 `PlayerTokens`），页面统一从 `GlassTheme.colors` 取色。
- `ui/theme/Dimens.kt`：`GlassShapes`（圆角）、`GlassSpacing`（间距刻度）、`GlassMotion`（时长与曲线）、`GlassDimens`（结构化尺寸）、`GlassElevation`（玻璃层级/描边/高光强度）。
- `ui/theme/Type.kt` + `Theme.kt`：字体层级与 `GlassTheme` 门面（`colors/shapes/spacing/motion/dimens/elevation/fillFor/shadowFor`）。
- `ui/components`：`GlassSurface`（玻璃卡片）、`GlassBottomSheet`（底部抽屉）、`BottomNav`（胶囊导航）、`GlassFilterFab`/`GlassSearchFab`（白色圆钮）、`GridPosterCard`（三列网格卡）、`showToast`（Toast 唯一出口）、`TriStateView`/`LoadingState`/`StatePill`/`ErrorNotice`（三态视图）、`EpisodeGrid`（剧集格）。
- 页面（Home / Player / Detail / Sources / Settings / Library / Search）不再写死色值与圆角；业务逻辑、数据层、站点与接口切换、源解析、播放链路均未改动。
- 新设计规格下发后，只需替换 `ui/theme` 与组件实现，页面结构基本不动。

---

## 3. 源协议

### 3.1 JSON（TVBox 配置）

顶层 `sites` 数组是核心；一个配置文件会展开为 N 条内部站点记录。

```json
{
  "spider": "https://example.com/spider.jar",
  "headers": { "User-Agent": "DemoClient/1.0" },
  "sites": [
    { "key": "demo_json", "name": "示例站·JSON CMS", "type": 1, "api": "https://example.com/api/vod/", "searchable": 1 },
    { "key": "demo_js",   "name": "示例站·JS",       "type": 3, "api": "https://example.com/demo.js" },
    { "key": "demo_py",   "name": "示例站·PY",       "type": 4, "api": "https://example.com/demo.py" },
    { "key": "demo_xml",  "name": "示例站·XML CMS",  "type": 0, "api": "https://example.com/api.php/provide/vod/" }
  ],
  "lives": [], "parses": [], "flags": [], "ads": []
}
```

支持的站点字段：`key/name/type/api/searchable/quickSearch/filterable/ext/jar/playerType/categories/headers/timeout/position/changeable`；未知字段原样保留在 `SourceEntity.raw`。

`type` 判定顺序（与 `02_源格式规范` 一致，代码见 `SourceParser.resolveType`）：

```
1) api 以 csp_ 开头 或存在 jar        → SPIDER(3)
2) api 以 .js / .js? 结尾             → JS(3)
3) api 以 .py 结尾 或 ext 标记 py      → PY(4)
4) type = 0 / 1 / 9(glass)           → XML CMS / JSON CMS / 内置
5) 其余嗅探：JSON 文档 → 配置；含 urls/repos → 多仓；含 JS/PY 特征 → 脚本
```

多仓（订阅索引）兼容三种形态：`{urls:[{name,url}]}`、`{repos:[...]}`、顶层 `[{name,url}]`。导入后弹出仓库选择，深度上限 2 层。

内置示例源：`assets/demo_sources.json` 里为一个 `type: "glass"` 站点，走本地 DemoContent，不依赖外网接口（仅封面与测试流需要网络）。

### 3.2 CMS 接口契约（type 0/1 以及 JS/PY 的最终输出）

- 分类：`GET {api}?ac=list`
- 列表：`GET {api}?ac=videolist&t={type_id}&pg={page}`
- 搜索：`GET {api}?wd={keyword}&ac=videolist&pg=1`
- 详情：`GET {api}?ac=detail&ids={id}`（部分站点用 `ac=videolist&ids=`）

剧集串解析：线路 `$$$`、剧集 `#`、集名与地址之间的第一个 `$`；空集名回落为“正片”。

字段别名（`SpiderJson` / `CmsSpider`）：

| 内部 | 别名顺序 |
|---|---|
| name | vod_name / name / title / video_name |
| pic | vod_pic / pic / cover / poster / vod_pic_thumb |
| remark | vod_remarks / note / remarks / state |
| year / area | vod_year / year；vod_area / area |
| typeName | type_name / typeName / class |
| content | vod_content / content / desc / description / blurb |
| playFrom / playUrl | vod_play_from / play_from；vod_play_url / play_url / urls |
| id | vod_id / id / ids |

XML（type 0）用轻量流式解析，兼容 `<class><ty id=..>` 位于 `<list>` 前后；`vod_play_url` 同样按 CMS 分隔符处理。

### 3.3 JS 源

两种形态都支持：

1. **函数式**：脚本定义 `home()`、`category(tid,page)`、`search(keyword)`、`detail(id)`、`player(flag,url)`、`init()`，返回 JSON 结构（`list/sections/detail/episodes`）。
2. **drpy 规则式**：脚本导出 `rule` 对象（`export default` / `module.exports =` 会在导入时自动归一为 `var rule =`），由 `RuleJsCms` 按 `host/headers/url/searchUrl/detailUrl/homeUrl/class_name/class_url` 模板发起请求并解析 CMS 风格 JSON，模板变量支持 `fyclass/fypage/fyid/**`。

脚本内可用的同步助手（QuickJS 绑定）：

```js
http_get(url, headers?)        // 返回字符串
http_post(url, body, headers?) // 返回字符串
http_json(url, headers?)       // 返回已解析对象
base64_encode(str)
md5(str)
log(str)
```

校验：`probe` 在 15s 超时内执行脚本并调用 `home()`，返回区块数；站点 `ext` 保存 rule JSON。执行入口见 `JsSpider.kt / JsExecutor`。

### 3.4 PY 源

由 Chaquopy 启动真实 CPython（首次调用有初始化耗时）。支持三种形态：

- 模块级函数 `home / homeVod / category / search / detail / player`；
- 模块级 `site` / `rule` 字典 + 同名函数；
- TVBox `class Spider` 契约：`init(ext)` + `homeContent / categoryContent / detailContent / searchContent`。

宿主代码 `app/src/main/python/glass_spider.py` 提供 `probe` 与 `invoke`，并注入 `requests`、`base.spider` 兼容垫片；HTTP 用 `urllib`，单次调用 20s 超时，返回值统一用 `{"ok":true,...}` 包裹。`probe` 要求首页实际返回条目（>0）才判定可运行。

PY 源只依赖标准库与内置垫片；不执行 `pip` 安装的第三方包（除了随 Chaquopy 打包的模块）。

---

## 4. 构建步骤

环境要求：

- JDK 17
- Android SDK Platform 35 / Build-Tools 35
- Python 3.10（Chaquopy 构建期需要；可用系统 Python，不要用 Windows Store 的别名）
- Gradle 8.9（仓库自带 wrapper）

1. 修改 `app/build.gradle` 中 Chaquopy 的 Python 路径：

```gradle
defaultConfig {
    python {
        buildPython "C:\\Users\\<you>\\AppData\\Local\\Programs\\Python\\Python310\\python.exe"
    }
}
```

2. 确认 `local.properties` 的 `sdk.dir` 指向本机 SDK。
3. 构建：

```powershell
.\gradlew.bat :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

4. Release（当前使用 debug 签名，便于直接安装测试）：

```powershell
.\gradlew.bat :app:assembleRelease
```

如需正式签名，请在 `app/build.gradle` 中替换 `signingConfigs`。

---

## 5. 真机验证记录

设备：`54938313`（Xiaomi/Redmi 类，Android 13，arm64-v8a）。

1.0.1 已验证：

- **首页按站点加载**：量子资源首页稳定出数据（首页/分类/轮播/继续观看），切换站点后标题与数据同步刷新；
- **站点抽屉**：9 个站点（按 key 去重后）的两列网格，打开自动定位并高亮当前站点（当前项在末行时同样可见），点选后关闭并按新站点加载，连续切换不崩溃；
- **接口切换**：弹窗列出全部已导入源，点选后首页切换到该源站点并重新加载（已验证切到「JS·电影天堂」后标题与数据同步刷新）；
- **底部胶囊导航**：首页 / 收藏·历史 / 设置三页切换，当前项高亮并带胶囊底色；
- **左上角站点名**：随站点切换实时更新（替代原「发现 / 柔光」）；
- **失败/超时/空态**：选中指向不可达 API 的站点（`10.255.255.1`）→ 先显示骨架，再显示「站点加载失败」+ `failed to connect ... after 6000ms` + 「重新加载 / 切换站点」，不再残留上一个站点的内容；
- **播放/详情/收藏历史回归**：量子详情页（10 条线路）正常展示，立即播放正常出画；收藏与历史页数据完好；
- **源解析矩阵**（三类源 × 三种导入通道）：
  - 在线链接：`imp.json`（JSON CMS，1 站）、`imp.js`（QuickJS 执行 `home()`，723 ms）、`imp.py`（CPython 执行 `homeContent()`，1782 ms）；
  - 本地文件：`本地.json`（56 站点配置）、`imp.js`（1568 ms）、`imp.py`（2141 ms）；
  - 剪贴板：JS / PY 自定义源均成功（源列表标记「剪贴板」）；
  - 端到端：URL 导入的 JS、PY 站点首页均渲染真实影视数据；详情页线路/选集/简介正常；
- **播放未被破坏**：量子资源直链 HLS 正常起播；详情默认线路按「直链优先」排序（`playableFirst`）。

1.0.2 结构重构回归（同一设备，外观与 1.0.1 一致）：

- 构建：`:app:assembleDebug` 通过；`adb install -r` + 冷启动无崩溃（`logcat -b crash` 空）。
- 首页：站点名 / 源徽标 / 柔光轮播 / 继续观看 / 悬浮按钮（站点数角标 67）/ 胶囊导航均正常。
- 站点抽屉：两列网格、打开自动定位并高亮当前站点（`导入验证PY`）、点选后按新站点加载。
- 站点切换与空态：切到「芒果TV」→ 标题更新为「芒果TV」，展示「这个站点暂时没有内容」+「重新加载 / 切换站点」，继续观看保留，无旧站点内容残留。
- 收藏与历史：`TriStateView` 空态（收藏 0）与两个胶囊筛选正常，导航高亮正确。
- 说明：设备当时被同时用于查看基准应用，部分页面截图被前台其他应用抢占，未纳入证据；详情/播放/源管理沿用 1.0.1 已验证链路，本次仅替换取色与形状来源。

### 5.1 缺陷 5 根因（首页加载不出数据）

| # | 根因 | 修复 |
|---|---|---|
| 1 | 首页并发请求**所有启用站点**，任一慢/失效站点拖住整体（首屏约 40s） | 改为只加载当前选中站点，单站预算 15s |
| 2 | OkHttp 超时 25s，CMS 列表失败后又串行重试 `ac=list` | 连接 6s / 读 10s / 调用 12s；仅 1 次退避重试 |
| 3 | `probe` 只验证「可执行」不看返回条数，0 项也标 VALID | `probe` 要求首页有实际条目；`testSite` 同样要求 items > 0 |
| 4 | 失败时静默回退 `DemoContent`，掩盖真实错误 | 删除静默回退，改为失败提示 + 重试 + 空态区分 |
| 5 | JSON CMS 的 `ac=list` 同时返回 class 与 list，旧实现丢弃 list 又多发一次请求 | 一次 `ac=list` 同时解析分类与最新列表，并做 10 分钟缓存 |
| 6 | 切换站点时未清空旧分区，新站点失败后仍显示上一个站点的内容，且失败提示被挤到列表末尾不易发现 | 切换站点时重置 `HomeUiState`（加载中骨架 → 新站点内容 / 失败空态 + 重新加载） |
| 7 | 多源导入同一份配置产生重复站点 key，站点抽屉 LazyGrid 抛出 `Key ... was already used` 崩溃 | 站点列表按 `key` 去重（`MainViewModel` / `AppStore` 双层） |

并发去重：`Net.singleFlight` 以 URL 为键合并同一时刻的重复请求；短时缓存 `getCached` 供分类/首页复用。

### 5.2 缺陷 6 根因（JSON / JS / PY 解析失败）

- **JSON**：BOM 未去除导致 `JsonParser` 失败；XML 分类 `<class><ty id="1">` 未识别；`note/des/state` 等字段别名缺失；`ac=detail` 失败无 `ac=videolist&ids=` 回退；`csp_`/jar 站点被误当 CMS/JS 请求。
- **JS**：ESM 形态（`export default` / `module.exports`）未归一导致执行失败；每次请求重复执行脚本；drpy `rule` 依赖 `ext` 未注入；`{class, list}` 结构未被分区解析识别（页面空白）。
- **PY**：只支持模块级函数，未支持 TVBox `class Spider` 契约（`init(ext)` + `homeContent/categoryContent/detailContent/searchContent`）；`requests`、`base.spider` 未提供垫片；`probe` 0 项误判通过；`invoke` 参数变体括号不平衡（`SyntaxError`）。
- **编码**：响应头无 charset 时按 UTF-8 合法性校验后回落 GB18030；XML/HTML `encoding=` 声明优先；统一去 BOM。

### 5.3 1.0.7 七项对齐验证（同一设备 1080×2400 / density 2.75）

- **构建/安装**：`:app:assembleDebug` 通过；`adb install -r` 成功；冷启动无崩溃。
- **首页**：左上「青花瓷影视.py」、右上「PY 源」两胶囊按名称长度自适应；右侧胶囊含 16dp 右边距整体位于屏幕中线右侧、不越线；分类栏每项独立小胶囊，「电影」选中为玫红实底；筛选悬浮钮为 2×2 四格（26dp 画布 / 3dp 间距 / 2.5dp 圆角 / 1.5dp 描边）。
- **「切换平台」抽屉**：外层暖白 `#F2F3EF` + 内嵌纯白列表卡（左右各 16dp，顶圆角 14dp）；顶圆角 24dp、把手 40×4dp、标题行 56dp / 20sp Black；右上 4 个 34dp 玫红动作钮（刷新/定位/排序/搜索）+ 30dp 灰色关闭；列表行 51dp、22dp 首字 logo、17sp 主文案 + 11sp 副信息、分隔线左缩进 50dp / 右 16dp。点 X、下滑、点遮罩均可关闭。
- **「切换资源」抽屉**：同一结构，标题「切换资源」，列出已导入源与站点数/状态，当前源高亮。
- **详情**：`洛河往事` 正常加载（无解析错误文案）；层级为出血头图 → 居中标题 → meta → 简介（可展开）→ 淡玫红全宽「播放」→ 5 个圆钮（搜索/下载/收藏/网格/评论）→「选集」头（线路胶囊 + 刷新 + 正序 + 定位）→ 4 列剧集 → 推荐。
- **点击行为**：点「播放」直接起播当前集；点剧集直接切集，不弹选集层；无观看记录默认第 1 条线路第 1 集，有记录显示「继续 第X集」并定位；切线路保留同名集，找不到回第 1 集。
- **播放器**：libmpv 打开 HLS，时间轴前进（实测 `00:37 / 1:39:05`），暂停/进度/全屏可用；横屏顶栏为纯文字（MPV / 硬解 / 片头 / 跳过 / 片尾 / 小窗 / 评论），左右竖列为无底板图标，底部左组为旋转/播放/循环/上一集/下一集 + 右侧倍速/字幕/弹幕/比例/画面/信息/投屏/选集。
- **截图说明**：`screencap` 对 SurfaceView 视频层返回黑底（系统截图限制），画面出帧以时间轴前进与 `VO: [gpu] ... mediacodec` 日志为准。
- **数据说明**：本次 PY 源单集名恰为「蓝光」（源数据如此），不是线路名误用。

### 5.4 1.0.8 播放控件逐项接入（分批进度）

> 每批 3–5 个控件，批末 `:app:compileDebugKotlin` 必须通过。未验证/未通过项如实标注。

**第 1 批（已完成，编译通过）**

- **后台播放 + MediaSession**：新增 `engine/PlaybackSession.kt`，用系统 `MediaSession`（非 media3-session）持有播放状态，`Notification.MediaStyle` 通知栏提供播放/暂停、上一集/下一集；按钮经 `PlaybackActionReceiver` 转发回当前播放页。manifest 增加 `POST_NOTIFICATIONS` 与 receiver。进入播放页按 Android 13+ 请求通知权限；拒绝只影响通知显示，不影响后台出声。
- **投屏 / 弹幕 / 画面**：确认均为依赖缺口而非接线问题，提示改为写明缺什么：
  - 投屏缺 `media3-cast` + `androidx.mediarouter`，且现有 MPV/IJK 内核不是 Media3 Player；
  - 弹幕缺渲染库（DanmakuFlameMaster 类）+ 源站弹幕接口；
  - 画面调节缺 libmpv `brightness/contrast/saturation` 面板，ExoPlayer/IJK 无对应能力。

**第 2 批（已完成，编译通过 + 真机复核）**

- **音轨**：横屏「字幕」打开 `字幕与音轨` 面板，标题「来自 MPV 轨道列表」，音频轨 6 条（`eng`）；选择会调用 `MPVLib.setPropertyInt("aid", id)`。**未实测**：切换后的听感确认。
- **字幕**：面板下半部分列出内嵌字幕与「关闭字幕」；外挂字幕选择器走 `OpenDocument` → 复制到 cacheDir → `MPV sub-add`（Exo 走 `SubtitleConfiguration` + 重新 prepare 保进度；IJK 返回 false 并提示无接口）。**未实测**：实际挑选 .srt 文件加载后的显示。
- **解码方式**：`MPV setHardwareDecoding=false read=no` / `true read=mediacodec`；ExoPlayer 固定 MediaCodec，按钮如实提示无软解回退；IJK 走 FFmpeg 软硬解开关。
- **循环**：`MPV setLoop=true read=inf`，切集后仍为 `inf`。
- **线路切换**：改为保留同名剧集（先按集名匹配，找不到回第 1 集）。**未实测**：当前样本源仅 1 条线路，无法端到端验证匹配逻辑。

**第 3 批（已完成，编译通过 + 真机复核）**

- **PiP**：小窗进入 `mode=pinned`，窗口 `508,2015-1036,2312`（528×297 ≈ 16:9）。
- **锁屏/通知层**：`MediaSession` 在锁屏显示播放状态与进度；通知栏 `category=transport`，三个动作就位。
- **手势回归**（沿用本轮前序实测）：音量 `setStreamVolume(STREAM_MUSIC)`、亮度提示 50%→100%、横滑 seek「快进到 04:42」、双击左右区 ±10s、长按 `setSpeed=2.0 read=2.000000` → 松手 `1.0`。
- **退出停音**：BACK 关闭播放页 → `MediaSession 已释放`，uid 10861 `state:started` 0 条，`active=true` 会话 0 个。

**第 4 批（收尾）**

- 控件对照表见 §5.5；APK/源码包见根目录。

### 5.5 播放页控件对照（1.0.8）

| 控件 | 原版行为（BieCuiLe 1.1.5） | 我们的行为（1.0.8） | 是否一致 |
|---|---|---|---|
| 返回 / 关闭 | 退出播放页并保存进度 | `closePlayer()` 保存进度 → pop | 一致 |
| 标题 | 顶栏显示「剧名 集名」 | 同 | 一致 |
| 画面比例 | 适应 → 拉伸 → 裁剪 循环 | 适应 → 拉伸 → 裁剪 → 16:9 → 4:3 五档循环；MPV `video-aspect-override`/`panscan`，Exo `AspectRatioFrameLayout`，IJK 宿主尺寸法 | 不完全一致（多两档固定比例；IJK 无真正拉伸能力） |
| 倍速 | 0.5–3× 面板 | 同，`MPV setSpeed` 回读验证 | 一致 |
| 音轨 | 面板列出并切换 | `track-list` 读取 + `aid` 切换 | 一致（切换听感未实测） |
| 字幕开关 | 面板关闭字幕 | `sid=no` / Exo 禁用 TEXT 轨道 | 一致 |
| 字幕文件加载 | 支持选择本地字幕 | MPV `sub-add`、Exo `SubtitleConfiguration`；IJK 不支持并提示 | 部分一致（IJK 缺、本地文件加载未实测） |
| 线路切换 | 切线路保留同名集 | 同（新增逻辑） | 一致（样本仅 1 线路，未实测） |
| 上一集/下一集 | 底部按钮切集 | 同；切集后比例/循环/倍速保持 | 一致 |
| 循环模式 | 单集循环开关 | `loop-file=inf/no` | 一致 |
| 旋转/全屏 | 横竖屏切换 | `SCREEN_ORIENTATION_SENSOR_LANDSCAPE/PORTRAIT` | 一致 |
| 锁屏 | 锁定后手势失效 | 同，只保留单击显隐 | 一致 |
| 选集 | 底部抽屉按线路列集 | 同 | 一致 |
| 投屏 | Cast 投到电视 | 未接入：缺 `media3-cast` + `androidx.mediarouter`，且内核不是 Media3 Player | 不一致（缺依赖） |
| 弹幕 | 弹幕渲染与开关 | 未接入：缺渲染库（DanmakuFlameMaster 类）+ 源站弹幕接口 | 不一致（缺依赖） |
| 解码方式 | 硬解/软解 | MPV/IJK 可切；Exo 固定硬解并如实提示 | 部分一致 |
| 内核切换 | 设置里切换内核 | 设置页切换 + 播放页顶栏点击轮换；下次进入生效 | 一致 |
| 亮度/音量手势 | 左亮右音，纵向滑动 | 同，实测生效 | 一致 |
| 长按倍速 | 长按 2× 松手恢复 | 同，日志确认 | 一致 |
| PiP | 小窗播放 | `enterPictureInPictureMode`，实测 `mode=pinned` | 一致 |
| 后台播放 / MediaSession | 退后台继续播，通知栏可控 | 系统 `MediaSession` + 通知栏三动作；后台位置继续前进 | 一致（未做前台 Service，进程被回收后通知随之消失） |
| 画面调节 | 亮度/对比度/饱和度 | 未接入：MPV 有 `brightness/contrast/saturation` 属性，面板未做；Exo/IJK 无对应能力 | 不一致（面板未做） |
| 片头/片尾 | 自动识别并跳过 | 片头为提示、跳过为固定 5s、片尾为提示 | 不一致 |

---

### 5.6 1.0.9 三类源修复与播放地址层诊断

#### 5.6.1 源格式兼容矩阵

| 源形态 | 判定依据 | 支持情况 | 验证 |
| --- | --- | --- | --- |
| TVBox `sites` 数组（`type:3` + `api=csp_*`） | `fish.json` 顶层 `sites`，104 站全 `type:3`，`spider=url;md5;hash` | 解析 + `DexClassLoader` 加载 jar | 导入显示「解析到 104 个可运行站点」；`csp_WenCai` 首页/详情/起播通过 |
| 多仓 `urls` 三级 | 顶层 `urls` → 子配置 | 支持（逐级取 `sites`） | — |
| 分类式 / `sites` 混排 | `sites[].type` 1/3/4 | type 1 CMS 直连、type 3 jar、type 4 走 JS/PY ext | 见 §3.1 |
| drpy JS（`rule` + `muban` 模板） | 脚本含 `模板/url/一级/二级` 或 `muban.首图` | QuickJS + jsoup 规则执行；导入时即跑 `home()`，无数据则报错 | `_139.js` 从 `muban is not defined` 变为可执行（host DNS 被劫持时显示 host 不可达） |
| node/cheerio JS | `require('cheerio')` / ESM `import` | 不支持，明确报「脚本要求 Node/cheerio 运行时」 | — |
| PY `class Spider`（`home/category/detail/player`） | `.py` 文件 + `tree:`/绝对路径 | Chaquopy 3.11 + requests/lxml/bs4/pyquery，`__file__` 与逻辑路径注入 | `自动加载.py`、`稀饭动漫.py`、`青花瓷影视.py` 均端到端出片 |
| PY 扫描本地目录 | 脚本用 `__file__` 推导目录 + `os.listdir` | 需 `MANAGE_EXTERNAL_STORAGE`，未授权时 `home()` 空结构抛可见错误 | 授权前可见报错；授权后 4 个子站 |
| Fish 加密 ext | `ext` 内含密文 + 依赖私有 bridge | 不能照搬；初始化失败显示「需要 bridge URL」 | `LaiChuang`/`Niulai` 均如此 |

#### 5.6.2 三个源问题的「现象 / 根因 / 修复 / 验证」

**JSON（`https://6800.kstore.vip/fish.json`）**
- 现象：导入后无法解析加载。
- 根因：结构是 TVBox `sites`（非 CMS 列表），且全部 `type:3` 依赖顶层 jar；此前未区分结构，也没有把 jar 初始化异常显示出来，异常被吞后降级为空站点。
- 修复：按 `sites` / `urls` / 分类三种结构分支解析；`csp_*` 走 `DexClassLoader`；异常改为可见错误（含类名与原因），不再静默 `SpiderNull`。
- 验证：HTTP 200、无 302、`application/json`、35,106 字节、无 BOM；真机导入 104 站；`csp_WenCai` `homeContent` 4904 字节、`detailContent` 585 字节、`playerContent` 起播；Fish 加密 ext 显示可见失败。

**JS**
- 现象：导入 drpy 源即报错/空白。
- 根因：脚本首行 `muban.首图...`，桥接未提供 `muban`/`pdfh`/`pdfa` 等 drpy 加载期 API（`muban is not defined`），且文件带 BOM。
- 修复：QuickJS PRELUDE 提供 `muban` 自动生长 Proxy、`pdfh/pdfa/pd`、`base64Decode`、`unescape/escape`、`urljoin`、`console`；`require`/ESM import 抛可见错误；剥离 BOM；新增 `HtmlDom`（jsoup）+ `DrpyRule` 执行 `模板/一级/二级/tabs/lists`；`resolvePlay` 支持 `lazy` 与 MacCMS `player_aaaa`。
- 验证：`_139.js` 可执行；`sample_net.js`（`caiji.dyttzyapi.com`）可达地址导入/首页数据。剩：`_139.js` 的 host 被 DNS 劫持，属外部不可达并已以可见错误呈现。

**PY**
- 现象（截图）：站点「自动加载」报「PY 执行失败（14996 字节，首行=""""）：NameError: name '__file__' is not defined」；修掉后又出现「该站点没有返回分类」的空首页。
- 根因：① `exec` 命名空间没有 `__file__`，脚本用 `os.path.dirname(__file__)` 推导目录直接 `NameError`；② 脚本 `PY_DIR='/storage/emulated/0/本地/py'` + `os.listdir` 需要 `MANAGE_EXTERNAL_STORAGE`，`PermissionError` 被脚本自身 `except` 吞掉 → `home()` 返回空 `class` → 首页静默空。
- 修复：`_load` 注入 `__file__/__package__/__cached__/__spec__/__loader__`，编译文件名用真实逻辑路径；`PySpider` 全链路传 `path()`（SAF `tree:` 无 `#`/有 `#` 两种都还原成 `/storage/emulated/0/...`）；两处异常加 `traceback.print_exc()` 打到 `python.stdout`；`home()` 空结构抛可见异常；Manifest 加 `MANAGE_EXTERNAL_STORAGE` 并在设置页提供授权入口；超时后重建实例。
- 验证（真机 54938313）：`自动加载.py` 首页 4 个真实子站（`【PY】123`/`123视频`/`18AV`/`18JTV`）；`稀饭动漫.py` 首页 24 项 → 详情 → 选集 → 起播；`青花瓷影视.py` 首页 → 详情 → 播放（三内核见 5.6.4）。

#### 5.6.3 原版拆解文档逐条判断（`biecuile_spec/08_源解析加载实现拆解.md`）

| 原版做法 | 判断 | 本工程落地 |
| --- | --- | --- |
| JS 源用系统 WebView（V8）+ `Native`/`BclNative`/`__hk` + 7 份 host 脚本 + crypto-js | **需替代** | 保留 QuickJS 作快速通道，补齐 drpy 加载期 API + jsoup 选择器；不引入 WebView 承载，避免宿主脚本/加密依赖与并发限制。实测 drpy 规则可跑通；依赖 `require('cheerio')`/crypto-js 的脚本仍不支持并明确报错 |
| JSON 源依赖 jar（16 个 `com.github.catvod.*` + DexClassLoader），缺 utils 会 `NoClassDefFoundError`，异常被吞后降级 `SpiderNull` 静默空 | **照搬（加载方式）+ 需替代（异常处理）** | `DexClassLoader` 照搬；兼容类 `Spider`/`SpiderDebug` 提供；异常改为可见错误，Fish 加密 ext 需私有 bridge 的如实报错 |
| PY 用 Chaquopy 3.11 + requests/lxml/bs4/pyquery，`runner.py`/`base/spider.py`/`base/localProxy.py` 明文，`fetch` 强制 utf-8；超时后不重建实例是短板 | **照搬** | 依赖与 `base.spider` 垫片照搬；`fetch` 沿用强制 utf-8；**超时后重建实例**（原版短板已修） |
| 树基址无 `#` 时拼 URI | **需替代** | `ScriptResolver.logicalPath` 统一还原逻辑路径，`tree:` 有无 `#` 都能定位，失败即报可见错误 |

#### 5.6.4 播放链路诊断（同一集：青花瓷「洺河往事 · 蓝光」）

| 环节 | 实测值 | 结论 |
| --- | --- | --- |
| 详情播放 id | 脚本 `player` 返回 m3u8 | 地址解析层正常 |
| 最终地址 | `https://newtestvideo.rekgfyn.com/splitOut/.../index.m3u8?auth_key=...` | 直链 HLS |
| 请求头 | 仅 `User-Agent`（已过滤 `https://null/`、`https://py/` 这类伪 Referer） | 无多余防盗链头 |
| m3u8 响应 | `206` `application/vnd.apple.mpegurl`，首行 `#EXTM3U` | 媒体流 |
| 分片响应 | `206` `video/mp2t`，首 128 字节可读 | 分片层可达 |
| MPV | `frames=148635 fps=25.0 decoder=mediacodec size=1280x544 dur=5945439`；UI `00:00 → 00:23` | 直接出画面、时间轴前进 |
| ExoPlayer | `mime=application/x-mpegURL`、`decoder=c2.qti.avc.decoder`、`video=1280x544`、首帧 | 可用（MIME 修复前会 `PARSING_CONTAINER_UNSUPPORTED`） |
| IJK | `pos=388 → 789ms`、`mediacodec`、`1280x544` | 可用 |
| 对照：`2048ai` 线路 | m3u8 `200` 正常，首条分片 `403 AccessDenied`（PC 侧同结论） | 属片源 CDN/防盗链层；界面只报「地址解析失败：HLS 分片被拒绝 HTTP 403…」，不再连降三级内核 |

**降级策略**：只有「地址探测通过、内核自身解码/容器报错」才按 MPV → Exo → IJK 降级；地址层失败（网页/接口/403、分片被拒）直接中止并显示可读原因与原 URL，同时把 libmpv 的 error/warn 原样打进 logcat。

### 5.7 1.0.10：fish.json「JAR 初始化失败：地址格式错误」修复记录

#### 5.7.1 原始字段（fish.json 全量）

- 顶层：`"spider": "https://qiniu.gongxueyun.com/upload/1642674/2026-11-03/leave/862321224_2_1793729323718.jar"`（无 `;md5`），HTTP 200，3,789,278 字节。
- `sites` 共 104 项：**全部没有 `jar` 字段**，`type` 全为 `3`，`api` 全为 `csp_*`；`ext` 分三类：普通 JSON/URL 字符串、加密串（`A...` 开头 base64 密文）、空。
- 因此每个站点的 jar 地址解析结果都等于顶层 `spider`；本配置中不存在相对路径 jar，但仍补齐了相对/协议相对解析以兼容其他配置。

#### 5.7.2 根因

`FishGuard` so 的 `FishCrypto` 解密依赖宿主在 Application 阶段调用 `InitOrigin.d(context, bridgeUrl)` 注册桥接并加载 so。我们此前只做了 `DexClassLoader` + `loadClass`，没有这一步，于是：
- 加密 ext 站点在 `init` 内解不出站点地址 → 抛 `地址格式错误` / `bridge URL is invalid` / `桥接字段格式错误` / `ext 仅接受 url`；
- 普通 ext 站点不受影响，所以表现为「部分站点」失败。

#### 5.7.3 修复

1. `JarSpider.initHostBridge()`：在 `DexClassLoader.loadClass` 之后、`Spider.init` 之前，反射调用 `com.github.catvod.spider.InitOrigin.d(Context, String)`，bridge = `http://127.0.0.1:9978/proxy`；按 `DexClassLoader` 逐实例注册（各 loader 的静态状态独立）。
2. `SourceParser.resolveAssetUrl()`：`jar` 支持 `url;md5;hash`、`#` 后缀、相对路径、`//host/path`，按配置 base 解析；`detect/parseSites` 新增 `baseUrl` 并接入导入、源校验、在线刷新。
3. 错误可见化：`SiteDef.jarRaw` 保留原始字段；下载失败 / 类加载失败 / 实例化失败（含 `NoClassDefFoundError`）/ `init` 失败都输出「原始 jar → 解析地址 → api → 原因」并附完整堆栈。

#### 5.7.4 逐站点验证（原始字段 → 解析 URL → 下载 → 加载）

| 站点 | api | 原始 jar | 解析 URL | 下载 | 加载结果 | 首页 |
| --- | --- | --- | --- | --- | --- | --- |
| `bl`（摆烂） | csp_App88 | 无（用顶层 spider） | 顶层 jar | 3,789,278 字节 OK | 已加载 App88 | 真实分类：剧集/电影/综艺/动漫/短剧 |
| `FaDai` | csp_FaDai | 无 | 顶层 jar | OK | 已加载 FaDai（修复前：`ext 仅接受 url`） | 真实片单：八仙！/功夫女足/蜘蛛侠：崭新之日… |
| `dadun`（大盾） | csp_DaDun | 无 | 顶层 jar | OK | 已加载 DaDun | 真实片单：青之芦苇第二季/JOJO 飙马野郎… |
| `KaiFan`（开饭） | csp_KaiFan | 无 | 顶层 jar | OK | 已加载 KaiFan | `homeContent` 25064 字节、`categoryContent` 2648 字节，首页真实片单 |
| `竹隐` | csp_NiuLai | 无 | 顶层 jar | OK | 已加载 NiuLai（修复前：`NiuLai ext requires only php and site`） | 上游 `homeContent` 15s 超时（源站网络不可达，已可见） |
| `兰芷` | csp_AppUn | 无 | 顶层 jar | OK | init 连接 `43.254.106.169:8167` 30s 超时后加载成功 | 源站不可达，错误可见 |
| `WenCai` | csp_WenCai | 无 | 顶层 jar | OK | 已加载 | 4904 字节首页 + 详情 + 起播（1.0.9 已验，无回归） |

**结论**：`地址格式错误` 是我们未注册宿主桥接造成的，已修复；仍失败的少量站点是上游服务器不可达/超时，属于源本身问题，现在都会在界面与 logcat 显示原始字段、解析地址和完整异常栈。

### 5.8 1.0.11：MPV「播放只有 1 秒」根因与修复

**现象**：摆烂 4K 站点起播，MPV 时间轴停在 1 秒；Exo 报 `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`；IJK 也失败。降级链会把同一个地址问题演成三个内核报错。

**证据（logcat，同一集）**

```
起播探测 ... status=200 type=image/png bytes=2048 isHls=true
  head=#EXTM3U #EXT-X-VERSION:3 #EXT-X-MEDIA-SEQUENCE:0 ...
分片探测 status=206 type=image/png bytes=128 url=.../xxxx.png
修复前 MPV [ffmpeg/video/20] png: Invalid PNG signature 0x234558544D33550A
  （0x23455854 = "#EXT"，0x4D33 = "M3"，0x550A = "U\n"）
修复前 MPV duration=1000 time-pos=0
```

**根因**：站点把 HLS 播放列表和 TS 分片伪装成 `.png` + `image/png`。MPV 按后缀/MIME 选中内置 image demuxer，把 m3u8 文本当成一张 1 秒静态图（`duration=1000`），并不是时长读取错误，也不是 1 秒后 EOF，更不是循环重载。地址层本身是通的。

**修复**

1. `MediaProbe` 新增 `isHls`：响应体以 `#EXTM3U` 开头即判定，不依赖 URL 后缀与 Content-Type；经 `PlaybackPrep.isHls` 传给内核。
2. MPV：强制 `demuxer=lavf`，HLS 时 `demuxer-lavf-format=hls`，绕开 image demuxer；新增 `MPV 属性[...]` 快照日志（`duration/time-pos/eof-reached/idle-active/keep-open/pause/paused-for-cache/core-idle/demuxer/file-format/video-format/width/height/track-list/count/estimated-frame-count/path`）。
3. Exo：`forceHls` 时直接使用 `APPLICATION_M3U8`，不再只靠 URL 里的 `.m3u8`。
4. IJK：`OPT_CATEGORY_FORMAT iformat=hls` + `protocol_whitelist=http,https,tcp,tls,crypto`。
5. 降级链：新增 `playStarted`，只有地址真正交给内核后才开始 25s「加载超时」计时；地址还在解析/探测时不再误报「线路没有返回画面」并连跳三个内核。

**验证（修复后，同一集）**

| 内核 | 属性/日志 | UI | 结论 |
| --- | --- | --- | --- |
| MPV | `duration=1948.439999 time-pos=1.943333 eof-reached=no idle-active=no demuxer=lavf file-format=hls video-format=hevc width=3840 height=2160` | 01:41 / 32:28 | 画面正常，时间轴推进 |
| Exo | `EXO 起播 mime=application/x-mpegURL`、`c2.qti.hevc.decoder 3840x2160 dur=1948440` | 00:01 / 32:28 | 画面正常 |
| IJK | `size=3840x2160 dur=2780880 pos 前进` | 00:00 / 46:20 | 画面正常（首页片单刷新后落到另一集） |

**结论**：MPV「都是 1 秒」是 disguised-HLS 触发图片 demuxer，属我们未做强制 HLS 解复用，已修复；三内核不再出现 1 秒时长。

### 5.9 1.0.11 竖屏播放页按 BieCuiLe 1.1.5 对齐（横屏不动）

原版截图（`com.langhuan.tv` 1080×2400，density 2.75）与本机逐项对照：

| 区域 | 原版 | 1.0.10 | 1.0.11 |
| --- | --- | --- | --- |
| 顶部左 | `第01集` 文字，无图标 | 返回图标 + `比例 适应` + 小窗 + 全屏 | 返回图标 + `第01集`；右移 6dp |
| 顶部右 | `弹幕 字幕 音轨 倍速 比例` 纯文字 | 只有比例文字 | 五个纯文字入口（13sp），音轨/倍速/比例可点 |
| 顶栏下 | `3840×1608 · 4K` 角标 | 无 | `宽×高 · 4K/2K/1080P` 角标，读取真实解码尺寸 |
| 中央 | 单个暂停键，无圆底 | 播放键 + 上一集/下一集 | 单个播放/暂停键，无上一集/下一集 |
| 底部 | 一行：`00:54` + 进度 + `45:28` + 全屏 | 独立进度条 + 时间/上一集/播放/下一集/时间 | 一行：时间 + 进度 + 总时长 + 全屏；进度白填充、8px 轨道、15px 拇指 |
| 信息区 | 海报 + 标题 21sp + 标签 + 评分 + 简介 3 行 | 海报 + 标题 + 标签 + 简介 2 行，上滑收起压在海报上 | 加上评分行（源有 `score` 时）、简介 3 行、上滑收起改为海报下方灰色胶囊 |
| 操作区 | 搜索/下载/收藏/选集/评论 | 换平台/下载/收藏/追更/评论（追更无实现） | 搜索/下载/收藏/选集（打开选集面板）/评论 |
| 选集行 | `选集` + 线路胶囊（紧随标题） + 刷新/排序/定位 | `选集` + 箭头 + 线路胶囊推右 + `正在播放 第xx集` | `选集` + 线路胶囊紧随（26dp），去掉「正在播放」 |
| 剧集格 | 4 列，选中浅粉底红字 | 4 列，选中实心红底白字 | `EpisodeGrid(softSelected=true)`，仅竖屏播放页用 |

**未照搬的原版元素**（有意保留差异）：原版顶栏无返回键（依赖系统手势），我们保留返回；原版选集行的刷新/排序/定位三颗胶囊在竖屏播放页未复制（详情页已有刷新/排序/定位，避免重复入口）；原版海报在此截图中未加载（白色空位），我们保留海报位。

验证：竖屏（1080×2400）与原版并排比对 `docs/compare-1.0.11.png`；MPV/Exo/IJK 三内核下顶栏、角标、中央键、进度行均正常显示与响应（点击中央键可暂停/继续，进度条可拖动）。

### 5.10 1.0.16 播放器画面面板（BieCuiLe 1.1.5 对齐）与工作区核对

#### 5.10.1 先核对现状（不假设）

- 工程无 git（`git status` → `NO_GIT`），回退基线为 `GlassBox-1.0.15-source.zip` 解压副本，按文件逐行比对。
- `app/src` 下**没有新增文件**，共 7 个文件有改动：`Models.kt`(+19)、`AppStore.kt`(+15)、`PlaybackEngine.kt`(+42)、`IjkPlayerEngine.kt`(+12)、`ExoPlayerEngine.kt`(+62)、`MpvPlayer.kt`(+50/−2)、`PlayerScreen.kt`(+424)。
- `.\gradlew.bat :app:assembleDebug` 直接 BUILD SUCCESSFUL（46 tasks），说明中断留下的改动**本身可编译**，不属于「半成品编译不过」。逐文件比对后确认改动是「新增能力接口 + 新增面板 + 两处入口接线」，没有半截函数、没有声明了却没人调用的死入口，所以按「能较快收敛成可用功能」处理：全部保留并补齐，**没有回退任何 1.0.15 行为**。

#### 5.10.2 面板与内核能力映射（不做空滑杆）

| 项 | MPV（默认主内核） | ExoPlayer | IJK |
| --- | --- | --- | --- |
| 亮度 / 对比度 / 饱和度 / 色相 / 伽马 | 五项全支持（libmpv equalizer 属性，渲染改走 `vo=gpu-next`） | 前四项（media3-effect `RgbAdjustment`/`Contrast`/`HslAdjustment`），伽马置灰 | 全部置灰 |
| 锐化 | `vf=unsharp`（0–100%，仅变化时重建滤镜链） | 不支持 | 不支持 |
| 持久化 | `AppSettings`，退出播放页/重启保留 | 同 | 同（切到 MPV 后可直接生效） |

不支持项统一「滑杆置灰 + 数值显示 `—` + 面板底部写明原因」，避免出现点了没反应的入口。

#### 5.10.3 交互

- 竖屏顶栏「画面」（13sp 纯文字）与横屏同位置入口均打开面板；拖动即时下发内核，松手才落盘（避免拖动过程频繁写文件）。
- `+存为预设` → 「保存为预设」输入框 → 保存后以胶囊列出，点击即应用同一组数值；`恢复原始` 一键归零并落盘；重复名字覆盖同名预设。
- IJK 下五项全灰并给出「切换到 MPV」按钮，点击后 `fallbackChain` 固定 MPV、重建内核，`LaunchedEffect(engine)` 把当前画面参数补发到新内核。

#### 5.10.4 本轮已知缺口（如实）

1. **真机未验证**：`adb devices` 无设备（此前 54938313 离线），未做安装、冷启动与出画验证。`vo=gpu-next`（libplacebo）此前在同机型上做过五项生效取证，但本轮无法复现；若个别机型异常，回退点是 `vo=gpu`（已知亮度单项可用的旧路径）。
2. **独播库.js 端到端仍未确认解决**：代码层已有 `homeVod()` 兜底（`JsSpider.kt` 在 `home()` 只返回 class 时改用 `homeVod()` 取片单），但仓库中没有该源「首页 → 详情 → 起播」的真机记录，本轮无设备无法复核，因此**不能记为已解决**。
3. **Anime4K 增强 / 黑边虚化**：位置保留、标「未接入」并置灰——未内置着色器、未接内核层。

## 6. 已知限制与规范差异

1. **真实背景高斯模糊未实现**：柔光玻璃用多层半透明渐变 + 渐变描边 + 上缘高光 + 柔影合成，未对背后内容做 RenderEffect 模糊。这是性能降级路径（规范 §4.9 允许），在低端机上更稳；后续可在 Android 12+ 用 `Modifier.graphicsLayer { renderEffect = BlurEffect(...) }` 逐级替换。
2. **玻璃强度滑块**：规范给的是固定四级 blur/fill 映射；当前滑块只调整背景光晕与填充浓度，映射关系仍按 L1–L4 的填充比例。
3. **L4 遮罩导航**：实现为 `scrim + 玻璃填充`，未做 32dp 真实 blur；播放页顶部栏、详情头图叠层使用同一套合成玻璃。
4. **JS 沙箱能力有限**：QuickJS 提供同步 HTTP/Base64/MD5/log，并在 1.0.9 补齐 drpy 加载期 API（`muban`/`pdfh`/`pdfa`/`pd`/`unescape`/`urljoin`）与 jsoup 选择器执行；不内置 cheerio、crypto-js、node 模块，`require('cheerio')` 会抛可见错误。需要完整浏览器环境的 JS 源仍会失败。
5. **QuickJS 超时不可中断**：`withTimeout` 只让调用方返回，脚本线程可能继续运行到自然结束；恶意死循环源仍可能占用一个线程。
6. **PY 首次调用慢**：Chaquopy 初始化约数秒；仅打包 `arm64-v8a`，32 位设备无法安装。
7. **type=3 jar spider**：已用 `DexClassLoader` 加载（`csp_*` + 顶层 `spider=url;md5;hash`），`fish.json` 的 `csp_WenCai` 真机可起播；但依赖原宿主私有 bridge 的 Fish 加密 ext（LaiChuang/Niulai 等）初始化会失败，界面显示「需要 bridge URL」而非静默空结果。
8. **`lives` / `parses` / `flags` / `ads`**：目前仅存储/保留字段，未做直播入口、解析链与广告域名拦截。
9. **二维码导入未实现**：提供在线链接、剪贴板文本、本地文件三种入口。
10. **首页分区顺序**：由当前站点 `home()` 返回顺序决定，未读取独立 `home_sections` 配置。
11. **网页解析线路不可播**：部分 CMS（如电影天堂的 `dytt` 线路）只给出 `/share/<hash>` 网页解析地址，ExoPlayer 直接请求返回 404。详情页会按「直链优先」排序（`.m3u8/.mp4` 优先），但仍需后续接入 parse 规则才能播放这类线路。
12. **示例素材**：封面使用 `picsum.photos`，测试流使用 `test-streams.mux.dev`、Apple BipBop 与 `media.w3.org`；示例站不依赖任何真实影视站点。
13. **搜索可搜范围**：查询当前站点 + 启用站点（上限 12），单站失败静默跳过；没有跨站统一的筛选条。
14. **播放器未移植 media_kit**：原版是 Flutter `media_kit + MpvSurfaceView`；本工程直接使用同源 MPV 内核的原生 `dev.jdtech.mpv:libmpv:0.4.1`（arm64 so，wrapper MIT，内核 libmpv/FFmpeg），**没有 Flutter 运行时、没有 media_kit 包装层**。控制层（顶栏文字钮、左右竖列、底部主副控、手势、倍速、字幕/音轨、循环、比例、PiP、后台）按原版行为重写。
15. **播放器未接入项**：弹幕、投屏、听歌识曲只给提示；片头/片尾跳过为提示或固定 5s，不是自动识别。具体缺口：
    - 投屏：需打包 `androidx.media3:media3-cast` + `androidx.mediarouter`，并把播放切到 Cast Player；当前 MPV/IJK 内核不在这套 Player 体系内；
    - 弹幕：需弹幕渲染库（DanmakuFlameMaster 类）以及源站弹幕接口（本工程源协议里没有弹幕字段）；
    - 画面增益（1.0.16 已做，剩余差异）：MPV 五项全支持、ExoPlayer 四项支持；**IJK 不支持**（ffmpeg 没有 `eq/color` 滤镜，SurfaceView 叠不了 `ColorFilter`），面板置灰并引导切 MPV；「Anime4K 增强」「黑边虚化」仍未接入，面板中标「未接入」并置灰。
16. **后台播放下限**：后台出声与通知栏控制用系统 `MediaSession` + `Notification.MediaStyle`，**未实现前台 Service**（未声明 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK`）。系统内存紧张时回收进程会同时中断播放并移除通知；需要「长期后台播放不被杀」时应补前台 Service。
17. **APK 体积与 ABI**：libmpv 增加约 23MB，APK 约 59MB；只打包 `arm64-v8a`，minSdk 由 24 提到 26。
18. **许可证**：libmpv/FFmpeg 的 LGPL/GPL 取决于编译选项；本项目仅自用研究、不对外分发，正式发布前需自行评估。
19. **播放器截图**：libmpv 0.4.1 未编译 PNG/JPEG 编码器，播放页截图改用 `PixelCopy` 抓 SurfaceView 写 PNG。

---

## 7. 内置示例源

| 源 | 类型 | 说明 |
|---|---|---|
| 柔光放映厅 | JSON（内置 glass） | 13 部示例影片 + 2 条 HLS 测试流，首页三分区、详情、搜索、播放全链路 |
| 柔光示例源 · JS | JS（QuickJS） | `assets/demo_spider.js`，演示函数式 API 与本地数据返回 |
| 柔光示例源 · PY | PY（Chaquopy） | `assets/demo_spider.py`，演示 `site`/`rule` + `home/search/detail` |

可在「源管理 → 导入源」中追加自己的配置；导入前会做类型识别与一次 `probe` 校验，失败会显示原因。

