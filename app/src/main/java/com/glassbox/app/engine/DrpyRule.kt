package com.glassbox.app.engine

import android.util.Log
import com.glassbox.app.GlassApp
import com.glassbox.app.core.Net
import com.glassbox.app.data.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder

/**
 * drpy2 规则源（`var rule = {…}` + muban 模板）的 Kotlin 侧执行器。
 *
 * 原版由系统 WebView 加载 drpy 引擎（`模板.js` + `drpy.js` + cheerio）执行；
 * 我们保留 QuickJS 跑脚本加载/纯函数脚本，规则解析改由 jsoup 完成（见 HtmlDom），
 * 避免依赖原宿主 7 份私有 host 脚本。
 */
class DrpyRule(
    private val rule: JsonObject,
    private val overrides: JsonObject,
    private val site: SiteDef
) {
    private val templates: JsonObject by lazy { loadTemplates() }

    private val template: JsonObject by lazy {
        val name = txt("模板")
        templates.getAsJsonObject(name) ?: JsonObject()
    }

    val host: String = txt("host").trimEnd('/')
    private val headers: Map<String, String> by lazy { buildHeaders() }

    private fun txt(key: String): String {
        val own = SpiderJson.str(rule, key)
        return own.ifBlank { SpiderJson.str(template, key) }
    }

    private fun section(key: String): JsonObject {
        val out = JsonObject()
        template.getAsJsonObject(key)?.entrySet()?.forEach { (k, v) -> out.add(k, v) }
        rule.getAsJsonObject(key)?.entrySet()?.forEach { (k, v) -> out.add(k, v) }
        overrides.getAsJsonObject(key)?.entrySet()?.forEach { (k, v) -> out.add(k, v) }
        return out
    }

    private fun loadTemplates(): JsonObject = runCatching {
        val text = GlassApp.instance.assets.open("muban.json").use { it.readBytes().toString(Charsets.UTF_8) }
        JsonParser.parseString(text).asJsonObject
    }.getOrDefault(JsonObject())

    private fun buildHeaders(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val h = rule.getAsJsonObject("headers") ?: template.getAsJsonObject("headers")
        h?.entrySet()?.forEach { (k, v) -> if (v.isJsonPrimitive) map[k] = ua(v.asString) }
        if (map.keys.none { it.equals("User-Agent", true) }) map["User-Agent"] = UA_MOBILE
        if (host.isNotBlank()) map["Referer"] = "$host/"
        return map
    }

    private fun ua(value: String): String = when (value) {
        "MOBILE_UA" -> UA_MOBILE
        "UC_UA" -> UA_UC
        else -> value
    }

    // ---------------- 分类 ----------------

    /** 分类来源优先级：class_parse → class_name/class_url → filter 顶层键。 */
    fun categories(html: String? = null): List<Pair<String, String>> {
        val parse = txt("class_parse")
        if (parse.isNotBlank() && html != null) {
            val parts = parse.split(';')
            val nodeSel = parts.getOrNull(0).orEmpty()
            val nameSel = parts.getOrNull(1).orEmpty()
            val linkSel = parts.getOrNull(2).orEmpty()
            val reg = parts.getOrNull(3).orEmpty()
            val result = HtmlDom.select(HtmlDom.document(html, host), nodeSel).mapNotNull { el ->
                val name = HtmlDom.value(el, nameSel, host).trim()
                val link = HtmlDom.value(el, linkSel, host).trim()
                if (name.isBlank() || link.isBlank()) return@mapNotNull null
                val id = matchId(link, reg).ifBlank { link }
                id to name
            }
            if (result.isNotEmpty()) return result
        }
        val names = txt("class_name").split('&').filter { it.isNotBlank() }
        val urls = txt("class_url").split('&').filter { it.isNotBlank() }
        if (names.isNotEmpty()) {
            return names.mapIndexed { i, name -> urls.getOrElse(i) { name } to name }
        }
        val filter = rule.getAsJsonObject("filter") ?: template.getAsJsonObject("filter")
        if (filter != null && filter.entrySet().isNotEmpty()) {
            return filter.entrySet().map { (key, _) -> key to key }
        }
        return emptyList()
    }

    private fun matchId(link: String, regex: String): String {
        if (regex.isBlank()) return ""
        val pattern = regex.trim().removeSurrounding("/").ifBlank { return "" }
        return runCatching {
            val m = Regex(pattern).find(link) ?: return ""
            (1..m.groupValues.size - 1).map { m.groupValues[it] }.firstOrNull { it.isNotBlank() } ?: ""
        }.getOrDefault("")
    }

    suspend fun catalog(): SiteCatalog {
        val html = runCatching { fetch(expand(SpiderJson.str(rule, "homeUrl").ifBlank { "/" })) }.getOrNull()
        val f = rule.getAsJsonObject("filter") ?: template.getAsJsonObject("filter")
        val filters = mutableMapOf<String, List<FilterGroup>>()
        f?.entrySet()?.forEach { (tid, groupsEl) ->
            if (!groupsEl.isJsonArray) return@forEach
            val groups = groupsEl.asJsonArray.mapNotNull { g ->
                if (!g.isJsonObject) return@mapNotNull null
                val go = g.asJsonObject
                val key = SpiderJson.str(go, "key")
                if (key.isBlank()) return@mapNotNull null
                val options = go.getAsJsonArray("value")?.mapNotNull { o ->
                    if (!o.isJsonObject) return@mapNotNull null
                    val oo = o.asJsonObject
                    val n = SpiderJson.str(oo, "n", "name")
                    if (n.isBlank()) null else FilterOption(n, SpiderJson.str(oo, "v", "value"))
                }.orEmpty()
                if (options.isEmpty()) null
                else FilterGroup(key, SpiderJson.str(go, "name").ifBlank { key }, options)
            }
            if (groups.isNotEmpty()) filters[tid] = groups
        }
        return SiteCatalog(
            categories = categories(html).map { (id, name) -> SiteCategory(id, name) },
            filters = filters.toMap()
        )
    }

    // ---------------- 首页 / 列表 ----------------

    suspend fun home(): List<HomeSection> {
        val homeUrl = expand(SpiderJson.str(rule, "homeUrl").ifBlank { "/" })
        val html = fetch(homeUrl)
        val sections = mutableListOf<HomeSection>()
        val recommend = txt("推荐")
        val first = txt("一级")
        val double = rule.get("double")?.let { runCatching { it.asBoolean }.getOrDefault(false) }
            ?: template.get("double")?.let { runCatching { it.asBoolean }.getOrDefault(false) } ?: false
        val items = parseList(html, recommend.ifBlank { first }, double)
        if (items.isNotEmpty()) {
            sections += HomeSection("推荐", "来自 ${site.name}", items, SectionStyle.HERO)
        }
        val cats = categories(html)
        cats.take(if (items.isEmpty()) 3 else 2).forEach { (tid, name) ->
            val list = runCatching { category(tid, 1) }.getOrElse { emptyList() }
            if (list.isNotEmpty()) sections += HomeSection(name, "来自 ${site.name}", list)
        }
        if (sections.isEmpty()) {
            throw IllegalStateException("首页未解析到内容（host=$host，模板=${txt("模板")}，请检查站点是否可达或规则是否失效）")
        }
        return sections
    }

    suspend fun category(tid: String, page: Int, extend: Map<String, String> = emptyMap()): List<MediaItem> {
        val tpl = txt("url").ifBlank { return emptyList() }
        val url = buildUrl(tpl, tid, page, extend)
        return parseList(fetch(url), txt("一级"), double = false)
    }

    suspend fun search(keyword: String): List<MediaItem> {
        val tpl = txt("searchUrl").ifBlank { return emptyList() }
        val url = buildUrl(tpl, "", 1, emptyMap(), keyword)
        return parseList(fetch(url), txt("搜索").ifBlank { txt("一级") }, double = false)
    }

    private fun buildUrl(
        tpl: String,
        tid: String,
        page: Int,
        extend: Map<String, String>,
        keyword: String = "",
        id: String = ""
    ): String {
        var out = tpl
        if ("fyfilter" in out) {
            val filterUrl = txt("filter_url")
            val built = if (filterUrl.isNotBlank()) {
                var f = filterUrl
                    .replace("{{fl.by or \"time\"}}", extend["by"] ?: "time")
                extend.forEach { (k, v) -> f = f.replace("{{fl.$k}}", v) }
                f = Regex("\\{\\{fl\\.[^}]+\\}\\}").replace(f, "")
                f
            } else ""
            out = out.replace("fyfilter", built)
        }
        out = out.replace("fyclass", tid)
            .replace("fypage", page.toString())
            .replace("{page}", page.toString())
            .replace("fyid", id)
            .replace("{id}", id)
            .replace("**", URLEncoder.encode(keyword, "UTF-8"))
        return if (out.startsWith("http")) out else Net.joinUrl(host, out)
    }

    private fun expand(path: String, id: String = ""): String {
        val p = path.replace("fyid", id).replace("{id}", id)
        if (p.startsWith("http")) return p
        return Net.joinUrl(host, p)
    }

    private suspend fun fetch(url: String): String {
        Log.i("GlassBoxPlayer", "drpy 请求：$url")
        return Net.getCached(url, headers, 3 * 60_000L)
    }

    /** 解析 `一级` / `推荐` 规则；`double=true` 时前两段是「容器;条目」。 */
    fun parseList(html: String, spec: String, double: Boolean): List<MediaItem> {
        if (spec.isBlank()) return emptyList()
        val parts = spec.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return emptyList()
        val doc = HtmlDom.document(html, host)
        val itemSel: String
        val fields: List<String>
        if (double && parts.size >= 3) {
            val containers = HtmlDom.select(doc, parts[0])
            val items = containers.flatMap { c -> HtmlDom.select(c, parts[1]) }
            return buildItems(items, parts.drop(2), HTML_ITEM_LIMIT)
        } else {
            itemSel = parts[0]
            fields = parts.drop(1)
        }
        val items = HtmlDom.select(doc, itemSel)
        return buildItems(items, fields, HTML_ITEM_LIMIT)
    }

    private fun buildItems(elements: List<org.jsoup.nodes.Element>, fields: List<String>, limit: Int): List<MediaItem> {
        return elements.take(limit).mapNotNull { el ->
            val title = HtmlDom.value(el, fields.getOrElse(0) { "a&&title" }, host).trim()
            if (title.isBlank()) return@mapNotNull null
            val pic = HtmlDom.value(el, fields.getOrElse(1) { "" }, host).trim()
            val remarks = HtmlDom.value(el, fields.getOrElse(2) { "" }, host).trim()
            val link = HtmlDom.value(el, fields.getOrElse(3) { "a&&href" }, host).trim()
            val id = link.ifBlank { title }
            MediaItem(
                id = id,
                title = title,
                poster = pic,
                backdrop = pic,
                remarks = remarks,
                siteKey = site.key,
                siteName = site.name,
                sourceId = site.sourceId
            )
        }
    }

    // ---------------- 详情 ----------------

    suspend fun detail(id: String): MediaDetail? {
        val url = if (id.startsWith("http")) id else expand(id)
        val html = fetch(url)
        val second = section("二级")
        val title = firstValue(html, SpiderJson.str(second, "title"))
        if (title.isBlank()) return null
        val pic = firstValue(html, SpiderJson.str(second, "img"))
        val content = firstValue(html, SpiderJson.str(second, "content"))
        val groups = playGroups(html, second)
        Log.i("GlassBoxPlayer", "drpy 详情：$url title=$title groups=${groups.size} eps=${groups.sumOf { it.episodes.size }}")
        return MediaDetail(
            item = MediaItem(
                id = id,
                title = title,
                poster = pic,
                backdrop = pic,
                typeName = site.name,
                siteKey = site.key,
                siteName = site.name,
                sourceId = site.sourceId
            ),
            description = content,
            groups = groups
        )
    }

    private fun firstValue(html: String, spec: String): String {
        if (spec.isBlank()) return ""
        for (one in spec.split(';')) {
            if (one.isBlank()) continue
            val value = HtmlDom.pdfh(html, one, host)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun playGroups(html: String, second: JsonObject): List<EpisodeGroup> {
        val tabsSel = SpiderJson.str(second, "tabs")
        val listsSel = SpiderJson.str(second, "lists")
        if (listsSel.isBlank()) return emptyList()
        val doc = HtmlDom.document(html, host)
        val tabs = if (tabsSel.isBlank()) emptyList() else HtmlDom.select(doc, tabsSel)
        val groups = mutableListOf<EpisodeGroup>()
        val count = maxOf(1, tabs.size)
        for (i in 0 until count) {
            val sel = listsSel.replace("#id", i.toString())
            val nodes = HtmlDom.select(doc, sel)
            val eps = nodes.mapNotNull { el ->
                val href = HtmlDom.value(el, "a&&href", host).ifBlank {
                    if (el.tagName() == "a") el.absUrl("href").ifBlank { el.attr("href") } else ""
                }
                val name = HtmlDom.value(el, "a&&Text", host).ifBlank { el.text().trim() }
                if (href.isBlank()) return@mapNotNull null
                Episode(name.ifBlank { "正片" }, href)
            }
            if (eps.isNotEmpty()) {
                val title = tabs.getOrNull(i)?.text()?.trim().orEmpty().ifBlank { "线路 ${i + 1}" }
                groups += EpisodeGroup(title, eps)
            }
        }
        return groups
    }

    // ---------------- 播放 ----------------

    /** `lazy` 为 `js:` 开头时由 QuickJS 执行；其余按 MacCMS 播放页通用结构兜底。 */
    val lazyJs: String
        get() {
            val lazy = txt("lazy")
            return if (lazy.startsWith("`js:")) lazy.removePrefix("`js:").substringBefore("`").trim()
            else if (lazy.startsWith("js:")) lazy.removePrefix("js:").trim()
            else ""
        }

    suspend fun playFromPage(id: String): PlaySource? {
        val url = if (id.startsWith("http")) id else expand(id)
        val html = Net.getCached(url, headers, 60_000L)
        Log.i("GlassBoxPlayer", "播放页解析：$url bytes=${html.length}")
        val found = parsePlayHtml(html) ?: return null
        return PlaySource(found)
    }

    companion object {
        /** MacCMS 播放页通用结构：`player_aaaa`（encrypt 1=unescape / 2=base64+unescape），也有站点直接 `var now`。 */
        fun parsePlayHtml(html: String): String? {
            Regex("player_aaaa\\s*=\\s*(\\{.*?})\\s*</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.let { m ->
                runCatching {
                    val obj = JsonParser.parseString(m.groupValues[1]).asJsonObject
                    val raw = SpiderJson.str(obj, "url")
                    val encrypt = SpiderJson.str(obj, "encrypt")
                    val decoded = when (encrypt) {
                        "1" -> unquote(raw)
                        "2" -> unquote(base64Decode(raw))
                        else -> raw
                    }
                    if (decoded.isNotBlank()) return decoded
                }
            }
            Regex("var\\s+now\\s*=\\s*\"(.*?)\"").find(html)?.let { m ->
                val raw = m.groupValues[1]
                if (raw.startsWith("http")) return raw
            }
            Regex("\"url\"\\s*:\\s*\"(http[^\"]+)\"").find(html)?.let { m ->
                val raw = m.groupValues[1].replace("\\/", "/")
                if (".m3u8" in raw || ".mp4" in raw) return raw
            }
            return null
        }

        private fun unquote(value: String): String {
            var out = value
            out = Regex("%u([0-9a-fA-F]{4})").replace(out) { m ->
                String(Character.toChars(m.groupValues[1].toInt(16)))
            }
            out = runCatching { java.net.URLDecoder.decode(out, "UTF-8") }.getOrDefault(out)
            return out
        }

        private fun base64Decode(value: String): String = runCatching {
            android.util.Base64.decode(value, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
        }.getOrDefault("")

        const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"
        const val UA_UC = "Mozilla/5.0 (Linux; U; Android 13; zh-CN; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.58 UCBrowser/15.0.0.1234 Mobile Safari/537.36"
        private const val HTML_ITEM_LIMIT = 40
    }
}
