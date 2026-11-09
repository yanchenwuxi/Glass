package com.glassbox.app.engine

import android.util.Base64
import android.util.Log
import app.cash.quickjs.QuickJs
import com.glassbox.app.core.Net
import com.glassbox.app.data.*
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest

/** QuickJS 只能绑定接口，因此桥接能力必须声明为 interface */
interface JsNative {
    fun get(url: String, headersJson: String): String
    fun post(url: String, body: String, headersJson: String): String
    fun base64(input: String): String
    fun base64Decode(input: String): String
    fun md5(input: String): String
    /** drpy 的 pdfh/pdfa：原版靠 cheerio，这里用 jsoup 等价实现（见 HtmlDom）。 */
    fun pdfh(html: String, selector: String): String
    fun pdfa(html: String, selector: String): String
    fun log(message: String)
}

class JsBridge : JsNative {
    override fun get(url: String, headersJson: String): String = kotlinx.coroutines.runBlocking {
        Net.get(url, headers(headersJson))
    }

    override fun post(url: String, body: String, headersJson: String): String = kotlinx.coroutines.runBlocking {
        Net.post(url, body, headers = headers(headersJson))
    }

    override fun base64(input: String): String =
        Base64.encodeToString(input.toByteArray(), Base64.NO_WRAP)

    override fun base64Decode(input: String): String = runCatching {
        String(Base64.decode(input, Base64.DEFAULT))
    }.getOrDefault("")

    override fun pdfh(html: String, selector: String): String = HtmlDom.pdfh(html, selector)

    override fun pdfa(html: String, selector: String): String =
        Gson().toJson(HtmlDom.pdfa(html, selector))

    override fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    override fun log(message: String) {
        Log.d("GlassJS", message.take(500))
    }

    private fun headers(json: String): Map<String, String> = runCatching {
        if (json.isBlank() || json == "{}") emptyMap()
        else {
            val obj = JsonParser.parseString(json).asJsonObject
            obj.entrySet().associate { it.key to it.value.asString }
        }
    }.getOrDefault(emptyMap())
}

