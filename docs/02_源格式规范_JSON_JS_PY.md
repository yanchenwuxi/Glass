# 源格式规范：JSON / JS / PY（TVBox 生态）

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 读者 | 客户端解析层开发、源适配开发者、QA |
| 目标 | 让客户端能"照着实现导入 → 校验 → 解析 → 播放"的完整链路 |
| 关联文档 | `01_设计规范_柔光玻璃.md` |
| 示例约定 | 本文所有样例均使用假数据：域名 `example.com`、片名"示例影片 A/B"、ID 为纯数字。禁止把真实站点、真实片名、真实 CDN 地址写入测试夹具 |

---

## 0. 三类源的分工

```
            ┌──────────────────────────────────────────────┐
            │  本地源仓库（Room / SQLite）                   │
            │  source(id, type, url, name, ext, enabled…)   │
            └───────────────▲──────────────────────────────┘
                            │ 导入 / 校验 / 归一化
   ┌────────────┬───────────┴────────────┬─────────────────┐
   │ JSON 配置   │ JS 源（drpy 约定）      │ PY 源            │
   │ 多站点容器   │ 单站点脚本              │ 单站点脚本        │
   └────┬───────┴───────────┬────────────┴────────┬────────┘
        │ 展开为 site        │ 由 JS 运行时求值     │ 由 PY 运行时/远端服务求值
        ▼                   ▼                     ▼
   ┌─────────────────────────────────────────────────────────┐
   │ 统一数据模型：Category / Vod / Episode / Line / PlayInfo │
   └─────────────────────────────────────────────────────────┘
```

关键结论：

- **JSON 是"容器"**：一个 JSON 配置承载 N 个站点（site）、N 条直播（live）、N 个解析器（parse）。客户端导入后应展开为 N 条内部源记录，而不是把整份 JSON 当一个源。
- **JS / PY 是"站点实现"**：本质上都是"给定分类/关键词/ID，返回标准结构"。客户端只认标准结构，不认脚本内部实现。
- **一切最终归一到 CMS 风格结构**（`class / list / page / pagecount`），这是 TVBox 生态事实上的通用语。

---

## 1. JSON 源（TVBox 配置）

### 1.1 顶层字段

| 字段 | 类型 | 必需 | 含义 |
|---|---|---|---|
| `spider` | string | 否 | spider jar 的 URL（或本地 `./` 相对路径）。有 `type=3` 站点时必需 |
| `sites` | array\<Site\> | 是 | 站点列表，可为空数组 |
| `lives` | array\<Live\> | 否 | 直播源（本设计 v1 仅存储，不做播放入口，保留字段） |
| `parses` | array\<Parse\> | 否 | 解析器链（用于需要网页解析的播放地址） |
| `flags` | array\<string\> | 否 | 平台标记，如 `qq` `qiyi` `youku` `mgtv`，用于播放地址归属识别 |
| `ads` | array\<string\> | 否 | 广告域名黑名单，命中则拦截请求 |
| `wallpaper` | string | 否 | 背景图；本设计**不采用**（背景由柔光渐变生成） |
| `logo` | string | 否 | 应用 logo，本设计不采用 |
| `headers` | object | 否 | 全局请求头（UA / Referer 等） |
| `proxy` | string | 否 | 代理地址（`http://host:port`） |
| `rules` | array | 否 | 嗅探/规则，本设计 v1 忽略 |
| `ext` | object | 否 | 扩展参数，透传给 spider |

> 版本兼容：读取时对未知字段一律**保留原样**（`raw` 字段存原文），便于导出回写时不丢信息。

### 1.2 Site 字段

| 字段 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `key` | string | 是 | 站点唯一键，用于去重（同一 key 视为同一站点） |
| `name` | string | 是 | 展示名 |
| `type` | int | 是 | 站点类型，见 §1.3 |
| `api` | string | 是 | 站点入口：CMS 地址 / 类名（spider 内）/ 脚本地址 |
| `searchable` | int(0/1/2) | 否 | 是否参与搜索；`2` 表示仅精确匹配可搜（生态内常见语义），默认 1 |
| `quickSearch` | int(0/1) | 否 | 是否参与快速搜索（首页搜索下拉），默认 0 |
| `filterable` | int(0/1) | 否 | 是否有筛选条件，默认 0 |
| `ext` | string/object | 否 | 站点扩展参数，spider 脚本内部使用 |
| `jar` | string | 否 | 该站点独立 jar（覆盖全局 `spider`） |
| `playerType` | int | 否 | 播放器类型（1 系统/2 内置/3 外部），默认跟随全局 |
| `categories` | array\<string\> | 否 | 限定分类白名单，为空表示全部 |
| `headers` | object | 否 | 该站点专用请求头 |
| `timeout` | int(ms) | 否 | 单请求超时，默认 8000 |
| `position` | int | 否 | 排序位 |
| `changeable` | int(0/1) | 否 | 是否允许用户修改（1 表示只读内置源） |

