package com.glassbox.app.data

import android.content.Context
import com.glassbox.app.engine.SourceParser
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import com.glassbox.app.GlassApp

class AppStore(private val context: Context) {

    private val gson = Gson()
    private val mutex = Mutex()

    val sources = MutableStateFlow<List<SourceEntity>>(emptyList())
    val settings = MutableStateFlow(AppSettings())
    val history = MutableStateFlow<List<WatchRecord>>(emptyList())
    val favorites = MutableStateFlow<List<MediaItem>>(emptyList())
    val searchHistory = MutableStateFlow<List<String>>(emptyList())

    private val sourcesFile = File(context.filesDir, "glass_sources.json")
    private val settingsFile = File(context.filesDir, "glass_settings.json")
    private val historyFile = File(context.filesDir, "glass_history.json")
    private val favoritesFile = File(context.filesDir, "glass_favorites.json")
    private val searchFile = File(context.filesDir, "glass_search.json")

    private inline fun <reified T> read(file: File, fallback: T): T {
        if (!file.exists()) return fallback
        return runCatching {
            gson.fromJson<T>(file.readText(), object : TypeToken<T>() {}.type) ?: fallback
        }.getOrDefault(fallback)
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            sources.value = read<List<SourceEntity>>(sourcesFile, emptyList())
                .map { source -> source.copy(sites = source.sites.map { it.sanitized() }) }
            settings.value = read(settingsFile, AppSettings()).sanitized()
            history.value = read(historyFile, emptyList())
            favorites.value = read(favoritesFile, emptyList())
            searchHistory.value = read(searchFile, emptyList())
            if (sources.value.isEmpty()) {
                sources.value = seed()
                persistSources()
            }
        }
    }

    /**
     * Gson 反序列化会绕过 Kotlin 默认值：旧版本持久化的站点缺少新增字段时会得到 null，
     * 直接 copy() 会抛 NullPointerException。加载时统一补全。
     */
    private fun SiteDef.sanitized(): SiteDef = SiteDef(
        key = key ?: "site",
        name = name ?: "未命名站点",
        api = api ?: "",
        type = type,
        searchable = searchable ?: true,
        ext = ext,
        sourceId = sourceId ?: "",
        sourceName = sourceName ?: "",
        unsupported = unsupported ?: false,
        unsupportedReason = unsupportedReason ?: "",
        enabled = enabled ?: true,
        scriptUrl = scriptUrl,
        jarUrl = jarUrl
    )

    private fun AppSettings.sanitized(): AppSettings = copy(
        selectedSiteKey = selectedSiteKey ?: "",
        selectedSourceId = selectedSourceId ?: "",
        playerKernel = playerKernel?.takeIf { it.isNotBlank() } ?: "mpv",
        // 缺字段时 Gson 保持 int 默认值 0（正好是「适应」），越界值统一夹到 0..4。
        aspectMode = aspectMode.coerceIn(0, 4),
        pictureBrightness = pictureBrightness.coerceIn(-100, 100),
        pictureContrast = pictureContrast.coerceIn(-100, 100),
        pictureSaturation = pictureSaturation.coerceIn(-100, 100),
        pictureHue = pictureHue.coerceIn(-100, 100),
        pictureGamma = pictureGamma.coerceIn(-100, 100),
        picturePresets = (picturePresets ?: emptyList()).filter { it.name.isNotBlank() }.map {
            it.copy(
                brightness = it.brightness.coerceIn(-100, 100),
                contrast = it.contrast.coerceIn(-100, 100),
                saturation = it.saturation.coerceIn(-100, 100),
                hue = it.hue.coerceIn(-100, 100),
                gamma = it.gamma.coerceIn(-100, 100)
            )
        },
        remoteControlPort = remoteControlPort.coerceIn(1024, 65535),
        liveSourceUrl = liveSourceUrl ?: "",
        epgUrl = epgUrl ?: "",
        parseListJson = parseListJson ?: "[]",
        adFilterEnabled = adFilterEnabled,
        skipIntroSec = skipIntroSec.coerceIn(0, 600),
        skipOutroSec = skipOutroSec.coerceIn(0, 600),
        backgroundPlay = backgroundPlay,
        hideStatusBar = hideStatusBar,
        tvMode = tvMode,
        homeCarousel = homeCarousel,
        fontScale = fontScale.coerceIn(0.75f, 1.5f),
        accentIndex = accentIndex.coerceIn(0, 4),
        proxyEnabled = proxyEnabled,
        proxyHost = proxyHost ?: "",
        proxyPort = proxyPort.coerceIn(0, 65535),
        danmuEnabled = danmuEnabled,
        shortDramaPreload = shortDramaPreload
    )

    private fun seed(): List<SourceEntity> {
        val now = System.currentTimeMillis()
        val jsonRaw = runCatching {
            context.assets.open("demo_sources.json").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        val jsRaw = runCatching {
            context.assets.open("demo_spider.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        val pyRaw = runCatching {
            context.assets.open("demo_spider.py").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        val jsonSites = runCatching {
            SourceParser.parseSites(
                com.google.gson.JsonParser.parseString(jsonRaw).asJsonObject,
                DemoContent.SOURCE_ID, "内置示例源 · JSON"
            )
        }.getOrDefault(emptyList())

        return listOf(
            SourceEntity(
                id = DemoContent.SOURCE_ID,
                name = "内置示例源 · JSON",
                type = SourceType.JSON,
                origin = SourceOrigin.BUILTIN,
                raw = jsonRaw,
                enabled = true,
                status = SourceStatus.VALID,
                message = "内置示例配置，含 ${jsonSites.size} 个站点",
                siteCount = jsonSites.size,
                sample = DemoContent.SITE_NAME,
                importedAt = now,
                updatedAt = now,
                sites = jsonSites
            ),
            SourceEntity(
                id = "builtin_js_demo",
                name = "内置示例源 · JS",
                type = SourceType.JS,
                origin = SourceOrigin.BUILTIN,
                raw = jsRaw,
                enabled = false,
                status = SourceStatus.PENDING,
                message = "等待校验（QuickJS）",
                importedAt = now,
                updatedAt = now
            ),
            SourceEntity(
                id = "builtin_py_demo",
                name = "内置示例源 · PY",
                type = SourceType.PY,
                origin = SourceOrigin.BUILTIN,
                raw = pyRaw,
                enabled = false,
                status = SourceStatus.PENDING,
                message = "等待校验（CPython）",
                importedAt = now,
                updatedAt = now
            )
        )
    }

    /**
     * 可用站点 = 各源中「单站点启用」的站点。
     * 不再使用整源 enabled 作为总开关：多源不合并、按平台分组后，粒度下沉到站点。
     */
    fun activeSites(): List<SiteDef> =
        sources.value.filter { it.status == SourceStatus.VALID }
            .flatMap { source ->
                source.sites.filter { !it.unsupported && it.isEnabled }
                    .map { it.copy(sourceId = source.id, sourceName = source.name) }
            }
            .distinctBy { it.key }

    fun allSites(): List<SiteDef> = sources.value.flatMap { source ->
        source.sites.map { it.copy(sourceId = source.id, sourceName = source.name) }
    }.distinctBy { it.key }

    fun sourceOf(site: SiteDef): SourceEntity? =
        sources.value.firstOrNull { it.id == site.sourceId }

    suspend fun upsert(entity: SourceEntity) = mutateSources { list ->
        val index = list.indexOfFirst { it.id == entity.id }
        if (index >= 0) list.toMutableList().also { it[index] = entity } else list + entity
    }

    suspend fun updateSource(id: String, transform: (SourceEntity) -> SourceEntity) = mutateSources { list ->
        list.map { if (it.id == id) transform(it) else it }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutateSources { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    /** 站点级启停：只改一个平台站点，不牵连同源其它站点。 */
    suspend fun setSiteEnabled(siteKey: String, enabled: Boolean) = mutateSources { list ->
        list.map { source ->
            val sites = source.sites.map { if (it.key == siteKey) it.copy(enabled = enabled) else it }
            val anyEnabled = sites.any { !it.unsupported && it.isEnabled }
            source.copy(sites = sites, enabled = anyEnabled)
        }
    }

    /** 某源内的批量启停（对应源卡上的「全选 / 全不选」）。 */
    suspend fun setSitesEnabled(sourceId: String, enabled: Boolean) = mutateSources { list ->
        list.map { source ->
            if (source.id != sourceId) source else source.copy(
                sites = source.sites.map { it.copy(enabled = enabled) },
                enabled = enabled
            )
        }
    }

    suspend fun delete(id: String) = mutateSources { list -> list.filterNot { it.id == id } }

    private suspend fun mutateSources(block: (List<SourceEntity>) -> List<SourceEntity>) {
        mutex.withLock {
            sources.value = block(sources.value)
            persistSources()
        }
    }

    private fun persistSources() {
        runCatching { sourcesFile.writeText(gson.toJson(sources.value)) }
    }

    suspend fun saveSettings(value: AppSettings) = withContext(Dispatchers.IO) {
        settings.value = value
        runCatching { settingsFile.writeText(gson.toJson(value)) }
    }

    suspend fun recordWatch(record: WatchRecord) = withContext(Dispatchers.IO) {
        val next = (listOf(record) + history.value.filterNot { it.key == record.key }).take(30)
        history.value = next
        runCatching { historyFile.writeText(gson.toJson(next)) }
        runCatching { GlassApp.instance.library.upsertHistory(record) }
    }

    suspend fun removeHistory(key: String) = withContext(Dispatchers.IO) {
        history.value = history.value.filterNot { it.key == key }
        runCatching { historyFile.writeText(gson.toJson(history.value)) }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        history.value = emptyList()
        runCatching { historyFile.writeText("[]") }
        runCatching { GlassApp.instance.library.clearHistory() }
    }

    suspend fun toggleFavorite(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        val exists = favorites.value.any { it.globalKey == item.globalKey }
        favorites.value = if (exists) favorites.value.filterNot { it.globalKey == item.globalKey }
        else listOf(item) + favorites.value
        runCatching { favoritesFile.writeText(gson.toJson(favorites.value)) }
        runCatching { GlassApp.instance.library.toggleFavorite(item) }
        !exists
    }

    suspend fun addSearchHistory(keyword: String) = withContext(Dispatchers.IO) {
        val k = keyword.trim()
        if (k.isEmpty()) return@withContext
        searchHistory.value = (listOf(k) + searchHistory.value.filterNot { it == k }).take(10)
        runCatching { searchFile.writeText(gson.toJson(searchHistory.value)) }
    }

    suspend fun clearSearchHistory() = withContext(Dispatchers.IO) {
        searchHistory.value = emptyList()
        runCatching { searchFile.writeText("[]") }
    }

    fun watchOf(item: MediaItem): WatchRecord? =
        history.value.firstOrNull { it.key == item.globalKey }

    fun setting(): AppSettings = settings.value
}