object JsExecutor {
    private const val PRELUDE = """
var __hdr = function (h) { return h ? (typeof h === 'string' ? h : JSON.stringify(h)) : '{}'; };
var http = {
  get: function (u, h) { return __native.get(String(u), __hdr(h)); },
  post: function (u, b, h) { return __native.post(String(u), String(b === null || b === undefined ? '' : b), __hdr(h)); }
};
function http_get(u, h) { return __native.get(String(u), __hdr(h)); }
function http_post(u, b, h) { return __native.post(String(u), String(b === null || b === undefined ? '' : b), __hdr(h)); }
function http_json(u, h) { return JSON.parse(http_get(u, h)); }
var request = function (u, o) {
  o = o || {};
  var h = o.headers || {};
  var m = String(o.method || 'GET').toUpperCase();
  return m === 'POST'
    ? __native.post(String(u), String(o.body === undefined || o.body === null ? '' : o.body), __hdr(h))
    : __native.get(String(u), __hdr(h));
};
// TVBox 函数式脚本的 req 返回 {code, content, headers}；桥接是同步的，await 到它就是普通对象。
var req = function (u, o) {
  var r = request(u, o);
  return { code: 200, content: String(r === undefined || r === null ? '' : r), headers: {} };
};
function getHtml(u, h) { return __native.get(String(u), __hdr(h)); }
function base64_encode(s) { return __native.base64(String(s)); }
function base64Encode(s) { return __native.base64(String(s)); }
function base64Decode(s) { return __native.base64Decode(String(s)); }
function md5(s) { return __native.md5(String(s)); }
function log(s) { __native.log(String(s)); }
var console = {
  log: function (s) { __native.log(String(s)); },
  warn: function (s) { __native.log(String(s)); },
  error: function (s) { __native.log(String(s)); },
  info: function (s) { __native.log(String(s)); }
};
// drpy：模板字典会自动生长，脚本里 `muban.首图.二级.title = '…'` 这类赋值必须可用
var __mkMuban = function (store) {
  return new Proxy(store, {
    get: function (t, k) {
      if (typeof k !== 'string') return t[k];
      if (k === 'toJSON') return undefined;
      if (!(k in t)) t[k] = __mkMuban({});
      return t[k];
    },
    set: function (t, k, v) { t[k] = v; return true; }
  });
};
var muban = __mkMuban({});
// drpy：pdfh/pdfa 原版由 cheerio 提供，这里由 Kotlin(jsoup) 实现
function pdfh(html, selector) { return __native.pdfh(String(html), String(selector)); }
function pdfa(html, selector) { return JSON.parse(__native.pdfa(String(html), String(selector))); }
function pd(html, selector) { return pdfh(html, selector); }
function unescape(s) {
  return String(s)
    .replace(/%u([0-9a-fA-F]{4})/g, function (_, h) { return String.fromCharCode(parseInt(h, 16)); })
    .replace(/%([0-9a-fA-F]{2})/g, function (_, h) { return String.fromCharCode(parseInt(h, 16)); });
}
function escape(s) {
  return String(s).replace(/[^@*_+-./A-Za-z0-9]/g, function (c) {
    return '%' + c.charCodeAt(0).toString(16).toUpperCase();
  });
}
function urljoin(base, path) {
  var p = String(path || '');
  if (/^https?:/i.test(p)) return p;
  var b = String(base || '').replace(/\/+$/, '');
  if (p.charAt(0) === '/') return b.replace(/(https?:\/\/[^/]+).*/, '$1') + p;
  return b + '/' + p.replace(/^\.?\//, '');
}
var joinUrl = urljoin;
// TVBox 本地代理（原版由宿主提供）；这里没有本地转发，直接返回原地址
function getProxyUrl(u) { return String(u); }
// 明确告知：node/cheerio 等模块未提供，避免静默失败
function require(name) {
  throw new Error('未提供的 Node 模块: ' + name + '（本桥接仅支持 muban/pdfh/pdfa/request/http/base64/md5）');
}
"""
    private const val INIT = """
try { if (typeof init === 'function') init(__ext); } catch (e) { log('init: ' + e); }
"""

    private val gson = Gson()

