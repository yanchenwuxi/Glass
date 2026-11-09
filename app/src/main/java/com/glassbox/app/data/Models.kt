package com.glassbox.app.data

import com.google.gson.Gson

enum class SourceType(val label: String, val badge: String) {
    JSON("JSON", "配置"), JS("JS", "脚本"), PY("PY", "脚本")
}

enum class SourceOrigin(val label: String) {
    BUILTIN("内置示例"), FILE("本地文件"), CLIPBOARD("剪贴板"), URL("在线链接")
}

enum class SourceStatus(val label: String) {
    PENDING("待校验"), VALID("校验通过"), INVALID("校验失败")
}

data class SiteDef(
    val key: String,
    val name: String,
    val api: String,
    val type: Int,             // 0 xml cms, 1 json cms, 3 js, 4 py, 9 glass demo
    val searchable: Boolean = true,
    val ext: String? = null,
    val sourceId: String = "",
    val sourceName: String = "",
    val unsupported: Boolean = false,
    val unsupportedReason: String = "",
    /** 站点的脚本位置（相对配置的 api 解析后的绝对地址，如 SAF 目录 / 本地路径 / http）。 */
    val scriptUrl: String? = null,
    /** 该站点需要的 TVBox JAR spider 地址（站点级 jar 优先，否则是配置顶层的 spider）。 */
    val jarUrl: String? = null,
    /** 配置里 jar 字段的原文（可能为空/相对路径），用于失败时把原始值与解析结果一起展示。 */
    val jarRaw: String? = null,
    /** 站点级启停：多源不合并、按平台分组后，开关粒度从「整个源」下沉到「单站点」。null 视为启用。 */
    val enabled: Boolean? = null
) {
    val isEnabled: Boolean get() = enabled ?: true

    val kindLabel: String
        get() = when {
            api.startsWith("csp_") || (!jarUrl.isNullOrBlank() && type == 3) -> "JAR"
            unsupported -> "JAR"
            type == 0 -> "XML CMS"
            type == 1 -> "JSON CMS"
            type == 3 -> "JS"
            type == 4 -> "PY"
            type == 9 -> "内置"
            else -> "TYPE $type"
        }
}

data class SourceEntity(
    val id: String,
    val name: String,
    val type: SourceType,
    val origin: SourceOrigin,
    val raw: String,
    val url: String? = null,
    val enabled: Boolean = false,
    val status: SourceStatus = SourceStatus.PENDING,
    val message: String = "",
    val siteCount: Int = 0,
    val sample: String? = null,
    val importedAt: Long = 0,
    val updatedAt: Long = 0,
    val sites: List<SiteDef> = emptyList()
) {
    val siteKindSummary: String
        get() = sites.map { it.kindLabel }.distinct().joinToString(" / ").ifBlank { type.label }
}

data class MediaItem(
    val id: String,
    val title: String,
    val poster: String = "",
    val backdrop: String = "",
    val remarks: String = "",
    val year: String = "",
    val area: String = "",
    val typeName: String = "",
    val score: String = "",
    val siteKey: String = "",
    val siteName: String = "",
    val sourceId: String = ""
) {
    val globalKey: String get() = "$siteKey::$id"
}

data class Episode(
    val name: String,
    val url: String
)

data class EpisodeGroup(
    val title: String,
    val episodes: List<Episode>
) {
    /** 直接可播放的媒体地址数量；CMS 中"/share/"网页解析线路得分为 0 */
    val playableCount: Int
        get() = episodes.count { it.isDirectMedia() }
}

private val DIRECT_MEDIA_SUFFIX = listOf(
    ".m3u8", ".mp4", ".flv", ".ts", ".mkv", ".mov", ".webm", ".avi", ".m4v"
)

fun Episode.isDirectMedia(): Boolean {
    val url = url.lowercase().substringBefore('?')
    if (!url.startsWith("http")) return false
    return DIRECT_MEDIA_SUFFIX.any { url.endsWith(it) } || url.contains(".m3u8")
}

/** 播放线路排序：把直链媒体线路提前，避免默认落到网页解析线路导致播放失败 */
fun List<EpisodeGroup>.playableFirst(): List<EpisodeGroup> =
    sortedByDescending { it.playableCount }

