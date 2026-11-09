/**
 * 源格式解析与类型嗅探。
 * 兼容规范 02：JSON 配置（sites 容器）、多仓索引、drpy JS（var rule / export default）、PY 脚本。
 * type 判定顺序（02 §1.3）：
 *   1) api 以 csp_ 开头 或 jar 存在 → SPIDER（本版不支持，标记不可用）
 *   2) api 以 .js / .js? / jsdelivr → JS
 *   3) api 以 .py 结尾 → PY
 *   4) ext 含 "type":"py" 显式标记 → PY
 *   5) 其余按 type 字段（0 XML / 1 JSON）
 *   6) type=2（分支里表示脚本站点）→ 按 api 后缀与内容嗅探
 */
package com.glassbox.app.engine

import com.glassbox.app.data.*
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class RepoEntry(val name: String, val url: String, val note: String = "")

sealed class DetectResult {
    data class Config(val sites: List<SiteDef>, val name: String, val repo: Boolean = false) : DetectResult()
    data class RepoIndex(val entries: List<RepoEntry>) : DetectResult()
    data class Script(val type: SourceType) : DetectResult()
    data class Unsupported(val reason: String) : DetectResult()
}

private data class TypeResolution(val type: Int, val unsupported: Boolean = false, val reason: String = "")

object SourceParser {

    fun detect(raw: String, hintName: String? = null, baseUrl: String = ""): DetectResult {
        val text = raw.trim().removePrefix("\uFEFF").trimStart('\uFEFF')
        if (text.isBlank()) return DetectResult.Unsupported("内容为空")
        if (text.length > 2 * 1024 * 1024) return DetectResult.Unsupported("源内容超过 2MB 限制")

        val head = text.take(8192)
        val looksJson = head.startsWith("{") || head.startsWith("[") ||
            hintName?.lowercase()?.let { it.endsWith(".json") || it.endsWith(".json?") } == true
        if (looksJson) {
            val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
                ?: return DetectResult.Unsupported("JSON 语法错误（请检查引号、逗号与 BOM）")
            if (parsed.isJsonArray) {
                val repos = parseRepoArray(parsed.asJsonArray)
                if (repos.isNotEmpty()) return DetectResult.RepoIndex(repos)
                return DetectResult.Unsupported("顶层数组不是受支持的配置")
            }
            val obj = parsed.asJsonObject
            if (obj.has("sites")) {
                val sites = runCatching { parseSites(obj, "", "", obj.str("spider"), baseUrl) }
                    .getOrElse { return DetectResult.Unsupported(it.message ?: "站点解析失败") }
                return DetectResult.Config(sites, obj.str("name").ifBlank { hintName ?: "JSON 源" })
            }
            if (obj.has("urls") || obj.has("repos")) {
                val arr = (obj.get("urls") ?: obj.get("repos")) as? JsonArray ?: JsonArray()
                val repos = parseRepoArray(arr)
                if (repos.isNotEmpty()) return DetectResult.RepoIndex(repos)
            }
            return DetectResult.Unsupported("JSON 中未找到 sites 字段")
        }

        val jsSignals = listOf(
            Regex("\\bvar\\s+rule\\b"),
            Regex("\\brule\\s*=\\s*\\{"),
            Regex("\\bexport\\s+default\\b"),
            Regex("\\bmodule\\.exports\\b"),
            Regex("\\bfunction\\s+(home|homeVod|category|detail|search)\\s*\\(")
        )
        if (jsSignals.any { it.containsMatchIn(head) }) return DetectResult.Script(SourceType.JS)

        val pySignals = listOf(
            Regex("(?m)^\\s*def\\s+(home|homeContent|categoryContent|detailContent|searchContent)\\s*\\("),
            Regex("(?m)^\\s*class\\s+Spider\\s*[:(]"),
            Regex("(?m)^\\s*import\\s+requests\\b")
        )
        if (pySignals.any { it.containsMatchIn(head) } || hintName?.endsWith(".py", true) == true) {
            return DetectResult.Script(SourceType.PY)
        }
        return DetectResult.Unsupported("无法识别的源格式（支持 TVBox JSON、JS、PY）")
    }