    suspend fun probe(raw: String): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        var code = raw
        try {
            code = SourceParser.normalizeExport(raw)
            val result = withTimeout(15000) {
                run(code, "JSON.stringify({meta:(typeof rule==='object'?rule:{})," +
                    "rule:(typeof rule==='object'?rule:{}),muban:(typeof muban==='object'?muban:{})," +
                    "home:(typeof home==='function'?home():(typeof homeVod==='function'?homeVod():null))})")
            }
            val obj = JsonParser.parseString(result).asJsonObject
            val meta = obj.getAsJsonObject("meta")
            val ruleObj = obj.getAsJsonObject("rule")
            val mubanObj = obj.getAsJsonObject("muban")
            val drpy = ruleObj != null && (
                ruleObj.get("模板") != null || ruleObj.get("url") != null ||
                    ruleObj.get("一级") != null || ruleObj.get("二级") != null
                )
            val title = SpiderJson.str(meta, "siteName", "title", "name").ifBlank { "JS 源" }
            val key = SpiderJson.str(meta, "siteKey", "key").ifBlank { "js_" + Integer.toHexString(code.hashCode()) }
            val ext = if (drpy) {
                JsonObject().apply {
                    add("__jsRule", ruleObj)
                    if (mubanObj != null && mubanObj.size() > 0) add("__jsMuban", mubanObj)
                }.toString()
            } else meta.toString()
            val site = SiteDef(
                key = key,
                name = title,
                api = "js://$key",
                type = 3,
                searchable = true,
                ext = ext,
                sourceId = "",
                sourceName = title
            )
            if (drpy) {
                // drpy 规则源：先跑一遍首页，导入时就能看到「真实可用」或「host 不可达」。
                val rule = DrpyRule(ruleObj, mubanObj ?: JsonObject(), site)
                val sections = withTimeout(20000) { rule.home() }
                val preview = sections.sumOf { it.items.size }
                return@withContext ProbeResult(
                    ok = preview > 0,
                    message = if (preview > 0)
                        "drpy 规则源（模板=${SpiderJson.str(ruleObj, "模板").ifBlank { "默认" }}）：首页 ${sections.size} 个分区 · $preview 项"
                    else "drpy 规则源可执行，但首页没有解析到条目（检查 host=${rule.host} 或选择器）",
                    sites = listOf(site),
                    sample = sections.firstOrNull()?.items?.firstOrNull()?.title,
                    elapsedMs = System.currentTimeMillis() - started
                )
            }
            val home = unwrap(obj.get("home"))
            var sections = SpiderJson.sections(home, site)
            var preview = sections.sumOf { it.items.size }
            var fromHomeVod = false
            if (preview == 0) {
                // home() 只返回 class 没有 list 的脚本（独播库等），用 homeVod() 取真实片单。
                val hvRaw = run(code, "JSON.stringify(typeof homeVod==='function'?homeVod():null)")
                val hv = unwrap(runCatching { JsonParser.parseString(hvRaw) }.getOrNull())
                val listEl = if (hv != null && hv.isJsonObject) hv.asJsonObject.get("list") ?: hv else hv
                val items = SpiderJson.items(listEl, site)
                if (items.isNotEmpty()) {
                    sections = listOf(HomeSection("推荐", "", items))
                    preview = items.size
                    fromHomeVod = true
                }
            }
            val ruleUsable = SpiderJson.str(meta, "homeUrl", "url").isNotBlank()
            val ok = preview > 0 || ruleUsable
            ProbeResult(
                ok = ok,
                message = when {
                    preview > 0 -> "QuickJS 执行 ${if (fromHomeVod) "homeVod()" else "home()"}，" +
                        "返回 $preview 项 · ${sections.size} 个分区"
                    ruleUsable -> "rule 模板可执行（home 未直接返回数据）"
                    else -> "脚本可执行，但 home()/homeVod() 未返回任何内容"
                },
                sites = listOf(site),
                sample = sections.firstOrNull()?.items?.firstOrNull()?.title,
                elapsedMs = System.currentTimeMillis() - started
            )
        } catch (t: Throwable) {
            ProbeResult(
                ok = false,
                message = "JS 执行失败：${explain(t, code)}",
                sites = emptyList(),
                elapsedMs = System.currentTimeMillis() - started
            )
        }
    }

    /**
     * 把 QuickJS 的原始报错翻译成可判定的原因：
     * - ReferenceError 指向 drpy/cheerio 运行时（muban/pdfh/pdfa…）→ 属于「未提供的 API」，不是桥接 bug；
     * - 其余语法/类型错误原样给出，便于定位脚本本身。
     */
    private fun explain(t: Throwable, code: String = ""): String {
        if (t is kotlinx.coroutines.TimeoutCancellationException) return "执行超时（15s）"
        val raw = t.message.orEmpty().trim()
        val missing = Regex("([A-Za-z_$][A-Za-z0-9_$]*) is not defined").find(raw)?.groupValues?.get(1)
        val nodeOnly = Regex("\\brequire\\s*\\(['\"](cheerio|crypto-js|axios|node-fetch|fs|path)['\"]|^\\s*import\\s.+from\\s" , RegexOption.MULTILINE)
            .containsMatchIn(code)
        if (nodeOnly) {
            return "脚本要求 Node/cheerio 运行时（本桥接未提供）：$raw"
        }
        if (missing != null) {
            return "脚本依赖 drpy 运行时 API（$missing），当前 QuickJS 桥未提供该能力"
        }
        return raw.ifBlank { t.javaClass.simpleName }
    }

    /**
     * 函数式 JS 源常把结果二次序列化成字符串（`return JSON.stringify({...})`），
     * 外层再 stringify 一次就变成 `"{\"list\":[...]}"`；这里剥掉字符串外壳再解析。
     */
    fun unwrap(el: JsonElement?): JsonElement? {
        var e = el ?: return null
        repeat(2) {
            val cur = e
            if (cur.isJsonPrimitive && cur.asJsonPrimitive.isString) {
                val s = cur.asString.trim()
                if (s.startsWith("{") || s.startsWith("[")) {
                    val parsed = runCatching { JsonParser.parseString(s) }.getOrNull()
                    if (parsed != null && (parsed.isJsonObject || parsed.isJsonArray)) e = parsed
                }
            }
        }
        return e
    }

    /** 一次执行同时探测可用函数，避免每个请求重复跑脚本 */
    suspend fun capabilities(code: String): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(12000) {
                val json = run(
                    code,
                    "JSON.stringify(['home','homeVod','category','search','detail','play'].filter(function(n){return typeof globalThis[n]==='function'}))"
                )
                JsonParser.parseString(json).asJsonArray.map { it.asString }.toSet()
            }
        }.getOrDefault(emptySet())
    }

    suspend fun call(code: String, expression: String, extJson: String = "{}"): String =
        withContext(Dispatchers.IO) {
            try {
                withTimeout(15000) { run(code, expression, extJson) }
            } catch (t: Throwable) {
                throw IllegalStateException(explain(t), t)
            }
        }

    private fun run(code: String, expression: String, extJson: String = "{}"): String {
        val js = QuickJs.create()
        try {
            // 实测 `_139.js` 首字节是 UTF-8 BOM；QuickJS 不认 BOM 会直接语法错误
            val script = desugarAsync(code.removePrefix("\uFEFF").trimStart())
            js.set("__native", JsNative::class.java, JsBridge())
            js.evaluate(PRELUDE)
            js.evaluate(script)
            val ext = if (extJson.trim().startsWith("{") || extJson.trim().startsWith("[")) extJson.trim() else "{}"
            js.evaluate("var __ext = $ext;\n$INIT")
            return when (val result = js.evaluate(expression)) {
                null -> "null"
                is String -> result
                is Number -> result.toString()
                is Boolean -> result.toString()
                else -> gson.toJson(result)
            }
        } finally {
            runCatching { js.close() }
        }
    }

    /**
     * 0.9.2 的 QuickJS 绑定没有 executePendingJob，async 函数的 Promise 永远 pending。
     * 桥接（req/http_get/request…）本身是同步的，因此把 `async function` 降级为普通函数、
     * 去掉 `await` 关键字即可让「函数式 JS 源」同步求值（返回 JSON 字符串的脚本也能直接拿到值）。
     */
    private fun desugarAsync(code: String): String {
        if (!Regex("\\basync\\b").containsMatchIn(code)) return code
        return code
            .replace(Regex("\\basync\\s+(function\\b)"), "$1")
            .replace(Regex("\\basync\\s*\\("), "(")
            .replace(Regex("\\bawait\\s+"), "")
    }
}