### 1.3 type 取值与客户端行为

| type | 语义 | 客户端动作 |
|---|---|---|
| `0` | CMS XML 接口 | 按 XML 解析（`<?xml><rss><list><video>`），归一为内部模型 |
| `1` | CMS JSON 接口 | 按 §2 的 JSON 契约解析 |
| `3` | JAR spider | 加载全局/独立 jar，按 `api` 反射调用类名（如 `csp_Xxx`） |
| `2` | 脚本站点（部分分支用于 PY 或 JS 单站点） | **不要盲信**：按 `api` 后缀与内容嗅探判定走 JS 或 PY 运行时 |

兼容说明（必须写进代码注释）：`type` 在 TVBox 各分支中并不完全一致（FongMi 分支只稳定支持 0/1/3；部分分支用 2 表示脚本站点，也有用 4 表示"外部播放器"）。因此客户端的判定顺序应为：

```
1) api 以 csp_ 开头 或 jar 存在           → SPIDER
2) api 以 .js / .js? / jsdelivr 脚本 结尾  → JS
3) api 以 .py 结尾                        → PY
4) ext 含 {"type":"py"} 等显式标记         → PY
5) 其余按 type 字段（0 XML / 1 JSON）
6) 仍无法判定 → 拉取 api 首 1KB 嗅探 BOM/关键字，再判定
```

### 1.4 最小可执行 JSON 样例

```json
{
  "spider": "https://example.com/jar/custom_spider.jar",
  "headers": {
    "User-Agent": "Mozilla/5.0 (Linux; Android 13) ExampleClient/1.0"
  },
  "sites": [
    {
      "key": "demo_json",
      "name": "示例站·JSON",
      "type": 1,
      "api": "https://example.com/api/vod/",
      "searchable": 1,
      "quickSearch": 1,
      "filterable": 1
    },
    {
      "key": "demo_spider",
      "name": "示例站·Spider",
      "type": 3,
      "api": "csp_Demo",
      "ext": "https://example.com/api/vod/",
      "searchable": 1,
      "quickSearch": 0,
      "jar": "https://example.com/jar/demo_spider.jar"
    }
  ],
  "parses": [
    {
      "name": "示例解析",
      "type": 1,
      "url": "https://example.com/parse?url=",
      "ext": { "flag": ["qq", "qiyi"] }
    }
  ],
  "flags": ["qq", "qiyi", "youku"],
  "ads": ["ads.example.com"]
}
```

导入后应产生 **2 条内部源**，而不是 1 条"配置文件"。UI 上展示为两个 SourceRow（名称 + 类型标签 `JSON` / `JAR`）。

### 1.5 多仓（仓库订阅）格式

TVBox 生态里的"多仓"是一个中间层：一个 URL 返回仓库列表，用户选仓后再拉具体配置。

```json
{
  "urls": [
    {
      "name": "示例仓库 A",
      "url": "https://example.com/repo/a.json"
    },
    {
      "name": "示例仓库 B",
      "url": "https://example.com/repo/b.json"
    }
  ]
}
```

字段兼容：部分配置用 `repos` / `list` / 顶层数组直接给 `[{name,url}]`。解析器应对上述三种形态全部兼容：

```kotlin
sealed class RepoDoc {
  data class Wrapped(val urls: List<RepoEntry>) : RepoDoc()   // {urls:[...]}
  data class Bare(val list: List<RepoEntry>) : RepoDoc()      // [...]
}
data class RepoEntry(val name: String, val url: String, val note: String? = null)
```

导入流程（与设计文档 §7.5 对齐）：

```
输入 URL → 拉取 → 判定是"多仓索引"还是"具体配置"
  ├ 多仓索引 → 弹出仓库选择面板 → 选中后递归拉取该仓 → 得具体配置
  └ 具体配置 → 直接进站点勾选预览
```

递归深度上限 **2**，防止套娃；同一 URL 重复出现即停止。

---

## 2. CMS 采集接口契约（type 0 / 1，以及 JS/PY 的最终输出）

这是全生态的"通用语"。客户端只实现这一套，就能覆盖绝大多数源。

### 2.1 分类列表

```
GET {api}?ac=list            (XML 接口常用 ?ac=list 或 /?ac=list)
```

```json
{
  "code": 1,
  "msg": "ok",
  "class": [
    { "type_id": 1, "type_name": "示例分类·电影" },
    { "type_id": 2, "type_name": "示例分类·剧集" },
    { "type_id": 3, "type_name": "示例分类·综艺" }
  ]
}
```

XML 版（type 0）等价结构：

