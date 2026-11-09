package com.glassbox.app.engine

import com.glassbox.app.data.*

data class ProbeResult(
    val ok: Boolean,
    val message: String,
    val sites: List<SiteDef>,
    val sample: String? = null,
    val elapsedMs: Long = 0
)

data class PlaySource(val url: String, val headers: Map<String, String> = emptyMap())

interface SiteSpider {
    suspend fun home(): List<HomeSection>

    /** 站点自身分类与各分类的筛选条件；默认无目录。 */
    suspend fun catalog(): SiteCatalog = SiteCatalog()

    suspend fun category(tid: String, page: Int, extend: Map<String, String> = emptyMap()): List<MediaItem>
    suspend fun search(keyword: String): List<MediaItem>
    suspend fun detail(id: String): MediaDetail?

    /**
     * 脚本源（TVBox 规则）详情里常只返回播放 id（如 `vid|nid|0`），
     * 需要在起播前调用 playerContent 解析成真实地址。默认源无需解析。
     */
    suspend fun resolvePlay(flag: String, id: String): PlaySource? = null
}

class DemoSpider : SiteSpider {
    override suspend fun home(): List<HomeSection> = DemoContent.homeSections()
    override suspend fun catalog(): SiteCatalog = DemoContent.catalog()
    override suspend fun category(tid: String, page: Int, extend: Map<String, String>): List<MediaItem> =
        DemoContent.category(tid, extend)
    override suspend fun search(keyword: String): List<MediaItem> = DemoContent.search(keyword)
    override suspend fun detail(id: String): MediaDetail? = DemoContent.detail(id)
}

class SourceEngine {

    fun spider(site: SiteDef, source: SourceEntity?): SiteSpider {
        if (site.type == 9 || site.api.startsWith("glass://")) return DemoSpider()
        if (site.api.startsWith("csp_")) return JarSpider(site)
        if (site.unsupported) return EmptySpider("站点需要 JAR spider，本版暂不支持")
        if (site.type == 3 && source != null) return JsSpider(source, site)
        if (site.type == 4 && source != null) return PySpider(source, site)
        return CmsSpider(site, jsonMode = site.type != 0)
    }

    suspend fun probe(entity: SourceEntity): ProbeResult {
        return when (entity.type) {
            SourceType.JSON -> {
                val parsed = runCatching {
                    SourceParser.detect(entity.raw, entity.name, entity.url.orEmpty())
                }.getOrNull()
                when (parsed) {
                    is DetectResult.Config -> {
                        val supported = parsed.sites.count { !it.unsupported }
                        if (parsed.sites.isEmpty()) ProbeResult(false, "配置中没有可用站点", emptyList())
                        else if (supported == 0) ProbeResult(
                            false,
                            "解析到 ${parsed.sites.size} 个站点，但全部依赖 JAR spider，当前版本无法运行",
                            emptyList()
                        )
                        else {
                            val jars = parsed.sites.size - supported
                            val extra = if (jars > 0) "，另有 $jars 个 JAR 站点不可用" else ""
                            ProbeResult(
                                true,
                                "解析到 $supported 个可运行站点$extra",
                                parsed.sites.map {
                                    it.copy(sourceId = entity.id, sourceName = entity.name)
                                },
                                parsed.sites.firstOrNull { !it.unsupported }?.name
                            )
                        }
                    }
                    is DetectResult.Unsupported -> ProbeResult(false, parsed.reason, emptyList())
                    is DetectResult.RepoIndex -> ProbeResult(false, "这是多仓索引，请选择具体仓库后导入", emptyList())
                    is DetectResult.Script -> ProbeResult(false, "配置内容被识别为脚本", emptyList())
                    null -> ProbeResult(false, "无法解析 JSON 配置", emptyList())
                }
            }
            SourceType.JS -> {
                val result = JsExecutor.probe(SourceParser.normalizeExport(entity.raw))
                if (!result.ok) result
                else ProbeResult(
                    true, result.message,
                    result.sites.map { it.copy(sourceId = entity.id, sourceName = entity.name) },
                    result.sample, result.elapsedMs
                )
            }
            SourceType.PY -> {
                val result = PyExecutor.probe(
                    entity.raw,
                    path = ScriptResolver.logicalPath("source.py", entity.url)
                )
                if (!result.ok) result
                else ProbeResult(
                    true, result.message,
                    result.sites.map { it.copy(sourceId = entity.id, sourceName = entity.name) },
                    result.sample, result.elapsedMs
                )
            }
        }
    }

    suspend fun testSite(site: SiteDef, source: SourceEntity?): ProbeResult {
        val started = System.currentTimeMillis()
        if (site.unsupported) {
            return ProbeResult(false, site.unsupportedReason.ifBlank { "该站点暂不可用" }, listOf(site))
        }
        return try {
            val spider = spider(site, source)
            val sections = kotlinx.coroutines.withTimeout(15_000L) { spider.home() }
            val items = sections.sumOf { it.items.size }
            val first = sections.firstOrNull { it.items.isNotEmpty() }
            ProbeResult(
                ok = items > 0,
                message = if (items > 0) "首页返回 ${sections.size} 个分区 · $items 项"
                else "接口可连通，但没有解析到内容（检查字段映射或分类接口）",
                sites = listOf(site),
                sample = first?.items?.firstOrNull()?.title,
                elapsedMs = System.currentTimeMillis() - started
            )
        } catch (t: Throwable) {
            ProbeResult(
                false,
                "站点请求失败：${t.message ?: t.javaClass.simpleName}".take(200),
                listOf(site),
                elapsedMs = System.currentTimeMillis() - started
            )
        }
    }
}

/** 不可用站点占位，避免把 JAR 站点误当 JS/CMS 请求 */
class EmptySpider(private val reason: String) : SiteSpider {
    override suspend fun home(): List<HomeSection> = emptyList()
    override suspend fun category(tid: String, page: Int, extend: Map<String, String>): List<MediaItem> =
        emptyList()
    override suspend fun search(keyword: String): List<MediaItem> = emptyList()
    override suspend fun detail(id: String): MediaDetail? = null
}