class JsSpider(private val source: SourceEntity, private val site: SiteDef) : SiteSpider {

    private var cachedCode: String? = null
    private var cachedCaps: Set<String>? = null

    private val ruleCms: RuleJsCms? by lazy {
        val ext = site.ext.orEmpty()
        if (!ext.trim().startsWith("{")) null
        else runCatching { RuleJsCms(JsonParser.parseString(ext).asJsonObject, site) }.getOrNull()
    }

    /** 导入时已把 rule/muban 快照存进 ext，这里还原成 drpy 规则执行器。 */
    private val drpy: DrpyRule? by lazy {
        val ext = site.ext.orEmpty().trim()
        if (!ext.startsWith("{")) return@lazy null
        val o = runCatching { JsonParser.parseString(ext).asJsonObject }.getOrNull() ?: return@lazy null
        val rule = o.getAsJsonObject("__jsRule") ?: run {
            if (o.get("模板") != null || o.get("url") != null || o.get("一级") != null) o else null
        } ?: return@lazy null
        DrpyRule(rule, o.getAsJsonObject("__jsMuban") ?: JsonObject(), site)
    }

    private suspend fun code(): String {
        cachedCode?.let { return it }
        val value = ScriptResolver.code(site, source)
        cachedCode = value
        return value
    }

