package com.glassbox.app.engine

import com.glassbox.app.core.Net
import com.glassbox.app.data.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

object PyExecutor {
    private val gson = Gson()
    private val moduleLock = Any()

    @Volatile
    private var module: PyObject? = null

    private fun module(): PyObject {
        module?.let { return it }
        synchronized(moduleLock) {
            module?.let { return it }
            (com.glassbox.app.GlassApp.instance).ensurePython()
            val m = Python.getInstance().getModule("glass_spider")
            module = m
            return m
        }
    }

    suspend fun probe(raw: String, ext: String = "{}", path: String = ""): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            val json = withTimeout(20000) {
                module().callAttr("probe", raw, ext, path).toString()
            }
            val obj = JsonParser.parseString(json).asJsonObject
            val ok = obj.get("ok")?.asBoolean ?: false
            if (!ok) {
                return@withContext ProbeResult(
                    false,
                    obj.get("message")?.asString ?: "PY 校验失败",
                    emptyList(),
                    elapsedMs = System.currentTimeMillis() - started
                )
            }
            val meta = obj.getAsJsonObject("meta")
            val title = SpiderJson.str(meta, "siteName", "title", "name").ifBlank { "PY 源" }
            val key = SpiderJson.str(meta, "siteKey", "key").ifBlank { "py_" + Integer.toHexString(raw.hashCode()) }
            val site = SiteDef(
                key = key, name = title, api = "py://$key", type = 4, searchable = true,
                ext = ext, sourceId = "", sourceName = title
            )
            ProbeResult(
                true,
                obj.get("message")?.asString ?: "CPython 执行通过",
                listOf(site),
                obj.get("sample")?.asString?.takeIf { it.isNotBlank() },
                System.currentTimeMillis() - started
            )
        } catch (t: Throwable) {
            val timedOut = t is kotlinx.coroutines.TimeoutCancellationException
            // 原版超时后不重建实例，后续请求会打在死连接上；这里清缓存强制下次重新装载。
            if (timedOut) runCatching { module().callAttr("reset", raw) }
            ProbeResult(
                false,
                when {
                    timedOut -> "PY 执行超时（20s），已重建脚本实例"
                    else -> "PY 运行时异常：${t.message ?: t.javaClass.simpleName}"
                }.take(300),
                emptyList(),
                elapsedMs = System.currentTimeMillis() - started
            )
        }
    }

    suspend fun call(raw: String, fn: String, args: List<Any?> = emptyList(), ext: String = "{}", path: String = ""): String {
        return withContext(Dispatchers.IO) {
            try {
                withTimeout(20000) {
                    val json = module().callAttr("invoke", raw, fn, gson.toJson(args), ext, path).toString()
                    val obj = JsonParser.parseString(json).asJsonObject
                    if (obj.get("missing")?.asBoolean == true) {
                        throw IllegalStateException(obj.get("message")?.asString ?: "PY 未实现 $fn")
                    }
                    if (!obj.get("ok").asBoolean) {
                        throw IllegalStateException(obj.get("message")?.asString ?: "PY 调用失败")
                    }
                    val data = obj.get("data")
                    if (data == null || data.isJsonNull) "null" else gson.toJson(data)
                }
            } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
                runCatching { module().callAttr("reset", raw) }
                throw IllegalStateException("PY 执行超时（20s），已重建脚本实例", t)
            }
        }
    }
}

class PySpider(private val source: SourceEntity, private val site: SiteDef) : SiteSpider {

    private var cachedCode: String? = null

    private suspend fun code(): String {
        cachedCode?.let { return it }
        val value = ScriptResolver.code(site, source)
        cachedCode = value
        return value
    }

    private fun ext(): String = site.ext?.takeIf { it.trim().startsWith("{") } ?: "{}"

    /** 脚本的真实/逻辑路径，用于填充 `__file__`（缺它会 NameError）。 */
    private fun path(): String = ScriptResolver.logicalPath(site.api, source.url)

    override suspend fun home(): List<HomeSection> {
        val raw = PyExecutor.call(code(), "home", emptyList(), ext(), path())
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return emptyList()
        val sections = SpiderJson.sections(root, site)
        // 空结果必须可见：PY 脚本最常见的原因是裸路径 /storage/emulated/0 没授权，
        // 脚本内部 `except` 又会吞掉 PermissionError，最终只剩「没有返回分类」。
        if (sections.isEmpty() && root.isJsonObject) {
            val obj = root.asJsonObject
            if (!obj.has("class") && !obj.has("list") && !obj.has("data")) {
                throw IllegalStateException(
                    "PY home() 返回空结构（无 class/list）：若脚本扫描本地目录，请到 设置 → 所有文件访问权限 授权；raw=${raw.take(120)}"
                )
            }
        }
        if (sections.isEmpty() && root.isJsonObject) {
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

    override suspend fun catalog(): SiteCatalog {
        val raw = PyExecutor.call(code(), "home", emptyList(), ext(), path())
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return SiteCatalog()
        return SpiderJson.catalog(root, site)
    }

    override suspend fun category(tid: String, page: Int, extend: Map<String, String>): List<MediaItem> {
        val raw = PyExecutor.call(code(), "category", listOf(tid, page, true, extend), ext(), path())
        return itemsOf(raw)
    }

    override suspend fun search(keyword: String): List<MediaItem> {
        val raw = PyExecutor.call(code(), "search", listOf(keyword, false, 1), ext(), path())
        return itemsOf(raw)
    }

    override suspend fun detail(id: String): MediaDetail? {
        val raw = PyExecutor.call(code(), "detail", listOf(id), ext(), path())
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return null
        if (root.isJsonNull) return null
        return SpiderJson.detail(root, site, id)
    }

    override suspend fun resolvePlay(flag: String, id: String): PlaySource? {
        val raw = PyExecutor.call(code(), "player", listOf(flag, id, 0), ext(), path())
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return null
        if (!root.isJsonObject) return null
        val o = root.asJsonObject
        val url = SpiderJson.str(o, "url", "playUrl", "play_url").trim()
        if (url.isBlank() || url == "null") return null
        return PlaySource(url)
    }

    private fun itemsOf(raw: String): List<MediaItem> {
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return emptyList()
        val list = if (root.isJsonObject) {
            root.asJsonObject.get("list") ?: root.asJsonObject.get("data") ?: root
        } else root
        return SpiderJson.items(list, site)
    }
}
