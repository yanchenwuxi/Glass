package com.glassbox.app.engine

import android.util.Log
import android.util.Xml
import com.glassbox.app.core.Net
import com.glassbox.app.data.*
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder

/**
 * CMS 采集接口（02 §2）：JSON（type 1）与 XML（type 0）。
 * 关键修复：
 * - `ac=list` 一次请求同时解析 class 与 list，避免额外往返；
 * - JSON / XML 字段别名按 §2.6；
 * - 详情接口 ac=detail 失败回退 ac=videolist；
 * - `$$$` / `#` / `$` 三级分隔逐字符切分，空集名补默认值；
 * - 分类列表 10 分钟缓存、列表 3 分钟缓存。
 */
class CmsSpider(private val site: SiteDef, private val jsonMode: Boolean) : SiteSpider {

    private fun apiUrl(vararg params: Pair<String, String>): String {
        val base = site.api.trim()
        val basePath = base.substringBefore('?')
        val query = if (base.contains('?')) base.substringAfter('?') else ""
        val merged = LinkedHashMap<String, String>()
        query.split('&').forEach { pair ->
            if (pair.isNotBlank()) {
                val k = pair.substringBefore('=')
                val v = pair.substringAfter('=', "")
                if (k.isNotBlank()) merged[k] = v
            }
        }
        params.forEach { (k, v) -> merged[k] = v }
        val qs = merged.entries.joinToString("&") { (k, v) ->
            k + "=" + URLEncoder.encode(v, "UTF-8")
        }
        return if (qs.isBlank()) basePath else "$basePath?$qs"
    }

    override suspend fun home(): List<HomeSection> {
        val text = Net.getCached(apiUrl("ac" to "list"), headers(), 10 * 60_000L)
        val categories = if (jsonMode) parseCategoriesJson(text) else parseCategoriesXml(text)
        val initial = if (jsonMode) parseListJson(text) else parseListXml(text)
        val sections = mutableListOf<HomeSection>()
        if (initial.isNotEmpty()) {
            sections += HomeSection("最新更新", "来自 ${site.name}", initial, SectionStyle.HERO)
        }
        categories.take(if (initial.isEmpty()) 3 else 2).forEach { (tid, name) ->
            runCatching {
                Net.getCached(apiUrl("ac" to "videolist", "t" to tid, "pg" to "1"), headers(), 3 * 60_000L)
            }.getOrNull()?.let { body ->
                val items = if (jsonMode) parseListJson(body) else parseListXml(body)
                if (items.isNotEmpty()) sections += HomeSection(name, "来自 ${site.name}", items)
            }
        }
        return sections
    }

    override suspend fun catalog(): SiteCatalog {
        val text = Net.getCached(apiUrl("ac" to "list"), headers(), 10 * 60_000L)
        val categories = if (jsonMode) parseCategoriesJson(text) else parseCategoriesXml(text)
        return SiteCatalog(
            categories = categories.map { (id, name) -> SiteCategory(id, name) },
            filters = if (jsonMode) parseFiltersJson(text) else emptyMap()
        )
    }

    override suspend fun category(tid: String, page: Int, extend: Map<String, String>): List<MediaItem> {
        val params = mutableListOf(
            "ac" to "videolist",
            "t" to tid,
            "pg" to page.toString()
        )
        extend.forEach { (key, value) -> if (value.isNotBlank()) params += key to value }
        val text = Net.getCached(apiUrl(*params.toTypedArray()), headers(), 3 * 60_000L)
        return if (jsonMode) parseListJson(text) else parseListXml(text)
    }

    override suspend fun search(keyword: String): List<MediaItem> {
        val text = runCatching {
            Net.get(apiUrl("ac" to "videolist", "wd" to keyword, "pg" to "1"), headers())
        }.getOrElse {
            Net.get(apiUrl("ac" to "detail", "wd" to keyword, "pg" to "1"), headers())
        }
        return if (jsonMode) parseListJson(text) else parseListXml(text)
    }