    private suspend fun caps(): Set<String> {
        cachedCaps?.let { return it }
        val value = JsExecutor.capabilities(code())
        cachedCaps = value
        return value
    }

    private fun extJson(): String {
        val ext = site.ext.orEmpty().trim()
        // drpy 快照（__jsRule/__jsMuban）不能直接当脚本 ext 传进去，只取脚本自己的 ext 字段
        if (ext.startsWith("{")) {
            val o = runCatching { JsonParser.parseString(ext).asJsonObject }.getOrNull()
            if (o != null && o.has("__jsRule")) {
                return o.get("ext")?.toString() ?: "{}"
            }
            return ext
        }
        return "{}"
    }

    override suspend fun home(): List<HomeSection> {
        val capabilities = caps()
        if ("home" in capabilities) {
            val raw = JsExecutor.call(code(), "JSON.stringify(home())", extJson())
            val root = JsExecutor.unwrap(runCatching { JsonParser.parseString(raw) }.getOrNull())
            val sections = SpiderJson.sections(root, site)
            if (sections.isEmpty() && root != null && root.isJsonObject) {
                // 只返回 class 没有 list 时，按第一个分类再取一页
                val first = SpiderJson.categories(root.asJsonObject.get("class")).firstOrNull()
                if (first != null) {
                    val items = category(first.first, 1)
                    if (items.isNotEmpty()) {
                        return listOf(HomeSection(first.second, "来自 ${site.name}", items, SectionStyle.HERO))
                    }
                }
            }
            return sections
        }
        if ("homeVod" in capabilities) {
            val raw = JsExecutor.call(code(), "JSON.stringify(homeVod())", extJson())
            val root = JsExecutor.unwrap(runCatching { JsonParser.parseString(raw) }.getOrNull())
            val items = SpiderJson.items(
                if (root != null && root.isJsonObject) root.asJsonObject.get("list") ?: root else root,
                site
            )
            return if (items.isEmpty()) emptyList()
            else listOf(HomeSection("推荐", "来自 ${site.name}", items, SectionStyle.HERO))
        }
        return drpy?.home() ?: ruleCms?.home() ?: emptyList()
    }

    override suspend fun catalog(): SiteCatalog {
        val capabilities = caps()
        if ("home" in capabilities) {
            val raw = JsExecutor.call(code(), "JSON.stringify(home())", extJson())
            val root = JsExecutor.unwrap(runCatching { JsonParser.parseString(raw) }.getOrNull())
            val catalog = SpiderJson.catalog(root, site)
            if (catalog.categories.isNotEmpty() || catalog.fallbackItems.isNotEmpty()) return catalog
        }
        if ("homeVod" in capabilities) {
            val raw = JsExecutor.call(code(), "JSON.stringify(homeVod())", extJson())
            val root = JsExecutor.unwrap(runCatching { JsonParser.parseString(raw) }.getOrNull())
            val items = SpiderJson.items(
                if (root != null && root.isJsonObject) root.asJsonObject.get("list") ?: root else root,
                site
            )
            if (items.isNotEmpty()) return SiteCatalog(fallbackItems = items)
        }
        val rule = drpy
        if (rule != null) return rule.catalog()
        return ruleCms?.catalog() ?: SiteCatalog()
    }

    override suspend fun category(tid: String, page: Int, extend: Map<String, String>): List<MediaItem> {
        if ("category" !in caps()) return drpy?.category(tid, page, extend) ?: ruleCms?.category(tid, page) ?: emptyList()
        val filterJson = Gson().toJson(extend)
        val raw = JsExecutor.call(
            code(),
            "JSON.stringify(category(${gson(tid)}, $page, true, $filterJson))",
            extJson()
        )
        return itemsOf(raw)
    }

    override suspend fun search(keyword: String): List<MediaItem> {
        if ("search" !in caps()) return drpy?.search(keyword) ?: ruleCms?.search(keyword) ?: emptyList()
        val raw = JsExecutor.call(code(), "JSON.stringify(search(${gson(keyword)}, false, 1))", extJson())
        return itemsOf(raw)
    }