```xml
<?xml version="1.0" encoding="utf-8"?>
<rss version="5.1">
  <list page="1" pagecount="1" pagesize="20" recordcount="3">
    <video>
      <id>101</id><tid>1</tid><name>示例影片 A</name>
      <type>示例分类·电影</type><pic>https://example.com/img/a.jpg</pic>
      <lang>国语</lang><area>示例地区</area><year>2024</year>
      <state>0</state><note>更新至 8 集</note>
      <actor>示例演员</actor><director>示例导演</director>
      <des></des>
    </video>
  </list>
  <class>
    <ty id="1">示例分类·电影</ty>
    <ty id="2">示例分类·剧集</ty>
  </class>
</rss>
```

> XML 源注意：`<class>` 在部分实现里位于 `<list>` 前后不定，解析必须容错；编码可能是 GBK，需按响应头/`encoding` 声明处理。

### 2.2 列表

```
GET {api}?ac=videolist&t={type_id}&pg={page}          JSON 版
GET {api}?ac=list&t={type_id}&pg={page}               XML 版常见
GET {api}?ac=videolist&t={type_id}&pg={page}&f={filter}  带筛选
```

```json
{
  "code": 1,
  "msg": "数据列表",
  "page": 1,
  "pagecount": 12,
  "limit": 20,
  "total": 236,
  "list": [
    {
      "vod_id": 101,
      "vod_name": "示例影片 A",
      "vod_pic": "https://example.com/img/a.jpg",
      "vod_remarks": "更新至 8 集",
      "vod_year": "2024",
      "vod_area": "示例地区",
      "vod_lang": "国语",
      "type_id": 1,
      "type_name": "示例分类·电影",
      "vod_time": "2024-06-01 12:00:00"
    }
  ]
}
```

### 2.3 详情

```
GET {api}?ac=detail&ids=101
GET {api}?ac=videolist&ids=101      部分站点只认这个
```

详情返回 `list` 数组，**在列表字段基础上增加**：

| 字段 | 类型 | 含义 |
|---|---|---|
| `vod_content` | string | 简介（可能是 HTML，需清洗） |
| `vod_actor` | string | 演员，`/` 或 `,` 分隔 |
| `vod_director` | string | 导演 |
| `vod_play_from` | string | 线路名，`$$$` 分隔多条线路 |
| `vod_play_url` | string | 剧集列表，格式见 §2.4 |
| `vod_score` | string | 评分 |
| `vod_douban_id` | int | 外部 ID（可空） |

```json
{
  "code": 1,
  "list": [
    {
      "vod_id": 101,
      "vod_name": "示例影片 A",
      "vod_pic": "https://example.com/img/a.jpg",
      "vod_content": "<p>这是一个示例简介，仅用于字段演示。</p>",
      "vod_actor": "示例演员甲/示例演员乙",
      "vod_director": "示例导演",
      "vod_year": "2024",
      "vod_area": "示例地区",
      "vod_remarks": "更新至 8 集",
      "vod_play_from": "示例线路一$$$示例线路二",
      "vod_play_url": "第1集$https://example.com/play/101-1.m3u8#第2集$https://example.com/play/101-2.m3u8#第3集$https://example.com/play/101-3.m3u8$$$第1集$https://example.com/play/101-1b.m3u8#第2集$https://example.com/play/101-2b.m3u8"
    }
  ]
}
```

### 2.4 `vod_play_url` 的二级分隔符（必须逐字符实现）

```
线路之间：  $$$   （三个美元符）
剧集之间：  #     （井号）
集名与地址： $    （单个美元符）
```

解析伪代码：

```kotlin
fun parsePlay(playFrom: String, playUrl: String): List<Line> {
    val names = playFrom.split("$$$")
    val groups = playUrl.split("$$$")
    return groups.mapIndexed { i, group ->
        val episodes = group.split("#").mapNotNull { item ->
            val idx = item.indexOf('$')          // 只在第一个 $ 处切分
            if (idx < 0) return@mapNotNull null
            val title = item.substring(0, idx).trim()
            val url = item.substring(idx + 1).trim()
            if (url.isEmpty()) null else Episode(title.ifEmpty { "正片" }, url)
        }
        Line(name = names.getOrElse(i) { "线路 ${i + 1}" }, episodes = episodes)
    }.filter { it.episodes.isNotEmpty() }
}
```

容错要点：

- 集名可能为空（`$https://...`），空则用"第 N 集"补；
- 地址可能含 `#`（极少见，如切片参数），因此**先按 `$$$` 切线路，再按 `#` 切集**，最后只按**第一个** `$` 切分，不贪心；
- 地址可能被 URL 编码，播放前 `decodeURIComponent` 一次并保留原始串用于重试；
- 部分站点用 `$$$` 结尾，需过滤尾空段。

### 2.5 搜索

```
GET {api}?wd={keyword}&ac=videolist&pg=1
GET {api}?wd={keyword}&ac=detail          （部分站点直接返回详情级字段）
```

