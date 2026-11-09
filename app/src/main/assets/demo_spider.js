/*
 * 柔光影视 · JS 源示例（QuickJS 执行）
 * -----------------------------------------------------------
 * 协议：脚本顶层定义 rule（元信息），并实现以下可选函数：
 *   home()                     -> 首页区块数组（JSON）
 *   category(tid, page)        -> 分类内容数组
 *   search(keyword)            -> 搜索结果数组
 *   detail(id)                 -> 详情对象
 *   player(flag, id)           -> { url, headers, title }（可选）
 * 返回值为 JS 对象/数组，引擎会自动 JSON.stringify。
 *
 * 内置同步 HTTP 助手（由 Kotlin 桥接，阻塞执行）：
 *   http_get(url, headers)     -> 字符串
 *   http_post(url, body, headers) -> 字符串
 *   http_json(url, headers)    -> 解析后的对象
 *   base64_encode(str), md5(str), log(str)
 *
 * 数组项字段：id,title,poster,backdrop,remarks,year,area,typeName,score
 * 详情字段：description,actors,director,groups:[{title,episodes:[{name,url}]}]
 */
var rule = {
    title: "柔光 JS 示例源",
    siteKey: "js_demo",
    siteName: "JS 放映厅",
    type: 3,
    searchable: true
};

var BASE = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/";

var LIBRARY = [
    { id: "js_bbb", title: "大雄兔 · JS 线路", image: "BigBuckBunny", year: "2008", type: "动画短片", remarks: "QuickJS 生成", score: "9.4" },
    { id: "js_sintel", title: "辛特尔 · JS 线路", image: "Sintel", year: "2010", type: "动画短片", remarks: "脚本聚合", score: "9.2" },
    { id: "js_tears", title: "钢铁之泪 · JS 线路", image: "TearsOfSteel", year: "2012", type: "科幻短片", remarks: "可播放", score: "8.8" },
    { id: "js_elephants", title: "大象之梦 · JS 线路", image: "ElephantsDream", year: "2006", type: "实验短片", remarks: "开放版权", score: "8.4" }
];

function buildItem(m) {
    var url = BASE + "images/" + m.image + ".jpg";
    return {
        id: m.id,
        title: m.title,
        poster: url,
        backdrop: url,
        remarks: m.remarks,
        year: m.year,
        area: "美国",
        typeName: m.type,
        score: m.score,
        siteKey: rule.siteKey,
        siteName: rule.siteName
    };
}

function home() {
    // 一段真实执行的 JS 逻辑：按评分过滤并排序
    var items = LIBRARY.sort(function (a, b) { return parseFloat(b.score) - parseFloat(a.score); })
        .filter(function (m) { return parseFloat(m.score) >= 8.4; })
        .map(buildItem);
    return [
        {
            title: "JS 引擎 · 正在运行",
            subtitle: "QuickJS 实时计算 · 共 " + items.length + " 部",
            style: "ROW",
            items: items
        }
    ];
}

function category(tid, page) {
    return LIBRARY.map(buildItem);
}

function search(keyword) {
    var k = String(keyword || "").toLowerCase();
    return LIBRARY.filter(function (m) {
        return m.title.toLowerCase().indexOf(k) >= 0 || m.type.toLowerCase().indexOf(k) >= 0;
    }).map(buildItem);
}

function detail(id) {
    var m = null;
    for (var i = 0; i < LIBRARY.length; i++) {
        if (LIBRARY[i].id === id) { m = LIBRARY[i]; break; }
    }
    if (!m) return null;
    return {
        description: "本条目由内嵌 QuickJS 引擎执行脚本后实时返回。" +
            "脚本可继续接入 http_get 抓取远端接口，协议说明见 README。",
        actors: "开放版权影片",
        director: "Blender Foundation",
        groups: [
            {
                title: "JS 线路",
                episodes: [
                    { name: "高清 MP4", url: BASE + m.image + ".mp4" },
                    { name: "HLS 自适应", url: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" }
                ]
            }
        ]
    };
}

function player(flag, id) {
    var m = null;
    for (var i = 0; i < LIBRARY.length; i++) {
        if (LIBRARY[i].id === id) { m = LIBRARY[i]; break; }
    }
    if (!m) return null;
    return { url: BASE + m.image + ".mp4", title: m.title };
}