    override suspend fun detail(id: String): MediaDetail? {
        if ("detail" !in caps()) return drpy?.detail(id) ?: ruleCms?.detail(id)
        val raw = JsExecutor.call(code(), "JSON.stringify(detail(${gson(id)}))", extJson())
        val root = JsExecutor.unwrap(runCatching { JsonParser.parseString(raw) }.getOrNull())
        if (root == null || root.isJsonNull) return drpy?.detail(id) ?: ruleCms?.detail(id)
        return SpiderJson.detail(root, site, id)
    }

    override suspend fun resolvePlay(flag: String, id: String): PlaySource? {
        if (id.startsWith("http") && (".m3u8" in id || ".mp4" in id)) return PlaySource(id)
        // 函数式 JS 源：play(flag, id, flags) 通常直接返回 {parse:0, url, header}
        if ("play" in caps()) {
            val raw = JsExecutor.call(
                code(),
                "JSON.stringify(play(${gson(flag)}, ${gson(id)}, []))",
                extJson()
            )
            val root = JsExecutor.unwrap(runCatching { JsonParser.parseString(raw) }.getOrNull())
            if (root != null && root.isJsonObject) {
                val o = root.asJsonObject
                val url = SpiderJson.str(o, "url", "playUrl")
                if (url.startsWith("http")) {
                    val headerEl = o.get("header") ?: o.get("headers")
                    val headers = runCatching {
                        headerEl?.asJsonObject?.entrySet()
                            ?.associate { it.key to it.value.asString }
                            .orEmpty()
                    }.getOrDefault(emptyMap())
                    Log.i("GlassJS", "play($flag,$id) -> $url（parse=${SpiderJson.str(o, "parse")}）")
                    return PlaySource(url, headers)
                }
            }
        }
        val rule = drpy ?: return null
        // 原版 lazy 用 `js:` 前缀表达「播放页需要再取一次」，这里交给 QuickJS + pdfh/request 执行
        if (rule.lazyJs.isNotBlank()) {
            val expression = "var input=${gson(id)};var url=input;var HOST=${gson(rule.host)};" +
                "(${rule.lazyJs});JSON.stringify(input)"
            val raw = runCatching { JsExecutor.call(code(), expression, extJson()) }.getOrNull()
            if (!raw.isNullOrBlank() && raw != "null") {
                val el = runCatching { JsonParser.parseString(raw) }.getOrNull()
                val url = when {
                    el != null && el.isJsonObject -> SpiderJson.str(el.asJsonObject, "url", "playUrl")
                    el != null && el.isJsonPrimitive -> el.asString
                    else -> ""
                }
                if (url.startsWith("http")) return PlaySource(url)
            }
        }
        return rule.playFromPage(id)
    }

    private fun itemsOf(raw: String): List<MediaItem> {
        val root = JsExecutor.unwrap(runCatching { JsonParser.parseString(raw) }.getOrNull())
            ?: return emptyList()
        val list = if (root.isJsonObject) {
            root.asJsonObject.get("list") ?: root.asJsonObject.get("data") ?: root
        } else root
        return SpiderJson.items(list, site)
    }

    private fun gson(value: String): String = Gson().toJson(value)
}

/**
 * drpy 规则式 JS 源（无函数实现时）：把 rule 中的 URL 模板当作 CMS 接口使用。
 * 支持 json: 类型的字段映射与标准 CMS 别名；css://html: 规则不在本版支持范围内。
 */
class RuleJsCms(private val rule: com.google.gson.JsonObject, private val site: SiteDef) {
    private val host = SpiderJson.str(rule, "host")
    private val headers: Map<String, String> = runCatching {
        val h = rule.getAsJsonObject("headers") ?: return@runCatching emptyMap()
        h.entrySet().associate { it.key to it.value.asString }
    }.getOrDefault(emptyMap())