返回结构与 §2.2 相同（`list`）。搜索无结果时：`code` 可能仍为 `1`，`list` 为空数组——**以 `list` 是否为空判定，不以 `code` 判定**。

### 2.6 字段别名映射（必须实现）

真实源字段命名不统一，客户端应有别名表，按优先级取值：

| 内部字段 | 别名（按顺序尝试） |
|---|---|
| `name` | `vod_name`, `name`, `title`, `video_name` |
| `pic` | `vod_pic`, `pic`, `cover`, `poster`, `vod_pic_thumb` |
| `remark` | `vod_remarks`, `note`, `remarks`, `state` |
| `year` | `vod_year`, `year` |
| `area` | `vod_area`, `area` |
| `typeName` | `type_name`, `typeName`, `class` |
| `content` | `vod_content`, `content`, `desc`, `description`, `blurb` |
| `playFrom` | `vod_play_from`, `play_from` |
| `playUrl` | `vod_play_url`, `play_url`, `urls` |
| `id` | `vod_id`, `id`, `ids` |

得分/评分、豆瓣 ID 这类可选字段缺失时显示为空，不显示"暂无"占位堆叠。

---

## 3. JS 源（drpy 约定）

### 3.1 形态

一个 JS 源 = 一份脚本文件，导出 `rule` 对象，由客户端（或宿主 App 的准星/JS 引擎）在沙箱中求值。TVBox 生态里常见两种写法：

```js
// 写法 A（TVBox 内置 JS 引擎，Rhino / QuickJS）
var rule = { /* ... */ }
```

```js
// 写法 B（drpy-node / 现代宿主，ESM）
export default { /* ... */ }
```

客户端判断：文本中出现 `var rule =` → 写法 A；出现 `export default` 或 `module.exports` → 写法 B。两种都必须支持（写法 B 在注入前替换为 `var rule =`）。

### 3.2 rule 字段

| 字段 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `title` | string | 是 | 源名称（UI 展示） |
| `host` | string | 是 | 站点主域，如 `https://example.com` |
| `homeUrl` | string | 否 | 首页推荐接口（相对 host 或绝对） |
| `url` | string | 否 | 分类列表接口模板，支持占位符 |
| `searchUrl` | string | 否 | 搜索接口模板，`**` 代表关键词 |
| `detailUrl` | string | 否 | 详情接口模板 |
| `class_name` | string | 否 | 分类名，`&` 分隔（如 `示例分类·电影&示例分类·剧集`） |
| `class_url` | string | 否 | 分类 ID，`&` 分隔，与 `class_name` 一一对应 |
| `class_parse` | string | 否 | 分类解析表达式；`"1&2"` 表静态，`js:` 前缀表动态求值 |
| `filter_url` | string | 否 | 筛选接口 |
| `filter_def` | object | 否 | 筛选默认值 |
| `searchable` | int | 否 | 1 可搜 / 2 仅精确 |
| `quickSearch` | int | 否 | 是否快速搜索 |
| `filterable` | int | 否 | 是否支持筛选 |
| `headers` | object | 否 | 请求头 |
| `play_parse` | bool | 否 | 是否需要二次解析播放地址 |
| `lazy` | bool | 否 | 播放地址是否懒解析（点了才请求） |
| `timeout` | int | 否 | 单请求超时 ms |
| `一级` | string | 否 | 列表页解析规则（见 §3.3） |
| `二级` | string | 否 | 详情页解析规则 |
| `搜索` | string | 否 | 搜索解析规则 |
| `推荐` | string | 否 | 首页推荐解析规则 |
| `double` | bool | 否 | 是否双列展示 |
| `tab_ex` | string | 否 | 分类分组表达式（`电视&电影`） |

### 3.3 规则字符串语法

解析规则是**分号分隔的"变量=表达式"清单**，变量供模板复用。表达式前缀决定解析方式：

| 前缀 | 含义 | 例子 |
|---|---|---|
| `json:` | 按 JSON 路径取值，`;` 分段，`a.b` 用点，数组下标用 `[0]` | `json:list;vod_name;vod_pic` |
| `jsp:` | JSON + 可选 `{...}` 表达式 | `jsp:list;vod_name` |
| `css:` | CSS 选择器 | `css:.item .title;text` |
| `js:` | 内联 JS 表达式 | `js:let d=JSON.parse(html); d.list` |
| `html:` | 正则/HTML 提取 | `html:<a href="(.*?)">` |
| `*` | 透传（不做解析，常用于详情页整体交给二级） | `二级:*` |

`;` 同时承担"字段分隔"职责，因此规则里不能出现裸 `;`，需要时用 `\;` 转义（drpy 约定）。

### 3.4 最小可执行 JS 样例（假数据）