data class MediaDetail(
    val item: MediaItem,
    val description: String = "",
    val actors: String = "",
    val director: String = "",
    val groups: List<EpisodeGroup> = emptyList(),
    val recommend: List<MediaItem> = emptyList()
) {
    val firstGroup: EpisodeGroup? get() = groups.firstOrNull { it.episodes.isNotEmpty() }
}

data class HomeSection(
    val title: String,
    val subtitle: String = "",
    val items: List<MediaItem> = emptyList(),
    val style: SectionStyle = SectionStyle.ROW
)

enum class SectionStyle { HERO, ROW, GRID }

/** 站点自身分类（来源：CMS 的 class 数组 / 脚本 home 返回的 class）。 */
data class SiteCategory(val id: String, val name: String)

data class FilterOption(val name: String, val value: String)

/** 分类自带的筛选项（来源：CMS 的 filters[tid] / 脚本 home 返回的 filters）。 */
data class FilterGroup(
    val key: String,
    val name: String,
    val options: List<FilterOption> = emptyList()
)

/**
 * 站点目录：分类、各分类的筛选条件，以及仅返回首页列表、没有 class 时的兜底条目。
 */
data class SiteCatalog(
    val categories: List<SiteCategory> = emptyList(),
    val filters: Map<String, List<FilterGroup>> = emptyMap(),
    val fallbackItems: List<MediaItem> = emptyList()
)

/** 起播准备结果：真实地址 + 请求头，或地址层可读错误（这类错误不允许触发内核降级）。 */
data class PlaybackPrep(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val error: String? = null,
    /** 探测到响应体是 #EXTM3U，内核需要无视地址后缀/Content-Type 直接按 HLS 打开。 */
    val isHls: Boolean = false
)

data class PlayInfo(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val title: String = "",
    val episodeName: String = ""
)

data class WatchRecord(
    val key: String,
    val title: String,
    val poster: String = "",
    val siteKey: String = "",
    val siteName: String = "",
    val episodeIndex: Int = 0,
    val episodeName: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0
)

data class AppSettings(
    val autoResume: Boolean = true,
    val defaultSpeed: Float = 1.0f,
    val hardwareDecode: Boolean = true,
    val glassIntensity: Float = 1.0f,
    val animateGlow: Boolean = true,
    val reduceMotion: Boolean = false,
    val darkTheme: Boolean = false,
    val preferredGroup: Int = 0,
    val selectedSiteKey: String = "",
    val selectedSourceId: String = "",
    /** 播放内核：mpv（默认）/ exo / ijk，对应 BieCuiLe 的三内核切换。 */
    val playerKernel: String = "mpv",
    /** 画面比例档位（0 适应 / 1 拉伸 / 2 裁剪 / 3 16:9 / 4 4:3），退出重进后记忆。 */
    val aspectMode: Int = 0,
    // 画面调节（libmpv video equalizer 的百分比，-100..100，0 为原始画面）。
    // BieCuiLe 1.1.5 实测：退出播放页/重启应用后仍保留，因此这里持久化。
    val pictureBrightness: Int = 0,
    val pictureContrast: Int = 0,
    val pictureSaturation: Int = 0,
    val pictureHue: Int = 0,
    val pictureGamma: Int = 0,
    val picturePresets: List<PicturePreset> = emptyList(),
    /** 局域网远程控制（NanoHTTPD，默认端口 9978） */
    val remoteControlEnabled: Boolean = false,
    val remoteControlPort: Int = 9978,
    /** 最近使用的直播源 URL */
    val liveSourceUrl: String = "",
    /** 解析器列表（JSON 字符串数组，可选；空则仅用脚本 resolvePlay） */
    val parseListJson: String = "[]"
)

/** 画面预设：原版「+存为预设」保存的一组滑杆数值。 */
data class PicturePreset(
    val name: String = "",
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val hue: Int = 0,
    val gamma: Int = 0
)

data class SearchHit(val item: MediaItem, val site: SiteDef)

class StoreJson {
    val gson: Gson = Gson()
}