    fun parseSites(
        obj: JsonObject,
        sourceId: String,
        sourceName: String,
        spiderUrl: String = "",
        baseUrl: String = ""
    ): List<SiteDef> {
        val sitesEl = obj.get("sites")
        if (sitesEl == null || !sitesEl.isJsonArray) return emptyList()
        val list = sitesEl.asJsonArray
        if (list.size() > 500) throw IllegalStateException("站点数量超过 500 限制")
        val seen = mutableSetOf<String>()
        return list.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val api = o.str("api")
            val name = o.str("name").ifBlank { o.str("key") }
            if (name.isBlank() && api.isBlank()) return@mapNotNull null
            var key = o.str("key").ifBlank { "site_" + Integer.toHexString("$name|$api".hashCode()) }
            if (!seen.add(key)) key = key + "_" + seen.size
            val resolution = resolveType(o, api)
            val ext = o.get("ext")?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
            val searchable = o.get("searchable")?.let {
                if (it.isJsonPrimitive) it.asString != "0" else true
            } ?: true
            SiteDef(
                key = key,
                name = name.ifBlank { "站点 $key" },
                api = api,
                type = resolution.type,
                searchable = searchable,
                ext = ext,
                sourceId = sourceId,
                sourceName = sourceName,
                unsupported = resolution.unsupported,
                unsupportedReason = resolution.reason,
                jarRaw = o.str("jar").takeIf { it.isNotBlank() },
                jarUrl = resolveAssetUrl(
                    raw = listOf(o.str("jar"), spiderUrl).firstOrNull { it.isNotBlank() }.orEmpty(),
                    base = baseUrl
                ).takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * TVBox 的 jar 字段可能是 `url;md5;hash`，也可能是相对路径（`lib/x.jar`、`./x.jar`、`../x.jar`）、
     * 协议相对 `//host/x.jar`。统一按配置地址的 base 解析成可下载地址。
     */
    fun resolveAssetUrl(raw: String, base: String = ""): String {
        val value = raw.trim().substringBefore(';').substringBefore('#').trim()
        if (value.isBlank()) return ""
        if (value.startsWith("//")) {
            val scheme = if (base.startsWith("http://")) "http:" else "https:"
            return scheme + value
        }
        if (Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(value)) return value
        // 绝对本地路径：禁止再和 base 拼接，否则会出现
        // /storage/emulated/0//storage/emulated/0/xxx.py
        if (value.startsWith("/") || value.startsWith("file://")) {
            return value.removePrefix("file://")
        }
        if (base.isBlank()) return value
        // 本地配置（SAF / 绝对路径）：按配置文件所在目录解析
        if (!base.startsWith("http")) {
            val basePath = base.removePrefix("file://")
            // base 若本身已是目录（无扩展名）直接作 parent；否则取父目录
            val dir = when {
                basePath.endsWith("/") -> basePath.trimEnd('/')
                basePath.contains('.') && basePath.substringAfterLast('/').contains('.') ->
                    basePath.substringBeforeLast('/', "")
                else -> basePath.trimEnd('/')
            }
            if (dir.isBlank()) return value
            val joined = "$dir/${value.removePrefix("./")}"
            return collapseDuplicateStorage(joined)
        }
        return runCatching {
            java.net.URI(base).resolve(value.replace(" ", "%20")).toString()
        }.getOrElse { value }
    }

    /** 折叠误拼的 /storage/emulated/0/.../storage/emulated/0/... */
    fun collapseDuplicateStorage(path: String): String {
        var p = path.replace("//", "/")
        val marker = "/storage/emulated/0/"
        val i = p.indexOf(marker)
        if (i >= 0) {
            val rest = p.substring(i + marker.length)
            if (rest.contains("storage/emulated/0/")) {
                val j = rest.lastIndexOf("storage/emulated/0/")
                p = marker + rest.substring(j + "storage/emulated/0/".length)
            } else if (p.startsWith(marker) && rest.startsWith("/storage/")) {
                p = rest
            }
        }
        // 再压一次双斜杠
        while ("//" in p) p = p.replace("//", "/")
        return p
    }

    private fun resolveType(o: JsonObject, api: String): TypeResolution {
        val jar = o.str("jar")
        val apiLower = api.lowercase().trim()
        val rawType = o.get("type")?.let { if (it.isJsonPrimitive) it.asString else "" }?.trim().orEmpty()
        val extType = runCatching {
            val ext = o.get("ext") ?: return@runCatching ""
            if (ext.isJsonObject) ext.asJsonObject.str("type") else ""
        }.getOrDefault("")

        if (api.startsWith("csp_") || (rawType == "3" && apiLower.endsWith(".jar")))
            return TypeResolution(3, false, "")
        if (jar.isNotBlank() && apiLower.endsWith(".jar"))
            return TypeResolution(3, false, "")
        if (apiLower.endsWith(".js") || apiLower.contains(".js?") || apiLower.contains("jsdelivr"))
            return TypeResolution(3)
        if (apiLower.endsWith(".py") || apiLower.contains(".py?") || extType == "py")
            return TypeResolution(4)
        if (rawType.equals("glass", true) || apiLower.startsWith("glass://")) return TypeResolution(9)
        return when (rawType) {
            "0" -> TypeResolution(0)
            "1" -> TypeResolution(1)
            "2" -> when {
                apiLower.contains(".js") -> TypeResolution(3)
                else -> TypeResolution(1)
            }
            "3" -> TypeResolution(3)
            "4" -> TypeResolution(4)
            else -> if (apiLower.startsWith("glass://")) TypeResolution(9) else TypeResolution(1)
        }
    }

    private fun parseRepoArray(arr: JsonArray): List<RepoEntry> = arr.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val o = el.asJsonObject
        val url = o.str("url", "api")
        if (url.isBlank()) return@mapNotNull null
        RepoEntry(o.str("name").ifBlank { url }, url, o.str("note", "desc"))
    }.distinctBy { it.url }

    fun repoEntries(element: JsonElement?): List<RepoEntry> {
        if (element == null) return emptyList()
        if (element.isJsonArray) return parseRepoArray(element.asJsonArray)
        if (element.isJsonObject) {
            val o = element.asJsonObject
            val arr = (o.get("urls") ?: o.get("repos") ?: o.get("list")) ?: return emptyList()
            if (arr.isJsonArray) return parseRepoArray(arr.asJsonArray)
        }
        return emptyList()
    }

    /** 把 ESM 写法（export default / module.exports）归一为 `var rule =`，供 QuickJS 直接执行。 */
    fun normalizeExport(raw: String): String {
        var text = raw.removePrefix("\uFEFF")
        val exportRegex = Regex("export\\s+default\\s+")
        val moduleRegex = Regex("module\\.exports\\s*=\\s*")
        if (exportRegex.containsMatchIn(text) && !text.contains("var rule")) {
            text = text.replace(exportRegex, "var rule = ")
        } else if (moduleRegex.containsMatchIn(text) && !text.contains("var rule")) {
            text = text.replace(moduleRegex, "var rule = ")
        }
        return text
    }

    fun JsonObject.str(vararg keys: String): String {
        keys.forEach { key ->
            val el = get(key) ?: return@forEach
            if (el.isJsonPrimitive) {
                val v = el.asString
                if (v.isNotBlank() && v != "null") return v
            }
        }
        return ""
    }
}