```js
var rule = {
  title: '示例站·JS',
  host: 'https://example.com',
  homeUrl: '/api/vod?ac=list',
  url: '/api/vod?ac=videolist&t=fypage&pg=fnodata',
  searchUrl: '/api/vod?ac=videolist&wd=**&pg=fypage',
  detailUrl: '/api/vod?ac=detail&ids=fyid',
  class_name: '示例分类·电影&示例分类·剧集',
  class_url: '1&2',
  searchable: 1,
  quickSearch: 1,
  filterable: 0,
  headers: { 'User-Agent': 'ExampleClient/1.0' },
  play_parse: false,
  lazy: false,
  timeout: 8000,
  一级: 'json:list;vod_name;vod_pic;vod_remarks;vod_id',
  二级: 'json:list;vod_name;vod_content;vod_play_from;vod_play_url',
  搜索: 'json:list;vod_name;vod_pic;vod_remarks;vod_id'
}
```

> 该样例命中 §2 的 CMS JSON 接口，因此可以零额外代码运行：客户端把 `**` 换成关键词、`fypage` 换成页码、`fyid` 换成 ID 即可。

### 3.5 函数式 JS 源（drpy 兼容的"可执行点"）

当规则字符串无法覆盖时，脚本可导出/定义以下函数，客户端按需调用。**这是 JS 源的可执行点清单**：

```js
// 每个函数均可选；返回值必须是 §3.6 的标准结构
function init(cfg) { /* 注入 ext/headers，一次 */ }
function home() { /* 返回推荐分类+影片 */ }
function homeVod() { /* 仅返回推荐影片列表 */ }
function category(tid, pg, filter, extend) { /* 分类列表 */ }
function detail(id) { /* 影片详情 */ }
function search(wd, quick, pg) { /* 搜索 */ }
function play(flag, id, flags) { /* 返回 {parse:0,url,jit} 或 {parse:1,url} */ }
function proxy(params) { /* 代理请求（防盗链/解密），可选 */ }
function action(name, value) { /* 动作（收藏/取消），可选 */ }
```

调用顺序（客户端实现必须与此一致）：

```
导入 → init(ext)
首页 → home() 失败则 homeVod() 失败则 category(第一个分类)
列表 → category(tid, pg, filter, extend)
详情 → detail(id)
搜索 → search(wd, quick, pg)
播放 → play(flag, id, flags)
```

### 3.6 函数返回值标准结构

```js
// home() / category() / search() 返回（分类 + 影片）
{
  "class": [
    { "type_id": "1", "type_name": "示例分类·电影" }
  ],
  "filters": {
    "1": [ { "key": "year", "name": "年份", "value": [ {"n":"全部","v":""}, {"n":"2024","v":"2024"} ] } ]
  },
  "list": [
    { "vod_id": "101", "vod_name": "示例影片 A", "vod_pic": "https://example.com/img/a.jpg", "vod_remarks": "更新至 8 集" }
  ],
  "page": 1,
  "pagecount": 12,
  "limit": 20,
  "total": 236
}
```

```js
// detail(id) 返回
{
  "list": [
    {
      "vod_id": "101",
      "vod_name": "示例影片 A",
      "vod_pic": "https://example.com/img/a.jpg",
      "vod_content": "示例简介。",
      "vod_play_from": "示例线路一$$$示例线路二",
      "vod_play_url": "第1集$https://example.com/play/a1.m3u8#第2集$https://example.com/play/a2.m3u8"
    }
  ]
}
```

```js
// play(flag, id, flags) 返回
{ "parse": 0, "url": "https://example.com/play/a1.m3u8", "header": { "Referer": "https://example.com/" } }
// parse=0 直链可播；parse=1 表示 url 是网页，需要走 parses 解析链
```

### 3.7 JS 沙箱约束（安全与稳定）

| 约束 | 值 | 说明 |
|---|---|---|
| 执行超时 | 单函数 10s（硬中断） | 超时视为该源失败，不影响其他源 |
| 内存上限 | 64MB | 防止 `while(true)` + 大数组 |
| 网络白名单 | 默认允许 http(s)，可选"仅 rule.host 同域"严格模式 | 严格模式防数据外传 |
| 禁用能力 | 文件读写、反射、`eval` 之外的动态加载、原生桥 | 引擎侧关闭 |
| 请求次数 | 单次调用 ≤ 20 次 | 超限中断 |
| 输出大小 | 单次返回 ≤ 4MB | 超限截断并报错 |
| 编码 | 默认 UTF-8，可声明 `charset=gbk` | 响应头优先 |

JS 引擎选型建议：QuickJS（体积小、可中断）或 Rhino（兼容老脚本）。**不要**用 WebView 跑脚本（无法可靠中断、内存不可控）。

---

## 4. PY 源

### 4.1 三种落地形态（选其一，客户端必须能识别）