    override suspend fun detail(id: String): MediaDetail? {
        val detailUrl = apiUrl("ac" to "detail", "ids" to id)
        val text = runCatching {
            Net.getCached(detailUrl, headers(), 10 * 60_000L)
        }.onFailure {
            Log.w("DetailLoad", "site=${site.key} api=${site.api} id=$id detail请求失败: ${it.message}")
        }.getOrElse {
            runCatching {
                Net.getCached(apiUrl("ac" to "videolist", "ids" to id), headers(), 10 * 60_000L)
            }.getOrDefault("")
        }
        Log.w(
            "DetailLoad",
            "site=${site.key} type=${if (jsonMode) "json" else "xml"} id=$id bytes=${text.length} head=${text.take(180).replace("\n", " ")}"
        )
        if (text.isBlank()) return null
        val primary = if (jsonMode) detailJson(text, id) else detailXml(text, id)
        val fallback = primary ?: runCatching {
            if (jsonMode) detailXml(text, id) else detailJson(text, id)
        }.getOrNull()
        val chosen = fallback ?: primary
        Log.w(
            "DetailLoad",
            "site=${site.key} id=$id parsed=${chosen != null} groups=${chosen?.groups?.size ?: 0} episodes=${chosen?.groups?.sumOf { it.episodes.size } ?: 0}"
        )
        return chosen
    }

    private fun headers(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val origin = Net.originOf(site.api)
        if (origin.isNotBlank()) map["Referer"] = "$origin/"
        runCatching {
            if (!site.ext.isNullOrBlank() && site.ext.trim().startsWith("{")) {
                val ext = JsonParser.parseString(site.ext).asJsonObject
                val h = ext.get("headers")
                if (h != null && h.isJsonObject) {
                    h.asJsonObject.entrySet().forEach { (k, v) ->
                        if (v.isJsonPrimitive) map[k] = v.asString
                    }
                }
            }
        }
        return map
    }

    // ---------------- JSON ----------------

