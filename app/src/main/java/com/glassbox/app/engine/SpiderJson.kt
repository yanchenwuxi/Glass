package com.glassbox.app.engine

import com.glassbox.app.data.*
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

object SpiderJson {

    fun items(element: JsonElement?, site: SiteDef): List<MediaItem> {
        if (element == null || !element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull { item(it, site) }
    }

    fun item(element: JsonElement?, site: SiteDef): MediaItem? {
        if (element == null || !element.isJsonObject) return null
        val o = element.asJsonObject
        val id = str(o, "id", "vod_id", "key")
        val title = str(o, "title", "name", "vod_name")
        if (title.isBlank()) return null
        val pic = str(o, "poster", "pic", "vod_pic", "cover")
        return MediaItem(
            id = id.ifBlank { title },
            title = title,
            poster = pic,
            backdrop = str(o, "backdrop", "banner").ifBlank { pic },
            remarks = str(o, "remarks", "note", "vod_remarks"),
            year = str(o, "year", "vod_year"),
            area = str(o, "area", "vod_area"),
            typeName = str(o, "typeName", "type_name", "type", "class"),
            score = str(o, "score", "vod_score").let { if (it == "0.0" || it == "0") "" else it },
            siteKey = str(o, "siteKey").ifBlank { site.key },
            siteName = str(o, "siteName").ifBlank { site.name },
            sourceId = str(o, "sourceId").ifBlank { site.sourceId }
        )
    }

    /**
     * 兼容三种 home 返回：
     * 1) 标准 CMS 风格 { class:[...], list:[...] }（TVBox JS/PY 最常见）；
     * 2) 分区数组 [{title,list}, ...]；
     * 3) 单分区对象 {title,items}。
     */
    fun sections(element: JsonElement?, site: SiteDef): List<HomeSection> {
        if (element == null || element.isJsonNull) return emptyList()
        if (element.isJsonArray) {
            return element.asJsonArray.mapNotNull { el ->
                if (el.isJsonObject) section(el.asJsonObject, site) else null
            }
        }
        if (!element.isJsonObject) return emptyList()
        val o = element.asJsonObject
        if (o.get("sections")?.isJsonArray == true) {
            return sections(o.get("sections"), site)
        }
        if (o.has("title") && (o.get("items") != null || o.get("list") != null)) {
            return listOfNotNull(section(o, site))
        }
        val items = items(
            o.get("list") ?: o.get("items") ?: o.get("videos") ?: o.get("vod") ?: o.get("data"),
            site
        )
        if (items.isEmpty()) return emptyList()
        return listOf(
            HomeSection(
                title = str(o, "title").ifBlank { "最新收录" },
                subtitle = classNames(o.get("class")),
                items = items,
                style = SectionStyle.HERO
            )
        )
    }

    /** class 数组里的分类名，用于分区副标题 */
    fun classNames(element: JsonElement?): String {
        if (element == null || !element.isJsonArray) return ""
        return element.asJsonArray.mapNotNull { el ->
            if (el.isJsonObject) str(el.asJsonObject, "type_name", "name", "title") else null
        }.filter { it.isNotBlank() }.take(5).joinToString(" · ")
    }

    fun categories(element: JsonElement?): List<Pair<String, String>> {
        if (element == null || !element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val id = str(o, "type_id", "id", "tid")
            val name = str(o, "type_name", "name", "title")
            if (id.isNotBlank() && name.isNotBlank()) id to name else null
        }
    }

    /** 脚本 home 返回里的 `filters`：分类 id → 筛选组数组。 */
    fun filters(element: JsonElement?): Map<String, List<FilterGroup>> {
        if (element == null || !element.isJsonObject) return emptyMap()
        val result = mutableMapOf<String, List<FilterGroup>>()
        element.asJsonObject.entrySet().forEach { (tid, groupsEl) ->
            if (!groupsEl.isJsonArray) return@forEach
            val groups = groupsEl.asJsonArray.mapNotNull { groupEl ->
                if (!groupEl.isJsonObject) return@mapNotNull null
                val groupObj = groupEl.asJsonObject
                val key = str(groupObj, "key")
                if (key.isBlank()) return@mapNotNull null
                val name = str(groupObj, "name").ifBlank { key }
                val optionsEl = groupObj.get("value")
                    ?: groupObj.get("values")
                    ?: groupObj.get("options")
                val options = if (optionsEl != null && optionsEl.isJsonArray) {
                    optionsEl.asJsonArray.mapNotNull { optionEl ->
                        if (!optionEl.isJsonObject) return@mapNotNull null
                        val optionObj = optionEl.asJsonObject
                        val optionName = str(optionObj, "n", "name", "title")
                        if (optionName.isBlank()) null
                        else FilterOption(optionName, str(optionObj, "v", "value", "id"))
                    }
                } else emptyList()
                if (options.isEmpty()) null else FilterGroup(key, name, options)
            }
            if (groups.isNotEmpty()) result[tid] = groups
        }
        return result.toMap()
    }

    /** 把 home 返回的根对象直接转成站点目录。 */
    fun catalog(element: JsonElement?, site: SiteDef): SiteCatalog {
        if (element == null || !element.isJsonObject) return SiteCatalog()
        val root = element.asJsonObject
        return SiteCatalog(
            categories = categories(root.get("class")).map { (id, name) -> SiteCategory(id, name) },
            filters = filters(root.get("filters")),
            fallbackItems = items(
                root.get("list") ?: root.get("items") ?: root.get("videos") ?: root.get("vod"),
                site
            )
        )
    }

    private fun section(o: JsonObject, site: SiteDef): HomeSection? {
        val items = items(o.get("items") ?: o.get("list") ?: o.get("videos"), site)
        if (items.isEmpty()) return null
        val style = when (str(o, "style").uppercase()) {
            "HERO" -> SectionStyle.HERO
            "GRID" -> SectionStyle.GRID
            else -> SectionStyle.ROW
        }
        return HomeSection(
            title = str(o, "title").ifBlank { site.name },
            subtitle = str(o, "subtitle", "desc"),
            items = items,
            style = style
        )
    }

    fun detail(element: JsonElement?, site: SiteDef, fallbackId: String): MediaDetail? {
        if (element == null || element.isJsonNull) return null
        val wrapper = if (element.isJsonObject) element.asJsonObject else null
        // 标准 CMS 风格：{ list:[{ vod_play_from:"...", vod_play_url:"..." }] }
        val entryEl: JsonElement? = when {
            wrapper?.get("list")?.isJsonArray == true -> wrapper.getAsJsonArray("list").firstOrNull()
            element.isJsonArray -> element.asJsonArray.firstOrNull()
            else -> null
        }
        val o = (entryEl?.takeIf { it.isJsonObject }?.asJsonObject)
            ?: wrapper
            ?: return null
        val itemEl = if (o.has("item") && o.get("item").isJsonObject) o.get("item") else o
        val item = item(itemEl, site) ?: MediaItem(
            id = fallbackId,
            title = fallbackId,
            siteKey = site.key,
            siteName = site.name,
            sourceId = site.sourceId
        )
        val groups = mutableListOf<EpisodeGroup>()
        val groupsEl = o.get("groups") ?: o.get("playGroups")
        if (groupsEl != null && groupsEl.isJsonArray) {
            groupsEl.asJsonArray.forEach { g ->
                if (!g.isJsonObject) return@forEach
                val go = g.asJsonObject
                val eps = episodes(go.get("episodes") ?: go.get("list"))
                if (eps.isNotEmpty()) groups += EpisodeGroup(str(go, "title", "name").ifBlank { "播放线路" }, eps)
            }
        }
        if (groups.isEmpty()) {
            val eps = episodes(o.get("episodes") ?: o.get("playUrl"))
            if (eps.isNotEmpty()) groups += EpisodeGroup("播放线路", eps)
        }
        if (groups.isEmpty()) {
            val playFrom = str(o, "vod_play_from", "play_from")
            val playUrl = str(o, "vod_play_url", "play_url", "urls")
            if (playUrl.isNotBlank()) {
                val froms = playFrom.split("$$$").map { it.trim() }
                playUrl.split("$$$").forEachIndexed { index, part ->
                    if (part.isBlank()) return@forEachIndexed
                    val eps = parsePlayUrl(part)
                    if (eps.isNotEmpty()) {
                        groups += EpisodeGroup(
                            froms.getOrNull(index)?.ifBlank { null } ?: "线路 ${index + 1}",
                            eps
                        )
                    }
                }
            }
        }
        return MediaDetail(
            item = item,
            description = str(o, "description", "desc", "content", "vod_content").replace(Regex("<[^>]+>"), " ").trim(),
            actors = str(o, "actors", "actor", "vod_actor"),
            director = str(o, "director", "vod_director"),
            groups = groups.playableFirst(),
            recommend = items(o.get("recommend") ?: wrapper?.get("recommend"), site)
        )
    }

    private fun episodes(element: JsonElement?): List<Episode> {
        if (element == null) return emptyList()
        if (element.isJsonArray) {
            return element.asJsonArray.mapNotNull { e ->
                if (e.isJsonObject) {
                    val o = e.asJsonObject
                    val url = str(o, "url", "playUrl")
                    if (url.isBlank()) null else Episode(str(o, "name", "title").ifBlank { "正片" }, url)
                } else null
            }
        }
        if (element.isJsonPrimitive && element.asString.isNotBlank()) {
            return parsePlayUrl(element.asString)
        }
        return emptyList()
    }

    fun parsePlayUrl(raw: String): List<Episode> {
        val chunks = raw.split("#")
        val named = chunks.count { it.contains('$') }
        // HLS 地址自身可能带 # 片段：当多数分片不是「名称$地址」时，整条按一集处理。
        val pieces = if (chunks.size > 1 && named < chunks.size - 1) listOf(raw) else chunks
        return pieces.mapNotNull { part ->
            val idx = part.indexOf('$')
            if (idx < 0) {
                val only = part.trim()
                return@mapNotNull if (only.startsWith("http")) Episode("正片", only) else null
            }
            val name = part.substring(0, idx).trim().ifBlank { "正片" }
            val url = part.substring(idx + 1).trim()
            if (url.isNotBlank()) Episode(name, url) else null
        }
    }

    fun str(o: JsonObject, vararg keys: String): String {
        keys.forEach { key ->
            val el = o.get(key) ?: return@forEach
            if (el.isJsonPrimitive) {
                val value = el.asString
                if (value.isNotBlank() && value != "null") return value
            }
        }
        return ""
    }
}