| 形态 | 描述 | 客户端职责 |
|---|---|---|
| **A. 远端 CMS 化**（最常见） | 有人把 py 脚本跑在服务端，暴露成 §2 的 JSON 接口；用户拿到的 `.py` 只是"来源凭证" | 只需按 §2 请求，`type=PY` 仅作标签展示 |
| **B. 直连 py 服务** | 源地址是 `http://host:port/api/...`，由 Python 服务实现同一契约 | 同 A，只是域名不同 |
| **C. 本地解释器** | 客户端内嵌 Python（Chaquopy 等），直接执行 `.py` | 需实现 §4.2 的函数契约 + 沙箱 |

本设计建议：**v1 优先支持 A/B**，C 作为可选能力（体积 +100MB 以上，且沙箱风险高）。若实现 C，必须默认关闭，且在设置中明确提示"执行第三方脚本"。

### 4.2 PY 脚本契约（形态 C 与宿主侧通用）

```python
# 示例站·PY  （全部为假数据）

rule = {
    "title": "示例站·PY",
    "host": "https://example.com",
    "searchable": 1,
    "quick_search": 1,
    "timeout": 8,
}

def init(cfg: dict) -> None:
    """注入 ext / headers，一次"""

def home() -> dict:
    """返回 {'class': [...], 'list': [...]}"""

def category(tid: str, pg: int = 1, flt: dict | None = None) -> dict:
    """分类列表"""

def detail(vod_id: str) -> dict:
    """返回 {'list': [ {...vod_content, vod_play_from, vod_play_url} ]}"""

def search(wd: str, quick: bool = False, pg: int = 1) -> dict:
    """搜索"""

def play(flag: str, play_id: str) -> dict:
    """返回 {'parse': 0, 'url': '...', 'header': {...}}"""
```

约定：

- 函数全部可选（缺失则视为不支持该能力，UI 相应入口置灰）。
- 返回值只允许是 **dict/list/str/int/bool**，禁止返回自定义对象（无法跨语言序列化）。
- 异常一律抛出，由宿主捕获转"源错误"；不要 `print` 当作返回。
- 禁止在模块导入时执行网络请求（会拖慢冷启动），网络请求只在函数内做。

### 4.3 最小可执行 PY 样例（形态 C，可直接跑）

```python
# coding: utf-8
import json
from urllib.parse import urlencode
from urllib.request import Request, urlopen

HOST = "https://example.com"

def _get(path, params):
    url = HOST + path + "?" + urlencode(params)
    req = Request(url, headers={"User-Agent": "ExampleClient/1.0"})
    with urlopen(req, timeout=8) as r:
        raw = r.read()
    # 站点可能返回 GBK，先尝试 utf-8
    try:
        return json.loads(raw.decode("utf-8"))
    except UnicodeDecodeError:
        return json.loads(raw.decode("gbk", errors="ignore"))

def init(cfg):
    pass

def home():
    return _get("/api/vod", {"ac": "list"})

def category(tid, pg=1, flt=None):
    data = _get("/api/vod", {"ac": "videolist", "t": tid, "pg": pg})
    return {"list": data.get("list", []),
            "page": pg,
            "pagecount": data.get("pagecount", 1)}

def detail(vod_id):
    return _get("/api/vod", {"ac": "detail", "ids": vod_id})

def search(wd, quick=False, pg=1):
    return _get("/api/vod", {"ac": "videolist", "wd": wd, "pg": pg})

def play(flag, play_id):
    # 示例：直接透传，实际站点可能需要二次解析
    return {"parse": 0, "url": play_id, "header": {"Referer": HOST + "/"}}

rule = {"title": "示例站·PY", "host": HOST, "searchable": 1, "quick_search": 1}
```

### 4.4 形态 A 的最小样例（把 py 包装成 CMS JSON 服务）

```python
# 仅演示契约；生产环境请用 Flask/FastAPI + 缓存
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs
import json

def build(ac, q):
    if ac == "list":
        return {"code": 1, "class": [
            {"type_id": 1, "type_name": "示例分类·电影"},
            {"type_id": 2, "type_name": "示例分类·剧集"}]}
    if ac in ("videolist", "detail"):
        return {"code": 1, "page": 1, "pagecount": 1, "limit": 20, "total": 1,
                "list": [{
                    "vod_id": 101,
                    "vod_name": "示例影片 A",
                    "vod_pic": "https://example.com/img/a.jpg",
                    "vod_remarks": "更新至 8 集",
                    "vod_play_from": "示例线路一",
                    "vod_play_url": "第1集$https://example.com/play/a1.m3u8"
                }]}
    return {"code": 0, "msg": "unsupported ac"}

class H(BaseHTTPRequestHandler):
    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        body = json.dumps(build(q.get("ac", "list"), q), ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.end_headers()
        self.wfile.write(body)

if __name__ == "__main__":
    HTTPServer(("0.0.0.0", 8090), H).serve_forever()
```

导入地址即 `http://<host>:8090/`，客户端按 §2 请求，与普通 JSON 源完全一致。

### 4.5 PY 与 JS 的取舍