    private fun parseCategoriesJson(text: String): List<Pair<String, String>> {
        return runCatching {
            val root = JsonParser.parseString(text).asJsonObject
            val arr = findArray(root, "class", "classes", "types") ?: return emptyList()
            arr.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val id = o.str("type_id", "id", "class_id", "tid")
                val name = o.str("type_name", "name", "class_name")
                if (id.isNotBlank() && name.isNotBlank()) id to name else null
            }
        }.getOrDefault(emptyList())
    }

    /** CMS 的 `filters` 是「分类 id → 筛选组数组」，只保留真正有选项的分类。 */
    private fun parseFiltersJson(text: String): Map<String, List<FilterGroup>> {
        return runCatching {
            val root = toObject(text) ?: return emptyMap()
            val filtersEl = root.get("filters") ?: return emptyMap()
            if (!filtersEl.isJsonObject) return emptyMap()
            val result = mutableMapOf<String, List<FilterGroup>>()
            filtersEl.asJsonObject.entrySet().forEach { (tid, groupsEl) ->
                if (!groupsEl.isJsonArray) return@forEach
                val groups = mutableListOf<FilterGroup>()
                groupsEl.asJsonArray.forEach { groupEl ->
                    if (!groupEl.isJsonObject) return@forEach
                    val groupObj = groupEl.asJsonObject
                    val key = groupObj.str("key")
                    if (key.isBlank()) return@forEach
                    val groupName = groupObj.str("name").ifBlank { key }
                    val optionsEl = groupObj.get("value")
                        ?: groupObj.get("values")
                        ?: groupObj.get("options")
                    if (optionsEl == null || !optionsEl.isJsonArray) return@forEach
                    val options = mutableListOf<FilterOption>()
                    optionsEl.asJsonArray.forEach { optionEl ->
                        if (!optionEl.isJsonObject) return@forEach
                        val optionObj = optionEl.asJsonObject
                        val optionName = optionObj.str("n", "name", "title")
                        if (optionName.isBlank()) return@forEach
                        options += FilterOption(optionName, optionObj.str("v", "value", "id"))
                    }
                    if (options.isNotEmpty()) groups += FilterGroup(key, groupName, options)
                }
                if (groups.isNotEmpty()) result[tid] = groups
            }
            result.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun parseListJson(text: String): List<MediaItem> {
        return runCatching {
            val root = toObject(text) ?: return emptyList()
            val list = findArray(root, "list", "data", "videos", "vod") ?: return emptyList()
            list.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                val id = o.str("vod_id", "id", "video_id", "ids")
                val name = o.str("vod_name", "name", "title", "video_name")
                if (id.isBlank() || name.isBlank()) null else mapItem(id, name, o)
            }
        }.getOrDefault(emptyList())
    }

    private fun toObject(text: String): JsonObject? {
        val el = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return null
        if (el.isJsonObject) return el.asJsonObject
        if (el.isJsonArray) {
            val o = JsonObject()
            o.add("list", el)
            return o
        }
        return null
    }

    private fun mapItem(id: String, name: String, o: JsonObject): MediaItem {
        val pic = o.str("vod_pic", "pic", "cover", "poster", "vod_pic_thumb")
        return MediaItem(
            id = id,
            title = name,
            poster = pic,
            backdrop = o.str("vod_pic_slide", "backdrop", "banner").ifBlank { pic },
            remarks = o.str("vod_remarks", "note", "remarks", "state"),
            year = o.str("vod_year", "year").let { if (it.length >= 4) it.take(4) else it },
            area = o.str("vod_area", "area"),
            typeName = o.str("type_name", "typeName", "class", "type"),
            score = o.str("vod_score", "score").let { if (it == "0.0" || it == "0") "" else it },
            siteKey = site.key,
            siteName = site.name,
            sourceId = site.sourceId
        )
    }

    private fun detailJson(text: String, requestedId: String): MediaDetail? {
        val root = toObject(text) ?: return null
        val arr = findArray(root, "list", "data", "videos")
        val o = arr?.firstOrNull { it.isJsonObject }?.asJsonObject
            ?: findDetailObject(root)
            ?: return null
        val vid = o.str("vod_id", "id").ifBlank { requestedId }
        val name = o.str("vod_name", "name", "title")
        val item = mapItem(vid, name.ifBlank { requestedId }, o)
        val playFrom = o.str("vod_play_from", "play_from")
        val playUrl = o.str("vod_play_url", "play_url", "urls")
        return MediaDetail(
            item = item,
            description = stripHtml(o.str("vod_content", "content", "desc", "description", "blurb", "vod_blurb")),
            actors = o.str("vod_actor", "actor"),
            director = o.str("vod_director", "director"),
            groups = buildGroups(playFrom, playUrl).playableFirst(),
            recommend = parseListJson(text).filter { it.id != vid }.take(6)
        )
    }

    // ---------------- XML --------------

    private fun parseCategoriesXml(text: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        return runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(text))
            var event = parser.eventType
            var tyId: String? = null
            var buf: StringBuilder? = null
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name.lowercase()) {
                            "ty", "class", "type" -> {
                                val id = parser.getAttributeValue(null, "id")
                                    ?: parser.getAttributeValue(null, "type_id")
                                if (!id.isNullOrBlank()) {
                                    tyId = id
                                    buf = StringBuilder()
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (buf != null) buf!!.append(parser.text.orEmpty())
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.lowercase()
                        if ((tag == "ty" || tag == "class" || tag == "type") && tyId != null) {
                            val name = buf?.toString()?.trim().orEmpty()
                            if (name.isNotBlank()) result += tyId!! to name
                            tyId = null
                            buf = null
                        }
                    }
                }
                event = parser.next()
            }
            result
        }.getOrDefault(result)
    }

    private fun parseListXml(text: String): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        return runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(text))
            var event = parser.eventType
            var map: MutableMap<String, String>? = null
            var currentTag = ""
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name.lowercase() == "video") map = mutableMapOf()
                        currentTag = parser.name.lowercase()
                    }
                    XmlPullParser.TEXT -> {
                        val m = map
                        if (m != null && currentTag.isNotBlank()) {
                            val t = parser.text?.trim().orEmpty()
                            if (t.isNotEmpty()) m[currentTag] = t
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.lowercase() == "video" && map != null) {
                            val m = map!!
                            val id = pick(m, "vod_id", "id")
                            val name = pick(m, "vod_name", "name", "title")
                            if (id.isNotBlank() && name.isNotBlank()) {
                                result += xmlItem(id, name, m)
                            }
                            map = null
                        }
                        currentTag = ""
                    }
                }
                event = parser.next()
            }
            result
        }.getOrDefault(result)
    }

    private fun xmlItem(id: String, name: String, m: Map<String, String>): MediaItem {
        val pic = pick(m, "vod_pic", "pic", "cover", "poster")
        return MediaItem(
            id, name, pic, pic,
            pick(m, "vod_remarks", "note", "remarks", "state"),
            pick(m, "vod_year", "year"),
            pick(m, "vod_area", "area"),
            pick(m, "type_name", "type", "class"),
            pick(m, "vod_score", "score"),
            site.key, site.name, site.sourceId
        )
    }

    private fun detailXml(text: String, requestedId: String): MediaDetail? {
        return runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(text))
            var event = parser.eventType
            var map: MutableMap<String, String>? = null
            val tagStack = mutableListOf<String>()
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase()
                        if (tag == "video") {
                            map = mutableMapOf()
                            tagStack.clear()
                            tagStack.add(tag)
                        } else if (map != null) {
                            tagStack.add(tag)
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val m = map
                        val tag = tagStack.lastOrNull()
                        if (m != null && tag != null && tag != "video") {
                            val t = parser.text.orEmpty()
                            if (t.isNotBlank()) m[tag] = (m[tag] ?: "") + t
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.lowercase()
                        if (tag == "video" && map != null) {
                            val m = map!!.mapValues { it.value.trim() }
                            val vid = pick(m, "vod_id", "id").ifBlank { requestedId }
                            val name = pick(m, "vod_name", "name").ifBlank { requestedId }
                            return@runCatching MediaDetail(
                                item = xmlItem(vid, name, m),
                                description = stripHtml(pick(m, "vod_content", "des", "content", "desc")),
                                actors = pick(m, "vod_actor", "actor"),
                                director = pick(m, "vod_director", "director"),
                                groups = buildGroups(
                                    pick(m, "vod_play_from", "play_from", "from"),
                                    pick(m, "vod_play_url", "play_url", "url")
                                ).playableFirst()
                            )
                        }
                        if (map != null && tagStack.isNotEmpty()) {
                            tagStack.removeAt(tagStack.lastIndex)
                        }
                    }
                }
                event = parser.next()
            }
            null
        }.getOrNull()
    }

    private fun pick(m: Map<String, String>, vararg keys: String): String {
        keys.forEach { k -> m[k]?.takeIf { it.isNotBlank() }?.let { return it } }
        return ""
    }

    // ---------------- 播放地址 ----------------

    private fun buildGroups(from: String, url: String): List<EpisodeGroup> {
        if (url.isBlank()) return emptyList()
        val froms = from.split("$$$").map { it.trim() }
        val groups = mutableListOf<EpisodeGroup>()
        url.split("$$$").forEachIndexed { groupIndex, groupText ->
            if (groupText.isBlank()) return@forEachIndexed
            val episodes = mutableListOf<Episode>()
            val chunks = groupText.split("#")
            val named = chunks.count { it.contains('$') }
            // HLS 地址自身可能带 # 片段：当多数分片不是「名称$地址」时，整条按一集处理。
            val pieces = if (chunks.size > 1 && named < chunks.size - 1) listOf(groupText) else chunks
            pieces.forEach { part ->
                if (part.isBlank()) return@forEach
                val idx = part.indexOf('$')
                if (idx < 0) {
                    val only = part.trim()
                    if (only.startsWith("http")) {
                        episodes += Episode("第 ${episodes.size + 1} 集", only)
                    }
                    return@forEach
                }
                val title = part.substring(0, idx).trim()
                    .ifBlank { "第 ${episodes.size + 1} 集" }
                val value = part.substring(idx + 1).trim()
                if (value.isNotBlank()) episodes += Episode(title, value)
            }
            if (episodes.isNotEmpty()) {
                val title = froms.getOrNull(groupIndex)?.ifBlank { null } ?: "线路 ${groupIndex + 1}"
                groups += EpisodeGroup(title, episodes)
            }
        }
        return groups
    }

    /**
     * 兼容详情返回不是数组的 CMS：`{data:{...}}`、`{info:{...}}`、`{vod:{...}}`、
     * 或根对象本身就是一条详情。
     */
    private fun findDetailObject(root: JsonObject): JsonObject? {
        if (looksLikeDetail(root)) return root
        for (key in listOf("data", "info", "vod", "detail", "video", "list")) {
            val el = root.get(key) ?: continue
            if (el.isJsonObject) {
                val o = el.asJsonObject
                if (looksLikeDetail(o)) return o
            }
        }
        return null
    }

    private fun looksLikeDetail(o: JsonObject): Boolean {
        val markers = listOf(
            "vod_id", "id", "vod_name", "name", "vod_play_url", "play_url", "vod_pic", "pic"
        )
        var hits = 0
        for (key in markers) if (o.has(key) && o.get(key).isJsonPrimitive) hits++
        return hits >= 2 || o.has("vod_play_url") || o.has("play_url")
    }

    private fun findArray(root: JsonObject, vararg keys: String): JsonArray? {
        for (key in keys) {
            val el: JsonElement = root.get(key) ?: continue
            if (el.isJsonArray) return el.asJsonArray
            if (el.isJsonObject) {
                val nested = el.asJsonObject
                for (inner in keys) {
                    val innerEl: JsonElement = nested.get(inner) ?: continue
                    if (innerEl.isJsonArray) return innerEl.asJsonArray
                }
            }
        }
        return null
    }

    private fun JsonObject.str(vararg keys: String): String {
        keys.forEach { key ->
            val el = get(key) ?: return@forEach
            if (el.isJsonPrimitive) {
                val value = el.asString
                if (value.isNotBlank() && value != "null") return value
            }
        }
        return ""
    }

    private fun stripHtml(input: String): String = input
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()
}