    private fun tpl(template: String, tid: String = "", page: Int = 1, keyword: String = "", id: String = ""): String {
        var t = template
        t = t.replace("fypage", page.toString())
            .replace("{page}", page.toString())
            .replace("fyclass", tid)
            .replace("{tid}", tid)
            .replace("fyid", id)
            .replace("{id}", id)
            .replace("**", java.net.URLEncoder.encode(keyword, "UTF-8"))
        if (!t.startsWith("http")) t = Net.joinUrl(host, t)
        return t
    }

    private suspend fun fetch(template: String, tid: String = "", page: Int = 1, keyword: String = "", id: String = ""): com.google.gson.JsonElement? {
        if (template.isBlank()) return null
        val text = Net.get(tpl(template, tid, page, keyword, id), headers + ("Referer" to host))
        return runCatching { JsonParser.parseString(text) }.getOrNull()
    }

    private fun listOf(root: com.google.gson.JsonElement?): List<MediaItem> {
        if (root == null) return emptyList()
        val obj = if (root.isJsonObject) root.asJsonObject else null
        val arr = when {
            root.isJsonArray -> root.asJsonArray
            obj != null && obj.get("list")?.isJsonArray == true -> obj.getAsJsonArray("list")
            obj != null && obj.get("data")?.isJsonObject == true -> {
                val data = obj.getAsJsonObject("data")
                if (data.get("list")?.isJsonArray == true) data.getAsJsonArray("list") else null
            }
            else -> null
        } ?: return emptyList()
        return SpiderJson.items(arr, site)
    }

    suspend fun home(): List<HomeSection> {
        val homeUrl = SpiderJson.str(rule, "homeUrl")
        if (homeUrl.isNotBlank()) {
            val list = listOf(fetch(homeUrl))
            if (list.isNotEmpty()) return listOf(HomeSection("推荐", "来自 ${site.name}", list, SectionStyle.HERO))
        }
        val classNames = SpiderJson.str(rule, "class_name").split("&").filter { it.isNotBlank() }
        val classUrls = SpiderJson.str(rule, "class_url").split("&").filter { it.isNotBlank() }
        val listTpl = SpiderJson.str(rule, "url")
        if (listTpl.isBlank()) return emptyList()
        val sections = mutableListOf<HomeSection>()
        val pairs = if (classUrls.isNotEmpty()) {
            classUrls.mapIndexed { i, id -> id to classNames.getOrElse(i) { "分类 ${i + 1}" } }
        } else listOf("" to "最新")
        pairs.take(2).forEach { (tid, name) ->
            runCatching {
                val list = listOf(fetch(listTpl, tid = tid, page = 1))
                if (list.isNotEmpty()) sections += HomeSection(name, "来自 ${site.name}", list)
            }
        }
        return sections
    }

    fun catalog(): SiteCatalog {
        val classNames = SpiderJson.str(rule, "class_name").split("&").filter { it.isNotBlank() }
        val classUrls = SpiderJson.str(rule, "class_url").split("&").filter { it.isNotBlank() }
        val categories = when {
            classNames.isNotEmpty() -> classNames.mapIndexed { index, name ->
                SiteCategory(classUrls.getOrElse(index) { index.toString() }, name)
            }
            classUrls.isNotEmpty() -> classUrls.mapIndexed { index, id ->
                SiteCategory(id, "分类 ${index + 1}")
            }
            else -> listOf(SiteCategory("", "最新"))
        }
        return SiteCatalog(categories = categories)
    }

    suspend fun category(tid: String, page: Int): List<MediaItem> {
        val tpl = SpiderJson.str(rule, "url", "categoryUrl")
        return listOf(fetch(tpl, tid = tid, page = page))
    }

    suspend fun search(keyword: String): List<MediaItem> {
        val tpl = SpiderJson.str(rule, "searchUrl")
        if (tpl.isBlank()) return emptyList()
        return listOf(fetch(tpl, keyword = keyword, page = 1))
    }

    suspend fun detail(id: String): MediaDetail? {
        val tpl = SpiderJson.str(rule, "detailUrl")
        val root = fetch(tpl, id = id) ?: return null
        return SpiderJson.detail(root, site, id)
    }
}