| 维度 | JS（drpy） | PY |
|---|---|---|
| 客户端体积 | +2~8MB（QuickJS/Rhino） | +100MB 起（Chaquopy） |
| 生态脚本量 | 多（drpy 生态为主） | 中（多在服务端） |
| 沙箱可控性 | 较好（可中断） | 较差（需进程隔离） |
| 推荐 | **v1 必须支持** | v1 支持 A/B 形态；C 延后 |

---

## 5. 导入流程与校验

### 5.1 四个入口

| 入口 | 处理 |
|---|---|
| 链接 | 输入框粘贴 → 拉取（8s 超时，2 次重试，退避 400/1200ms） |
| 剪贴板 | 启动时读剪贴板（仅前台且用户点按"粘贴"后），匹配 `http(s)://` 或 `{`/`var rule` 开头 |
| 本地文件 | SAF 选择器，接受 `.json` / `.js` / `.py`，大小 ≤ 2MB |
| 二维码 | 扫码得到内容，走"链接"或"文本"分支 |

### 5.2 类型识别（嗅探顺序）

```
1. 内容是 JSON 且含 sites/parses/lives      → TVBOX_CONFIG
2. 内容是 JSON 且含 urls/repos（元素有 name+url） → MULTI_REPO
3. 内容含 "var rule =" / "export default"    → JS_SOURCE
4. 内容含 "def " / "class " + "import" 且 .py → PY_SOURCE
5. 内容是 URL：
     URL 后缀 .json → 拉取后回到 1/2
     URL 后缀 .js   → JS_SOURCE
     URL 后缀 .py   → PY_SOURCE
     其他           → 拉取后按内容嗅探（1~4）
```

嗅探只看前 8KB，且**先判 JSON 再判脚本关键字**（避免 JSON 里含 `var rule` 字符串误判）。

### 5.3 校验清单（导入前必须全过）

| 检查 | 不通过时 |
|---|---|
| 结构合法性（必需字段存在） | 阻断，提示"不是受支持的源格式" |
| `sites[].key` 唯一 | 合并重复项，保留先到的 |
| `api` 为合法 URL 或 `csp_` 类名 | 该站点标记为"不可用"，仍可导入但默认关闭 |
| 条目数 ≤ 500 | 阻断（防恶意超大配置） |
| 总大小 ≤ 2MB | 阻断 |
| 已知广告域名出现在 `api` | 警告，不阻断 |
| 与本地已有源 key 冲突 | 预览标"已存在"，默认不勾选 |
| 探测可用性（并发 4，单站 3s） | 失败不阻断，卡片标红点 |

### 5.4 归一化与合并

```
Site → InternalSource(
  id = sha1(type + "|" + key + "|" + api).take(16),
  type, name, url, ext, jar, headers, enabled, groupId = 配置文件 id
)
```

- 同一 `id` 重复导入：**更新配置，不新增**（保留用户启停状态与自定义排序）。
- 从多仓切换仓库：旧仓站点不自动删除，标为"来自仓库 A"可批量清理。
- 导出：`sites` 按 `position` 排序，输出标准 JSON（便于回灌到 TVBox）。

### 5.5 探测与状态

| 状态 | 判定 |
|---|---|
| 🟢 可用 | 分类请求 200 且能解析出 ≥ 1 个分类，耗时 < 3s |
| 🟡 超时 | 3s 未返回或返回 5xx |
| 🔴 失败 | 解析异常 / 4xx / 内容为空 |
| ⚪ 未检测 | 导入后未探测，或用户关闭自动探测 |

探测结果缓存 30 分钟；播放失败时对该源做一次强制复检。

---

## 6. 解析容错规范

### 6.1 请求层

| 项 | 规范 |
|---|---|
| 超时 | 连接 5s / 读取 8s（可配） |
| 重试 | 仅对幂等 GET，最多 2 次，退避 400 / 1200ms |
| UA | 优先 site.headers，其次全局 headers，最后默认 UA |
| Referer | 默认取 host 根域，播放地址取站点根域 |
| 重定向 | 跟随 ≤ 5 跳 |
| 压缩 | 支持 gzip/br；不支持时降级重发 |
| 编码 | 响应头 charset → HTML meta → 默认 UTF-8 → 失败尝试 GBK |
| 缓存 | 分类 10min / 列表 3min / 详情 10min / 搜索不缓存；ETag 优先 |

### 6.2 结构层

- `list` 不存在或非数组 → 视为空结果，不抛异常给 UI。
- 单条记录字段缺失 → 用默认值（封面走柔光占位，简介留空不占位）。
- `page/pagecount` 缺失 → 按 `list.size < limit` 推断是否还有下一页。
- `code` 非 1 但有 `list` → 以 `list` 为准（大量站点 `code` 语义不规范）。
- 详情 `list` 为空 → 回退用列表页已缓存的字段展示，并提示"详情暂不可用"。

### 6.3 时间与数字

- `vod_year` 可能是 `2024` / `2024年` / `2024-06-01`：统一取前 4 位数字。
- 集数 `更新至 8 集` / `更新至第8集` / `HD`：只做展示，不做解析（不做"集数推断"这种脆弱逻辑）。
- 时长若为 `00:42:10` 转秒；若为 `42分钟` 用正则取数字 ×60。

---

## 7. 安全与合规

| 风险 | 措施 |
|---|---|
| 恶意 jar | 校验来源域名白名单（可配）、记录 sha256、大小 ≤ 15MB；首次加载弹确认 |
| 脚本外传数据 | JS 严格模式下仅允许 `rule.host` 同域；PY 形态 C 默认禁用，启用需二次确认 |
| 超大配置 DoS | 条目/大小/深度上限（§5.3） |
| 无限循环脚本 | 执行超时硬中断 + 内存上限（§3.7） |
| 明文敏感信息 | 源 URL 中的 `token`/`key` 参数在日志中脱敏 |
| 版权提示 | 首启页与源导入页展示"源由用户自行提供，应用仅作为播放器"声明 |
| 广告过滤 | `ads` 域名命中直接返回空响应，不弹错误 |
| 播放地址劫持 | 仅允许 http/https/rtsp，禁止 `file://`、`intent://` 等 |

---

## 8. 测试夹具（假数据）

建议放在 `app/src/test/resources/fixtures/`：

| 文件 | 用途 |
|---|---|
| `config_min.json` | 1 个 JSON 站点 + 1 个 spider 站点 |
| `config_multi_repo.json` | `{urls:[{name,url}]}` 两仓 |
| `config_bare_repo.json` | 顶层数组形态 |
| `cms_list.json` | 分类 + 列表 |
| `cms_detail.json` | 含双线路、空集名、尾 `$$$` |
| `cms_gbk.xml` | GBK 编码 XML 列表 |
| `js_min.js` | §3.4 的规则式脚本 |
| `js_func.js` | §3.5 的函数式脚本（全部函数 mock） |
| `py_min.py` | §4.3 脚本（用本地 stub 替换网络） |
| `bad_*.json` | 缺字段 / 类型错误 / 超大条目 / 循环引用 |

必测断言：

1. `$`/`#`/`$$$` 三级分隔全部正确，含空集名与尾分隔符。
2. 同一 key 二次导入 = 更新而非新增。
3. 单站探测超时不影响其他源渲染。
4. JS 死循环 10s 被中断且 UI 有错误态。
5. GBK XML 正确解码，片名不乱码。
6. 多仓递归深度 2 次后停止。

---

## 9. 客户端接口草图（Kotlin）

```kotlin
interface SourceAdapter {
    val kind: SourceKind                 // JSON_CONFIG / CMS_JSON / CMS_XML / SPIDER / JS / PY
    suspend fun categories(src: Source): Result<List<Category>>
    suspend fun list(src: Source, cat: Category, page: Int, filter: Map<String, String>): Result<Paged<VodBrief>>
    suspend fun detail(src: Source, vodId: String): Result<VodDetail>
    suspend fun search(src: Source, keyword: String, page: Int): Result<Paged<VodBrief>>
    suspend fun play(src: Source, line: Line, ep: Episode): Result<PlayInfo>
}

data class Paged<T>(val items: List<T>, val page: Int, val pageCount: Int?)
data class Line(val name: String, val episodes: List<Episode>)
data class Episode(val title: String, val url: String, val index: Int)
data class PlayInfo(val url: String, val headers: Map<String, String>, val needParse: Boolean)
```

实现分工：

| kind | 实现 |
|---|---|
| CMS_JSON | 一个 `CmsJsonAdapter`，覆盖 90% 源 |
| CMS_XML | `CmsXmlAdapter`（XmlPullParser，GBK 支持） |
| SPIDER | `SpiderAdapter`（反射调 jar 类；接口签名为 `homeContent/filterContent/detailContent/searchContent/playerContent`） |
| JS | `JsAdapter`（QuickJS/Rhino 沙箱 + rule 求值） |
| PY | v1：`CmsJsonAdapter` 复用（形态 A/B）；v2：`PyAdapter`（Chaquopy） |

---

## 10. 交付对照

| 需求 | 落地位置 |
|---|---|
| 在线 JSON 源导入解析 | §1 + §2 + `CmsJsonAdapter` |
| 在线 JS 源导入解析 | §3 + `JsAdapter`（QuickJS） |
| 在线 PY 源导入解析 | §4（A/B 形态必做，C 延后） |
| 本地 json/js/py 导入 | §5.1 本地文件入口 + §5.2 嗅探（同一套解析器） |
| 源管理 UI | 设计文档 §5.9 / §7.4 |
| 三态与错误可恢复 | 设计文档 §5.10 + 本文 §6 |
