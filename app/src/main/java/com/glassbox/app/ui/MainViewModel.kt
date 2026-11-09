package com.glassbox.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glassbox.app.GlassApp
import com.glassbox.app.core.Net
import com.glassbox.app.core.displayName
import com.glassbox.app.data.*
import com.glassbox.app.engine.*
import com.glassbox.app.engine.live.LiveChannelGroup
import com.glassbox.app.engine.live.LiveEpgDay
import com.glassbox.app.engine.live.LiveEpgProgram
import com.glassbox.app.engine.dlna.DlnaDevice
import com.glassbox.app.engine.parse.ParseDef
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.UUID

data class HomeUiState(
    val loading: Boolean = true,
    val siteKey: String = "",
    val siteName: String = "",
    val sourceName: String = "",
    /** 当前站点自身的分类，首页顶部分类栏的数据源 */
    val categories: List<SiteCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    /** 分类 id → 该分类自带的筛选组 */
    val filterMap: Map<String, List<FilterGroup>> = emptyMap(),
    /** 当前分类的筛选组 */
    val filters: List<FilterGroup> = emptyList(),
    /** 已选筛选值，key = FilterGroup.key */
    val extend: Map<String, String> = emptyMap(),
    val items: List<MediaItem> = emptyList(),
    /** 已加载到的页码（1 基，与原版一致）；0 表示第 1 页还没回来 */
    val page: Int = 0,
    /** 触底加载中（底部显示 loading 项） */
    val loadingMore: Boolean = false,
    /** 还有下一页；false 时底部显示「没有更多了」并停止请求 */
    val hasMore: Boolean = true,
    /** 触底加载失败原因；保留已有条目，仅底部错误项可见 */
    val loadMoreError: String? = null,
    val errors: List<String> = emptyList(),
    val loadedSites: Int = 0,
    val refreshedAt: Long = 0,
    val emptyReason: String = "",
    val failed: Boolean = false
) {
    val selectedCategory: SiteCategory?
        get() = categories.firstOrNull { it.id == selectedCategoryId }
}

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    val errors: List<String> = emptyList()
)

data class DetailUiState(
    val item: MediaItem? = null,
    val detail: MediaDetail? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val favorite: Boolean = false,
    val watch: WatchRecord? = null
)

data class RepoPick(val entries: List<RepoEntry>, val fromUrl: String)

data class ImportPreview(
    val id: String,
    val name: String,
    val type: SourceType,
    val origin: SourceOrigin,
    val raw: String,
    val url: String? = null,
    val ok: Boolean = false,
    val message: String = "",
    val sites: List<SiteDef> = emptyList(),
    val repos: List<RepoEntry> = emptyList(),
    val elapsedMs: Long = 0
)

data class ImportUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val preview: ImportPreview? = null,
    val repo: RepoPick? = null,
    /** 目录导入时的增量进度文本，例如「12/40 · 本地.json」 */
    val progress: String = ""
)

data class PlayRequest(
    val detail: MediaDetail,
    val groupIndex: Int,
    val episodeIndex: Int,
    val startPositionMs: Long = 0
)

/** 局域网远程控制指令，由 RemoteControlServer 写入、PlayerScreen 消费。 */
data class RemoteCommand(
    val action: String,
    val extras: Map<String, String> = emptyMap(),
    val seq: Long = System.currentTimeMillis()
)

/** 资源页批量操作类型。 */
enum class SourceBatchAction(val label: String) {
    ENABLE("启用"),
    DISABLE("停用"),
    REFRESH("更新"),
    DELETE("删除")
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store: AppStore = (app as GlassApp).store
    private val engine: SourceEngine = (app as GlassApp).engine
    private val ctx: Context = app

    val sources: StateFlow<List<SourceEntity>> = store.sources.asStateFlow()
    val settings: StateFlow<AppSettings> = store.settings.asStateFlow()
    fun setting(): AppSettings = settings.value
    val history: StateFlow<List<WatchRecord>> = store.history.asStateFlow()
    val favorites: StateFlow<List<MediaItem>> = store.favorites.asStateFlow()
    val searchHistory: StateFlow<List<String>> = store.searchHistory.asStateFlow()

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _detail = MutableStateFlow(DetailUiState())
    val detail: StateFlow<DetailUiState> = _detail.asStateFlow()

    private val _import = MutableStateFlow(ImportUiState())
    val importState: StateFlow<ImportUiState> = _import.asStateFlow()

    private val _pendingPlay = MutableStateFlow<PlayRequest?>(null)
    val pendingPlay: StateFlow<PlayRequest?> = _pendingPlay.asStateFlow()

    // —— P1 直播 ——
    private val _liveGroups = MutableStateFlow<List<LiveChannelGroup>>(emptyList())
    val liveGroups: StateFlow<List<LiveChannelGroup>> = _liveGroups.asStateFlow()
    private val _liveLoading = MutableStateFlow(false)
    val liveLoading: StateFlow<Boolean> = _liveLoading.asStateFlow()
    private val _liveError = MutableStateFlow<String?>(null)
    val liveError: StateFlow<String?> = _liveError.asStateFlow()

    private val _remoteCommand = MutableStateFlow<RemoteCommand?>(null)
    val remoteCommand: StateFlow<RemoteCommand?> = _remoteCommand.asStateFlow()

    private val _dlnaDevices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val dlnaDevices: StateFlow<List<DlnaDevice>> = _dlnaDevices.asStateFlow()
    private val _dlnaBusy = MutableStateFlow(false)
    val dlnaBusy: StateFlow<Boolean> = _dlnaBusy.asStateFlow()
    private val _dlnaMessage = MutableStateFlow<String?>(null)
    val dlnaMessage: StateFlow<String?> = _dlnaMessage.asStateFlow()

    private val _epgDays = MutableStateFlow<List<LiveEpgDay>>(emptyList())
    val epgDays: StateFlow<List<LiveEpgDay>> = _epgDays.asStateFlow()
    private val _epgLoading = MutableStateFlow(false)
    val epgLoading: StateFlow<Boolean> = _epgLoading.asStateFlow()
    private val _epgNowNext = MutableStateFlow<Pair<LiveEpgProgram?, LiveEpgProgram?>>(null to null)
    val epgNowNext: StateFlow<Pair<LiveEpgProgram?, LiveEpgProgram?>> = _epgNowNext.asStateFlow()


    /** 播放页回写，供 /status 查询 */
    @Volatile
    var playbackStatusJson: String = """{"ok":true,"playing":false,"page":"idle"}"""

    private val glassApp get() = getApplication<GlassApp>()

    private val _sites = MutableStateFlow<List<SiteDef>>(emptyList())
    /** 站点切换抽屉的数据源：所有已导入源里可运行的站点 */
    val availableSites: StateFlow<List<SiteDef>> = _sites.asStateFlow()

    private val _currentSite = MutableStateFlow<SiteDef?>(null)
    val currentSite: StateFlow<SiteDef?> = _currentSite.asStateFlow()

    private var searchJob: Job? = null
    private var homeJob: Job? = null
    private var categoryJob: Job? = null
    private var loadMoreJob: Job? = null
    private var initialized = false

    private companion object {
        /** 目录导入只认这三类源文件，其余扩展名在扫描阶段直接丢弃，不进入读取/探测队列。 */
        val SOURCE_EXTENSIONS = setOf("json", "js", "py")
    }

    init {
        viewModelScope.launch {
            store.load()
            migrateLibraryFromStore()
            applyRemoteControl(store.settings.value)
            initDefaults()
            validatePendingSources()
            resolveSelection()
            refreshHome()
            store.sources.collect { list ->
                val all = list.flatMap { source ->
                    source.sites.filter { !it.unsupported }.map {
                        it.copy(sourceId = source.id, sourceName = source.name)
                    }
                }
                // 多个源可能导入同一份配置（站点 key 重复），抽屉列表的 key 必须唯一，否则 LazyGrid 会崩溃。
                // 保留首次出现的站点，并把当前选中站点择出来确保其 sourceId/sourceName 正确。
                val unique = all.distinctBy { it.key }
                _sites.value = unique
                val current = _currentSite.value
                val sameKey = current?.let { c -> unique.firstOrNull { it.key == c.key } }
                if (current != null) {
                    if (sameKey == null) {
                        resolveSelection()
                    } else if (sameKey.sourceId != current.sourceId || sameKey.sourceName != current.sourceName) {
                        _currentSite.value = sameKey
                        refreshHome(force = true)
                    }
                }
            }
        }
    }

    private suspend fun initDefaults() {
        if (!initialized) {
            initialized = true
            if (store.settings.value.darkTheme) { /* 默认深色，无需额外处理 */ }
        }
    }

    /** 选中优先级：已保存站点 → 已保存接口 → 第一个启用源 → 第一个校验通过的源 */
    private fun resolveSelection() {
        val sources = store.sources.value
        val settings = store.settings.value
        val all = sources.flatMap { source ->
            source.sites.filter { !it.unsupported }.map { it.copy(sourceId = source.id, sourceName = source.name) }
        }
        val active = sources.filter { it.status == SourceStatus.VALID }
            .flatMap { source ->
                source.sites.filter { !it.unsupported && it.isEnabled }
                    .map { it.copy(sourceId = source.id, sourceName = source.name) }
            }
        val picked = all.firstOrNull { it.key == settings.selectedSiteKey && it.sourceId == settings.selectedSourceId }
            ?: all.firstOrNull { it.key == settings.selectedSiteKey }
            ?: all.firstOrNull { it.sourceId == settings.selectedSourceId }
            ?: active.firstOrNull()
            ?: all.firstOrNull()
        _currentSite.value = picked
        if (picked != null && (settings.selectedSiteKey != picked.key || settings.selectedSourceId != picked.sourceId)) {
            viewModelScope.launch {
                store.saveSettings(
                    store.settings.value.copy(
                        selectedSiteKey = picked.key,
                        selectedSourceId = picked.sourceId
                    )
                )
            }
        }
    }

    fun selectSite(site: SiteDef) {
        viewModelScope.launch {
            val source = store.sources.value.firstOrNull { it.id == site.sourceId }
            if (source != null && !site.isEnabled) {
                store.setSiteEnabled(site.key, true)
            }
            store.saveSettings(
                store.settings.value.copy(selectedSiteKey = site.key, selectedSourceId = site.sourceId)
            )
            _currentSite.value = site.copy(enabled = true)
            _sites.value = _sites.value.map {
                if (it.key == site.key) it.copy(sourceName = source?.name ?: it.sourceName, enabled = true) else it
            }
            refreshHome(force = true)
        }
    }

    fun selectSource(source: SourceEntity) {
        val site = source.sites.firstOrNull { !it.unsupported } ?: return
        selectSite(site.copy(sourceId = source.id, sourceName = source.name))
    }

    private suspend fun validatePendingSources() {
        val pending = store.sources.value.filter { it.status == SourceStatus.PENDING }
        pending.take(4).forEach { source ->
            runCatching {
                val result = engine.probe(source)
                store.updateSource(source.id) {
                    it.copy(
                        status = if (result.ok) SourceStatus.VALID else SourceStatus.INVALID,
                        message = result.message,
                        sites = result.sites,
                        siteCount = result.sites.size,
                        sample = result.sample,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }
        }
    }

    /**
     * 首页只加载"当前站点"：
     * 1) 先取站点自身分类与各分类的筛选条件；
     * 2) 选中分类（切换站点时回到第一个分类）后拉第一页片源；
     * - 15s 超时预算，失败自动重试 1 次；
     * - 同一时刻只保留一个加载任务（并发去重）；
     * - 失败/空结果不再静默回退到示例数据，而是给可重试的明确状态。
     */
    fun refreshHome(force: Boolean = true) {
        if (!force && _home.value.loading) return
        homeJob?.cancel()
        categoryJob?.cancel()
        val site = _currentSite.value ?: resolveSelection().let { _currentSite.value }
        Log.i(
            "GlassHome",
            "失效重建：refreshHome(force=$force) site=${site?.key} " +
                "prevCategory=${_home.value.selectedCategoryId} prevPage=${_home.value.page}"
        )
        homeJob = viewModelScope.launch {
            if (site == null) {
                _home.value = HomeUiState(
                    loading = false,
                    siteName = "未选择站点",
                    emptyReason = "还没有可用的接口。请到源管理导入 JSON / JS / PY 源并启用。",
                    refreshedAt = System.currentTimeMillis()
                )
                return@launch
            }
            val prev = _home.value
            // 切换到不同站点时必须清空旧站点的分类与条目，避免加载期间/失败后仍显示上一个站点的内容。
            val switching = prev.siteKey != site.key || prev.sourceName != site.sourceName
            val keepCategoryId = if (!switching) prev.selectedCategoryId else null
            val keepExtend = if (!switching) prev.extend else emptyMap()
            _home.value = if (switching) {
                HomeUiState(
                    loading = true,
                    siteKey = site.key,
                    siteName = site.name,
                    sourceName = site.sourceName,
                    loadedSites = 1
                )
            } else {
                prev.copy(
                    loading = true,
                    errors = emptyList(),
                    siteKey = site.key,
                    siteName = site.name,
                    sourceName = site.sourceName,
                    emptyReason = "",
                    failed = false,
                    loadedSites = 1,
                    page = 0,
                    loadingMore = false,
                    hasMore = true,
                    loadMoreError = null
                )
            }
            val source = store.sourceOf(site)
            val spider = engine.spider(site, source)
            var catalog: SiteCatalog? = null
            var catalogError: String? = null
            for (attempt in 0 until 2) {
                try {
                    catalog = withContext(Dispatchers.IO) {
                        withTimeout(15_000L) { spider.catalog() }
                    }
                    catalogError = null
                    break
                } catch (t: Throwable) {
                    catalogError = t.message ?: t.javaClass.simpleName
                    if (attempt == 0) delay(600)
                }
            }
            val loaded = catalog
            if (loaded == null) {
                _home.value = _home.value.copy(
                    loading = false,
                    categories = emptyList(),
                    selectedCategoryId = null,
                    filterMap = emptyMap(),
                    filters = emptyList(),
                    items = emptyList(),
                    errors = listOf("${displayName(site.name)} · ${catalogError ?: "分类加载失败"}"),
                    failed = true,
                    emptyReason = "分类加载失败，请检查网络或换一个站点",
                    refreshedAt = System.currentTimeMillis()
                )
                return@launch
            }
            // 只返回首页列表、没有 class 的脚本源，退化为单个「全部」分类，直接使用首页条目。
            val categories = loaded.categories.ifEmpty {
                if (loaded.fallbackItems.isNotEmpty()) listOf(SiteCategory("", "全部")) else emptyList()
            }
            if (categories.isEmpty()) {
                _home.value = _home.value.copy(
                    loading = false,
                    categories = emptyList(),
                    selectedCategoryId = null,
                    items = emptyList(),
                    errors = emptyList(),
                    failed = false,
                    emptyReason = "当前站点没有返回分类",
                    refreshedAt = System.currentTimeMillis()
                )
                return@launch
            }
            val chosen = categories.firstOrNull { it.id == keepCategoryId } ?: categories.first()
            val filters = loaded.filters[chosen.id].orEmpty()
            // 只有停留在同一分类时才保留筛选值，并把已不存在的筛选键丢掉。
            val extend = if (chosen.id == keepCategoryId) {
                keepExtend.filterKeys { key -> filters.any { it.key == key } }
            } else emptyMap()
            _home.value = _home.value.copy(
                categories = categories,
                selectedCategoryId = chosen.id,
                filterMap = loaded.filters,
                filters = filters,
                extend = extend
            )
            val (items, error) = if (chosen.id == "" && extend.isEmpty() && loaded.fallbackItems.isNotEmpty()) {
                loaded.fallbackItems to null
            } else {
                fetchCategoryItems(spider, chosen.id, extend, 1)
            }
            val firstPage = items.distinctBy { it.globalKey }
            Log.i(
                "GlassBoxPager",
                "page=1(刷新) category=${chosen.id} extend=$extend raw=${items.size} " +
                    "total=${firstPage.size} error=${error != null}"
            )
            _home.value = _home.value.copy(
                loading = false,
                items = if (error == null) firstPage else emptyList(),
                page = if (error == null) 1 else 0,
                loadingMore = false,
                // 脚本源只有首页条目（fallbackItems）时没有真正的分页，直接判定到底。
                hasMore = error == null && items.isNotEmpty() &&
                    !(chosen.id == "" && extend.isEmpty() && loaded.fallbackItems.isNotEmpty()),
                loadMoreError = null,
                errors = if (error != null) listOf("${displayName(site.name)} · $error") else emptyList(),
                failed = error != null,
                emptyReason = when {
                    error != null -> "加载失败，请检查网络或换一个站点"
                    items.isEmpty() -> "该分类暂无内容"
                    else -> ""
                },
                refreshedAt = System.currentTimeMillis()
            )
        }
    }

    /** 顶部分类栏点选：切换到该分类，清空筛选并重新拉片源。 */
    fun selectCategory(category: SiteCategory) {
        val current = _home.value
        if (current.selectedCategoryId == category.id) return
        _home.value = current.copy(
            selectedCategoryId = category.id,
            filters = current.filterMap[category.id].orEmpty(),
            extend = emptyMap(),
            items = emptyList(),
            loading = true,
            failed = false,
            errors = emptyList(),
            emptyReason = "",
            page = 0,
            loadingMore = false,
            hasMore = true,
            loadMoreError = null
        )
        loadCategoryItems()
    }

    /** 应用当前分类自带的筛选条件。 */
    fun applyFilters(extend: Map<String, String>) {
        val current = _home.value
        if (current.extend == extend) return
        _home.value = current.copy(
            extend = extend,
            items = emptyList(),
            loading = true,
            failed = false,
            errors = emptyList(),
            emptyReason = "",
            page = 0,
            loadingMore = false,
            hasMore = true,
            loadMoreError = null
        )
        loadCategoryItems()
    }

    /** 重置到第 1 页：分类/筛选变化或首次进入分类时调用。 */
    private fun loadCategoryItems() {
        val site = _currentSite.value ?: return
        val snapshot = _home.value
        val categoryId = snapshot.selectedCategoryId ?: return
        categoryJob?.cancel()
        loadMoreJob?.cancel()
        categoryJob = viewModelScope.launch {
            val spider = engine.spider(site, store.sourceOf(site))
            val (items, error) = fetchCategoryItems(spider, categoryId, snapshot.extend, 1)
            val current = _home.value
            // 站点/分类/筛选在请求期间又变了，丢弃这次迟到的结果。
            if (current.siteKey != snapshot.siteKey ||
                current.selectedCategoryId != categoryId ||
                current.extend != snapshot.extend
            ) return@launch
            Log.i(
                "GlassBoxPager",
                "page=1(重置) category=$categoryId extend=${snapshot.extend} raw=${items.size} " +
                    "error=${error != null}"
            )
            _home.value = current.copy(
                loading = false,
                items = if (error == null) items.distinctBy { it.globalKey } else emptyList(),
                page = if (error == null) 1 else 0,
                loadingMore = false,
                hasMore = error == null && items.isNotEmpty(),
                loadMoreError = null,
                failed = error != null,
                errors = if (error != null) listOf("${displayName(site.name)} · $error") else emptyList(),
                emptyReason = when {
                    error != null -> "加载失败，请检查网络或换一个站点"
                    items.isEmpty() -> "该分类暂无内容"
                    else -> ""
                },
                refreshedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * 触底加载下一页（pg = 当前页 + 1，1 基）：
     * - 同一页只会有一个请求在飞（loadingMore 兼作并发闸门）；
     * - 失败保留已有条目，只在底部给出可重试的错误项；
     * - 空页或整页都是重复条目时判定到底，避免无限请求空页；
     * - 分类/筛选在请求期间变化则丢弃迟到结果。
     */
    fun loadMoreCategory() {
        val current = _home.value
        if (current.loading || current.loadingMore || !current.hasMore || current.loadMoreError != null) return
        val site = _currentSite.value ?: return
        val categoryId = current.selectedCategoryId ?: return
        val nextPage = current.page + 1
        if (nextPage <= 1) return
        loadMoreJob?.cancel()
        _home.value = current.copy(loadingMore = true, loadMoreError = null)
        loadMoreJob = viewModelScope.launch {
            val spider = engine.spider(site, store.sourceOf(site))
            val (items, error) = fetchCategoryItems(spider, categoryId, current.extend, nextPage)
            val latest = _home.value
            if (latest.siteKey != current.siteKey ||
                latest.selectedCategoryId != categoryId ||
                latest.extend != current.extend
            ) return@launch
            if (error != null) {
                _home.value = latest.copy(loadingMore = false, loadMoreError = error)
                return@launch
            }
            // 翻页常出现跨页重复：按 globalKey（站点+id）去重且保持原顺序。
            val seen = latest.items.mapTo(HashSet()) { it.globalKey }
            val fresh = items.filter { it.globalKey !in seen }
            _home.value = latest.copy(
                items = (latest.items + fresh).distinctBy { it.globalKey },
                page = nextPage,
                loadingMore = false,
                // 空页或整页都是重复条目 → 到底。
                hasMore = fresh.isNotEmpty(),
                loadMoreError = null,
                refreshedAt = System.currentTimeMillis()
            )
            Log.i(
                "GlassBoxPager",
                "page=$nextPage category=$categoryId extend=${current.extend} " +
                    "raw=${items.size} fresh=${fresh.size} total=${_home.value.items.size} " +
                    "hasMore=${_home.value.hasMore}"
            )
        }
    }

    /** 底部错误项重试：从同一下一页重新请求。 */
    fun retryLoadMore() {
        if (_home.value.loadMoreError != null) {
            _home.value = _home.value.copy(loadMoreError = null)
        }
        loadMoreCategory()
    }

    private suspend fun fetchCategoryItems(
        spider: SiteSpider,
        categoryId: String,
        extend: Map<String, String>,
        page: Int
    ): Pair<List<MediaItem>, String?> {
        var lastError: String? = null
        for (attempt in 0 until 2) {
            try {
                val items = withContext(Dispatchers.IO) {
                    withTimeout(15_000L) { spider.category(categoryId, page, extend) }
                }
                return items to null
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                if (attempt == 0) delay(600)
            }
        }
        return emptyList<MediaItem>() to lastError
    }

    fun search(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            _search.value = SearchUiState()
            return
        }
        searchJob = viewModelScope.launch {
            _search.value = SearchUiState(query = q, loading = true)
            // 当前站优先，其余并行；限制并发与单站超时，结果边到边展示
            val sites = (listOfNotNull(_currentSite.value) + store.activeSites())
                .filter { !it.unsupported }
                .distinctBy { it.key }
                .take(8)
            if (sites.isEmpty()) {
                _search.value = SearchUiState(q, false, DemoContent.search(q).map {
                    SearchHit(it, SiteDef(DemoContent.SITE_KEY, DemoContent.SITE_NAME, "glass://demo", 9))
                })
                return@launch
            }
            val hits = mutableListOf<SearchHit>()
            val errors = mutableListOf<String>()
            fun publish(loading: Boolean) {
                val deduped = hits.distinctBy { it.site.key + "::" + it.item.id }.take(120)
                _search.value = SearchUiState(q, loading, deduped, errors.distinct())
            }
            // 当前站先搜（通常最快出首屏）
            val primary = sites.first()
            val rest = sites.drop(1)
            runCatching {
                withTimeout(6_000) {
                    withContext(Dispatchers.IO) {
                        val source = store.sourceOf(primary)
                        engine.spider(primary, source).search(q)
                    }
                }
            }.onSuccess { list ->
                hits += list.map { SearchHit(it, primary) }
                publish(loading = rest.isNotEmpty())
            }.onFailure {
                errors += "${displayName(primary.name)} · ${it.message ?: "超时/失败"}"
                publish(loading = rest.isNotEmpty())
            }
            if (rest.isNotEmpty()) {
                coroutineScope {
                    rest.map { site ->
                        async(Dispatchers.IO) {
                            runCatching {
                                withTimeout(8_000) {
                                    val source = store.sourceOf(site)
                                    engine.spider(site, source).search(q)
                                }
                            }.fold(
                                onSuccess = { list -> site to list.map { SearchHit(it, site) } },
                                onFailure = { e ->
                                    errors += "${displayName(site.name)} · ${e.message ?: "超时/失败"}"
                                    site to emptyList()
                                }
                            )
                        }
                    }.forEach { deferred ->
                        val (_, list) = deferred.await()
                        if (list.isNotEmpty()) {
                            hits += list
                            publish(loading = true)
                        }
                    }
                }
            }
            publish(loading = false)
            if (hits.isNotEmpty()) store.addSearchHistory(q)
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = SearchUiState()
    }

    fun openDetail(item: MediaItem) {
        viewModelScope.launch {
            _detail.value = DetailUiState(
                item = item,
                loading = true,
                favorite = store.favorites.value.any { it.globalKey == item.globalKey },
                watch = store.watchOf(item)
            )
            val sites = store.sources.value.flatMap { it.sites }
            val site = sites.firstOrNull { it.key == item.siteKey }
                ?: SiteDef(item.siteKey, item.siteName, "glass://demo", 9, sourceId = DemoContent.SOURCE_ID)
            val source = store.sourceOf(site)
            val outcome = runCatching {
                withContext(Dispatchers.IO) { engine.spider(site, source).detail(item.id) }
            }
            var result = outcome.getOrNull()
            // 仅本地 demo 站点回退示例数据，真实站点解析失败要保留原因，便于定位。
            if (result == null && site.api.startsWith("glass://")) {
                result = DemoContent.detail(item.id)
            }
            if (result != null) {
                val filtered = result.copy(
                    groups = AdFilter.filterGroups(result.groups, store.settings.value.adFilterEnabled)
                )
                _detail.value = DetailUiState(
                    item = filtered.item,
                    detail = filtered,
                    loading = false,
                    favorite = store.favorites.value.any { it.globalKey == filtered.item.globalKey },
                    watch = store.watchOf(filtered.item)
                )
            } else {
                val cause = outcome.exceptionOrNull()
                Log.w(
                    "DetailLoad",
                    "openDetail failed site=${site.key} api=${site.api} type=${site.type} id=${item.id} cause=${cause?.javaClass?.simpleName}: ${cause?.message}"
                )
                val message = when {
                    cause != null -> "详情请求失败：${cause.message ?: cause.javaClass.simpleName}"
                    site.api.startsWith("glass://") -> "本地示例里没有该条目的详情"
                    else -> "站点未返回可解析的详情（接口或宏可能已失效）"
                }
                _detail.value = DetailUiState(item, null, false, message, false, store.watchOf(item))
            }
        }
    }

    fun retryDetail() {
        _detail.value.item?.let { openDetail(it) }
    }

    /** 起播前把「播放 id」解析成真实地址，并探测它到底是不是媒体流。 */
    suspend fun preparePlayback(detail: MediaDetail, groupIndex: Int, episodeIndex: Int): PlaybackPrep {
        val group = detail.groups.getOrNull(groupIndex)
            ?: return PlaybackPrep("", emptyMap(), "地址解析失败：剧集分组不存在")
        val episode = group.episodes.getOrNull(episodeIndex)
            ?: return PlaybackPrep("", emptyMap(), "地址解析失败：剧集不存在")
        val site = store.sources.value.flatMap { it.sites }
            .firstOrNull { it.key == detail.item.siteKey }
        val source = site?.let { store.sourceOf(it) }
        val t0 = android.os.SystemClock.elapsedRealtime()
        var url = episode.url
        var headers = siteHeaders(site)
        if (!isMediaLink(url) && site != null && source != null) {
            val resolved = withContext(Dispatchers.IO) {
                runCatching { engine.spider(site, source).resolvePlay(group.title, url) }
            }
            val play = resolved.getOrNull()
            if (play != null && play.url.isNotBlank()) {
                url = play.url
                headers = headers + play.headers
            } else {
                val parsed = tryParseChain(url)
                if (parsed != null) {
                    url = parsed.url
                    headers = headers + parsed.headers
                } else if (!url.startsWith("http")) {
                    return PlaybackPrep(
                        url, headers,
                        "地址解析失败：播放 id 未能解析成 URL（id=$url，站点=${site.name}，${resolved.exceptionOrNull()?.message ?: "脚本未返回播放地址"}）"
                    )
                }
            }
        } else if (!isMediaLink(url)) {
            // 无站点脚本时仍尝试全局解析链
            val parsed = tryParseChain(url)
            if (parsed != null) {
                url = parsed.url
                headers = headers + parsed.headers
            }
        }
        android.util.Log.i(
            "GlassBoxPlayer",
            "起播准备·解析完成 url=$url headers=$headers 耗时=${android.os.SystemClock.elapsedRealtime() - t0}ms " +
                "站点=${site?.name} 源类型=${source?.type?.label}"
        )
        if (store.settings.value.adFilterEnabled && AdFilter.isAdUrl(url)) {
            return PlaybackPrep(url, headers, "已拦截广告地址（可在设置关闭「去广告」）")
        }
        if (url.startsWith("file://") || url.startsWith("content://") || url.startsWith("/")) {
            return PlaybackPrep(url, headers, null, url.contains(".m3u8", ignoreCase = true))
        }
        val probe = MediaProbeRunner.probe(url, headers)
        android.util.Log.i(
            "GlassBoxPlayer",
            "起播准备·探测完成 ok=${probe.ok} isHls=${probe.isHls} type=${probe.contentType} " +
                "耗时=${android.os.SystemClock.elapsedRealtime() - t0}ms reason=${probe.reason}"
        )
        if (!probe.ok) {
            return PlaybackPrep(
                probe.finalUrl, headers,
                "地址解析失败：${probe.reason} · URL=$url · 首字节=${probe.head}"
            )
        }
        return PlaybackPrep(probe.finalUrl, headers, null, probe.isHls)
    }

    private fun isMediaLink(url: String): Boolean {
        val u = url.lowercase().substringBefore('?')
        if (u.startsWith("file://") || u.startsWith("content://") || u.startsWith("/storage/") || u.startsWith("/sdcard/")) {
            return true
        }
        return u.startsWith("http") && listOf(
            ".m3u8", ".mp4", ".flv", ".mkv", ".ts", ".mov", ".webm", ".avi", ".m4v", ".mp3", ".m4a", ".flac", ".aac"
        ).any { u.endsWith(it) }
    }

    private fun siteHeaders(site: SiteDef?): Map<String, String> {
        if (site == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        // py:// 等非 http 协议下 originOf 会返回 "https://null" / "https://py"，不是真实域名，
        // 绝不能把这种无效 Referer 发给 CDN。
        val origin = Net.originOf(site.api)
        val host = origin.removePrefix("https://").removePrefix("http://").substringBefore('/')
        if (origin.startsWith("http") && host.contains(".") && !host.startsWith("null")) {
            map["Referer"] = "$origin/"
        }
        runCatching {
            if (!site.ext.isNullOrBlank() && site.ext.trim().startsWith("{")) {
                val ext = JsonParser.parseString(site.ext).asJsonObject
                val h = ext.getAsJsonObject("headers")
                h?.entrySet()?.forEach { (k, v) -> if (v.isJsonPrimitive) map[k] = v.asString }
            }
        }
        return map
    }

    fun toggleFavorite() {
        val item = _detail.value.item ?: return
        viewModelScope.launch {
            val added = store.toggleFavorite(item)
            _detail.value = _detail.value.copy(favorite = added)
        }
    }

    fun isFavorite(item: MediaItem): Boolean = store.favorites.value.any { it.globalKey == item.globalKey }

    fun preparePlay(detail: MediaDetail, groupIndex: Int, episodeIndex: Int) {
        val group = detail.groups.getOrNull(groupIndex) ?: detail.firstGroup ?: return
        val episode = group.episodes.getOrNull(episodeIndex) ?: return
        val record = store.watchOf(detail.item)
        val resume = if (
            store.settings.value.autoResume && record != null &&
            record.episodeIndex == episodeIndex && record.positionMs > 3000 &&
            record.durationMs - record.positionMs > 5000
        ) record.positionMs else 0L
        _pendingPlay.value = PlayRequest(detail, groupIndex, episodeIndex, resume)
    }

    fun preparePlayRecord(record: WatchRecord, detail: MediaDetail) {
        val groupIndex = detail.groups.indexOfFirst { g -> g.episodes.any { it.name == record.episodeName } }
            .takeIf { it >= 0 } ?: 0
        val episodeIndex = detail.groups.getOrNull(groupIndex)
            ?.episodes?.indexOfFirst { it.name == record.episodeName }?.takeIf { it >= 0 } ?: 0
        _pendingPlay.value = PlayRequest(detail, groupIndex, episodeIndex, record.positionMs)
    }

    fun clearPlay() {
        _pendingPlay.value = null
    }

    /** 播放器退出/切集时调用：不阻塞主线程。 */
    fun saveProgressAsync(play: PlayRequest, positionMs: Long, durationMs: Long) {
        viewModelScope.launch(Dispatchers.IO) { saveProgress(play, positionMs, durationMs) }
    }

    suspend fun saveProgress(play: PlayRequest, positionMs: Long, durationMs: Long) {
        val group = play.detail.groups.getOrNull(play.groupIndex) ?: return
        val episode = group.episodes.getOrNull(play.episodeIndex) ?: return
        if (durationMs <= 0) return
        store.recordWatch(
            WatchRecord(
                key = play.detail.item.globalKey,
                title = play.detail.item.title,
                poster = play.detail.item.poster,
                siteKey = play.detail.item.siteKey,
                siteName = play.detail.item.siteName,
                episodeIndex = play.episodeIndex,
                episodeName = episode.name,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun toggleSource(id: String, enabled: Boolean) {
        viewModelScope.launch {
            val source = store.sources.value.firstOrNull { it.id == id } ?: return@launch
            if (enabled && source.status != SourceStatus.VALID) {
                store.updateSource(id) { it.copy(message = "校验中…") }
                val result = engine.probe(source)
                store.updateSource(id) {
                    it.copy(
                        status = if (result.ok) SourceStatus.VALID else SourceStatus.INVALID,
                        message = result.message,
                        sites = if (result.ok) result.sites.map { s -> s.copy(sourceId = source.id, sourceName = source.name) } else it.sites,
                        siteCount = if (result.ok) result.sites.size else it.sites.size,
                        sample = result.sample,
                        enabled = result.ok
                    )
                }
                if (!result.ok) {
                    _home.value = _home.value.copy(errors = listOf("接口启用失败 · ${result.message}"))
                }
            } else {
                store.setEnabled(id, enabled)
            }
            resolveSelection()
            refreshHome()
        }
    }

    /**
     * 站点级启停：源管理里每个平台一张卡，卡内逐站点开关。
     * 不再通过 [toggleSource] 启停整源，避免「一个源里的坏站点拖死其它平台」。
     */
    fun toggleSite(site: SiteDef, enabled: Boolean) {
        viewModelScope.launch {
            store.setSiteEnabled(site.key, enabled)
            if (enabled && store.sourceOf(site)?.status != SourceStatus.VALID) {
                val source = store.sourceOf(site) ?: return@launch
                val result = engine.probe(source)
                store.updateSource(source.id) {
                    it.copy(
                        status = if (result.ok) SourceStatus.VALID else it.status,
                        sites = if (result.ok) result.sites else it.sites,
                        siteCount = if (result.ok) result.sites.size else it.sites.size,
                        message = result.message
                    )
                }
            }
            resolveSelection()
            refreshHome()
        }
    }

    fun toggleAllSites(sourceId: String, enabled: Boolean) {
        viewModelScope.launch {
            store.setSitesEnabled(sourceId, enabled)
            resolveSelection()
            refreshHome()
        }
    }

    fun deleteSource(id: String) {
        viewModelScope.launch {
            store.delete(id)
            val current = _currentSite.value
            if (current?.sourceId == id) {
                store.saveSettings(store.settings.value.copy(selectedSiteKey = "", selectedSourceId = ""))
                _currentSite.value = null
            }
            resolveSelection()
            refreshHome()
        }
    }

    fun refreshSource(id: String) {
        viewModelScope.launch { refreshSourceNow(id) }
    }

    private suspend fun refreshSourceNow(id: String) {
        val source = store.sources.value.firstOrNull { it.id == id } ?: return
        store.updateSource(id) { it.copy(message = "正在更新…") }
        val raw = if (!source.url.isNullOrBlank()) {
            runCatching { Net.get(source.url!!) }.getOrNull()
        } else source.raw
        if (raw == null) {
            store.updateSource(id) { it.copy(status = SourceStatus.INVALID, message = "在线更新失败，保留本地缓存") }
            return
        }
        val type = when (SourceParser.detect(raw, source.name, source.url.orEmpty())) {
            is DetectResult.Script ->
                (SourceParser.detect(raw, source.name, source.url.orEmpty()) as DetectResult.Script).type
            else -> source.type
        }
        val candidate = source.copy(raw = raw, type = type)
        val result = engine.probe(candidate)
        store.updateSource(id) {
            it.copy(
                raw = raw,
                type = type,
                status = if (result.ok) SourceStatus.VALID else SourceStatus.INVALID,
                message = result.message,
                sites = if (result.ok) result.sites else it.sites,
                siteCount = if (result.ok) result.sites.size else it.sites.size,
                sample = result.sample,
                updatedAt = System.currentTimeMillis()
            )
        }
        resolveSelection()
        refreshHome()
    }

    /**
     * 资源页批量操作：按 id 顺序执行，逐个回调进度（[onProgress] 当前/总数），
     * 结束后统一回调成功/失败数量。删除与更新均为不可并发的大 IO，串行更稳。
     */
    fun batchSources(
        ids: List<String>,
        action: SourceBatchAction,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onDone: (Int, Int) -> Unit = { _, _ -> }
    ) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            ids.forEachIndexed { index, id ->
                onProgress(index + 1, ids.size)
                val success = runCatching {
                    when (action) {
                        SourceBatchAction.ENABLE -> store.setSitesEnabled(id, true)
                        SourceBatchAction.DISABLE -> store.setSitesEnabled(id, false)
                        SourceBatchAction.DELETE -> store.delete(id)
                        SourceBatchAction.REFRESH -> refreshSourceNow(id)
                    }
                }.isSuccess
                if (success) ok++ else failed++
            }
            resolveSelection()
            refreshHome()
            onDone(ok, failed)
        }
    }

    // ---------------- 导入 ----------------

    fun resetImport() {
        _import.value = ImportUiState()
    }

    fun previewFromText(raw: String, hint: String?, origin: SourceOrigin, url: String? = null) {
        viewModelScope.launch {
            _import.value = ImportUiState(loading = true)
            val preview = buildPreview(raw, hint, origin, url)
            _import.value = ImportUiState(loading = false, preview = preview, error = if (!preview.ok && preview.repos.isEmpty()) preview.message else null)
        }
    }

    fun previewFromUrl(url: String) {
        viewModelScope.launch {
            _import.value = ImportUiState(loading = true)
            val text = runCatching { Net.get(url) }.getOrElse {
                _import.value = ImportUiState(error = "网络请求失败：${it.message ?: "无法访问"}")
                return@launch
            }
            val hint = url.substringAfterLast('/').substringBefore('?')
            val preview = buildPreview(text, hint, SourceOrigin.URL, url)
            val repo = if (preview.repos.isNotEmpty()) RepoPick(preview.repos, url) else null
            _import.value = ImportUiState(loading = false, preview = preview, repo = repo, error = if (!preview.ok && repo == null) preview.message else null)
        }
    }

    fun previewFromUri(uri: Uri) {
        viewModelScope.launch {
            _import.value = ImportUiState(loading = true)
            val text = readText(uri)
            if (text == null) {
                _import.value = ImportUiState(error = "无法读取该文件")
                return@launch
            }
            val name = queryDisplayName(uri) ?: uri.lastPathSegment
            // 保存文件 URI：配置里的相对脚本路径要据此解析（单文件授权通常读不到兄弟文件）。
            val preview = buildPreview(text, name, SourceOrigin.FILE, uri.toString())
            _import.value = ImportUiState(loading = false, preview = preview, error = if (!preview.ok && preview.repos.isEmpty()) preview.message else null)
        }
    }

    /**
     * 批量导入本地文件（多选）/ 本地文件夹递归扫描。
     * 每个文件独立成一个源，不合并：同一份配置里多个平台也会按站点分组展示。
     */
    fun importUris(uris: List<Uri>, folder: Boolean = false, onResult: (Int, Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            _import.value = ImportUiState(loading = true)
            var imported = 0
            var failed = 0
            for (uri in uris.take(40)) {
                val text = readText(uri)
                if (text.isNullOrBlank()) {
                    failed++
                    continue
                }
                val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "本地源"
                val preview = buildPreview(text, name, SourceOrigin.FILE, uri.toString())
                if (preview.ok && preview.sites.isNotEmpty() && preview.repos.isEmpty()) {
                    importPreview(preview, preview.name, enable = true)
                    imported++
                } else {
                    failed++
                }
            }
            _import.value = ImportUiState(
                loading = false,
                error = if (imported == 0) "没有识别到可导入的源文件（支持 JSON / JS / PY / TXT）" else null
            )
            if (imported > 0) {
                resolveSelection()
                refreshHome()
            }
            onResult(imported, failed)
        }
    }

    /**
     * 本地文件夹导入优化：
     * - 扫描、读取、解析全程在 IO/Default 线程，主线程只更新进度；
     * - 扫描限深 3 层、最多 40 个候选 / 80 个目录，目录名命中噪声词直接跳过；
     * - 内容哈希去重，且与已导入本地源内容一致时直接跳过（不再逐个发网络探测）；
     * - 读取并发 4、探测并发 3，读到的文件立刻解析并回显进度，不再等整目录扫完；
     * - 每个文件打印「读 / 解析」耗时（logcat `GlassImport`），便于对比优化前后。
     */
    fun importFolder(treeUri: Uri, onResult: (Int, Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            _import.value = ImportUiState(loading = true, progress = "扫描目录…")
            // 目录授权要持久化，否则重启后相对脚本（./py/x.py）读不到。
            runCatching {
                GlassApp.instance.contentResolver.takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val files = withContext(Dispatchers.IO) { collectTreeFiles(treeUri) }
            Log.i("GlassImport", "扫描完成：候选 ${files.size} 个，耗时 ${SystemClock.elapsedRealtime() - t0}ms")
            if (files.isEmpty()) {
                _import.value = ImportUiState(error = "目录里没有可识别的源文件")
                onResult(0, 0)
                return@launch
            }
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
            // 已导入本地源的内容指纹：同样的文件不再重复探测。
            val importedHashes = store.sources.value
                .filter { it.origin == SourceOrigin.FILE }
                .map { sha1(it.raw) }
                .toHashSet()

            var imported = 0
            var failed = 0
            var skipped = 0
            var done = 0
            val total = files.size
            val lock = Any()
            val seen = HashSet<String>()
            val probeLimit = Semaphore(3)
            // chaquopy 的 Python 运行时是单实例，多个脚本并发探测会互相踩状态，PY 串行探测。
            val pyLimit = Semaphore(1)
            coroutineScope {
                files.map { file ->
                    async(Dispatchers.IO) {
                        val uri = file.uri
                        val name = file.name
                        val readStart = SystemClock.elapsedRealtime()
                        val text = readText(uri)
                        val readMs = SystemClock.elapsedRealtime() - readStart
                        if (text.isNullOrBlank() || text.length > 600_000) {
                            synchronized(lock) {
                                failed++
                                done++
                                _import.value = _import.value.copy(progress = "$done/$total · 跳过空/超大的文件")
                            }
                            return@async
                        }
                        val hash = sha1(text)
                        val duplicated = synchronized(lock) { !seen.add(hash) }
                        if (duplicated || hash in importedHashes) {
                            synchronized(lock) {
                                skipped++
                                done++
                                _import.value = _import.value.copy(progress = "$done/$total · $name（已导入）")
                            }
                            Log.i("GlassImport", "$name 跳过：内容重复，读 ${readMs}ms")
                            return@async
                        }
                        // 用「tree:根目录#所在子目录」作为 base，配置里的相对脚本按此解析。
                        val dir = runCatching {
                            DocumentsContract.getDocumentId(uri).removePrefix(rootId).trimStart('/')
                                .substringBeforeLast('/', "")
                        }.getOrDefault("")
                        val base = "tree:$treeUri" + if (dir.isNotBlank()) "#$dir" else ""
                        val parseStart = SystemClock.elapsedRealtime()
                        val preview = if (name.endsWith(".py", ignoreCase = true)) {
                            pyLimit.withPermit { probeLimit.withPermit { buildPreviewBounded(text, name, SourceOrigin.FILE, base) } }
                        } else {
                            probeLimit.withPermit { buildPreviewBounded(text, name, SourceOrigin.FILE, base) }
                        }
                        val parseMs = SystemClock.elapsedRealtime() - parseStart
                        if (preview == null) {
                            Log.i("GlassImport", "$name 读 ${readMs}ms + 探测超时 ${parseMs}ms，已跳过")
                            synchronized(lock) {
                                failed++
                                done++
                                _import.value = _import.value.copy(progress = "$done/$total · $name（超时）")
                            }
                            return@async
                        }
                        Log.i(
                            "GlassImport",
                            "$name 读 ${readMs}ms + 解析/探测 ${parseMs}ms · ok=${preview.ok} · ${preview.message}"
                        )
                        if (preview.ok && preview.sites.isNotEmpty() && preview.repos.isEmpty()) {
                            withContext(Dispatchers.Default) { importPreview(preview, preview.name, enable = true) }
                            synchronized(lock) { imported++ }
                        } else {
                            synchronized(lock) { failed++ }
                        }
                        synchronized(lock) {
                            done++
                            _import.value = _import.value.copy(progress = "$done/$total · $name")
                        }
                    }
                }.awaitAll()
            }
            Log.i(
                "GlassImport",
                "完成：导入 $imported，跳过 $skipped，失败 $failed，总计 ${SystemClock.elapsedRealtime() - t0}ms"
            )
            _import.value = ImportUiState(
                loading = false,
                error = if (imported == 0 && skipped == 0) "该目录下没有可导入的有效源" else null,
                progress = "导入 $imported · 跳过 $skipped · 失败 $failed"
            )
            if (imported > 0) {
                resolveSelection()
                refreshHome()
            }
            onResult(imported, failed)
        }
    }

    /**
     * 目录导入的探测兜底：chaquopy 的原生 Python 调用无法被协程取消打断，
     * withTimeout 只会等它返回后才生效。这里把探测丢到独立线程池，超时后直接放弃该线程并继续导入，
     * 避免一个卡死的脚本让整批文件永远停在「解析中」。
     */
    private val importProbeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun buildPreviewBounded(
        text: String,
        name: String,
        origin: SourceOrigin,
        base: String,
        timeoutMs: Long = 10_000
    ): ImportPreview? {
        val job = importProbeScope.async {
            runCatching { buildPreview(text, name, origin, base) }.getOrNull()
        }
        return withTimeoutOrNull(timeoutMs) { job.await() }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private suspend fun importPreview(preview: ImportPreview, name: String, enable: Boolean) {
        val sites = preview.sites
        if (!preview.ok || sites.isEmpty()) return
        val existing = store.sources.value.firstOrNull { it.id == preview.id }
        val finalName = name.trim().ifBlank { preview.name }
        // 同一个本地文件用「单文件」再改用「目录」导入时，url 从 content:// 变成 tree:，id 会变；
        // 这里按同名同来源删掉旧条目，避免资源页出现两个 本地.json 且旧的读取不到相对脚本。
        store.sources.value
            .filter { it.id != preview.id && it.origin == SourceOrigin.FILE && it.name == finalName }
            .forEach { store.delete(it.id) }
        store.upsert(
            SourceEntity(
                id = preview.id,
                name = finalName,
                type = preview.type,
                origin = preview.origin,
                raw = preview.raw,
                url = preview.url,
                enabled = enable || existing?.enabled == true,
                status = SourceStatus.VALID,
                message = preview.message,
                siteCount = sites.size,
                sample = sites.firstOrNull()?.name,
                importedAt = existing?.importedAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                sites = sites.map { it.copy(sourceId = preview.id, sourceName = finalName) }
            )
        )
    }

    private fun readText(uri: Uri): String? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    private data class TreeFile(val uri: Uri, val name: String)

    /** 递归扫描 SAF 目录，收集可识别的源文件（最多 40 个、3 层、80 个目录，噪声目录直接跳过）。 */
    private fun collectTreeFiles(treeUri: Uri): List<TreeFile> {
        val out = mutableListOf<TreeFile>()
        val root = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return out
        val noise = setOf("Android", "DCIM", "Pictures", "Movies", "Music", "Download", "data", "cache", "node_modules", ".git")
        var visitedDirs = 0
        val dirStart = SystemClock.elapsedRealtime()
        fun walk(docId: String, depth: Int) {
            if (depth > 3 || out.size >= 40 || visitedDirs >= 80) return
            visitedDirs++
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val dirs = mutableListOf<String>()
            runCatching {
                ctx.contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { c ->
                    // 先收当前层的配置文件，再递归子目录：避免 40 个上限被深层脚本占满而漏掉根目录的 本地.json。
                    while (c.moveToNext() && out.size < 40) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: ""
                        val mime = c.getString(2) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (name !in noise && !name.startsWith(".")) dirs += id
                        } else if (name.lowercase().substringAfterLast('.', "") in SOURCE_EXTENSIONS) {
                            out += TreeFile(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name)
                        }
                    }
                }
            }
            dirs.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        Log.i(
            "GlassImport",
            "遍历 ${visitedDirs} 个目录，收集 ${out.size} 个源文件，用时 ${SystemClock.elapsedRealtime() - dirStart}ms"
        )
        return out
    }

    fun loadRepo(repo: RepoEntry, fromUrl: String) {
        viewModelScope.launch {
            _import.value = _import.value.copy(loading = true, error = null)
            val text = runCatching { Net.get(repo.url) }.getOrElse {
                _import.value = _import.value.copy(loading = false, error = "仓库加载失败：${it.message}")
                return@launch
            }
            val preview = buildPreview(text, repo.name, SourceOrigin.URL, repo.url)
            _import.value = ImportUiState(loading = false, preview = preview, error = if (!preview.ok) preview.message else null)
        }
    }

    private suspend fun buildPreview(raw: String, hint: String?, origin: SourceOrigin, url: String? = null): ImportPreview {
        val started = System.currentTimeMillis()
        when (val detected = SourceParser.detect(raw, hint, url.orEmpty())) {
            is DetectResult.RepoIndex -> return ImportPreview(
                id = "repo_" + UUID.randomUUID(),
                name = hint ?: "多仓索引",
                type = SourceType.JSON,
                origin = origin,
                raw = raw,
                url = url,
                ok = false,
                message = "发现多仓索引，请选择一个仓库",
                repos = detected.entries
            )
            is DetectResult.Unsupported -> return ImportPreview(
                id = stableId(SourceType.JSON, raw, url, hint),
                name = hint ?: "未命名源",
                type = SourceType.JSON,
                origin = origin,
                raw = raw,
                url = url,
                ok = false,
                message = detected.reason
            )
            is DetectResult.Script -> {
                val id = stableId(detected.type, raw, url, hint)
                val provisional = SourceEntity(
                    id = id,
                    name = hint ?: "${detected.type.label} 源",
                    type = detected.type,
                    origin = origin,
                    raw = raw,
                    url = url,
                    importedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                val probe = engine.probe(provisional)
                return ImportPreview(
                    id = id,
                    name = if (hint.isNullOrBlank()) (probe.sites.firstOrNull()?.name ?: provisional.name) else provisional.name,
                    type = detected.type,
                    origin = origin,
                    raw = raw,
                    url = url,
                    ok = probe.ok,
                    message = probe.message,
                    sites = probe.sites,
                    elapsedMs = probe.elapsedMs
                )
            }
            is DetectResult.Config -> {
                val id = stableId(SourceType.JSON, raw, url, hint)
                val provisional = SourceEntity(
                    id = id,
                    name = hint ?: detected.name,
                    type = SourceType.JSON,
                    origin = origin,
                    raw = raw,
                    url = url,
                    importedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                val probe = engine.probe(provisional)
                return ImportPreview(
                    id = id,
                    name = detected.name.ifBlank { hint ?: "JSON 源" },
                    type = SourceType.JSON,
                    origin = origin,
                    raw = raw,
                    url = url,
                    ok = probe.ok,
                    message = probe.message,
                    sites = probe.sites,
                    elapsedMs = probe.elapsedMs
                )
            }
        }
    }

    fun confirmImport(preview: ImportPreview, name: String, selectedKeys: Set<String>, enable: Boolean): Boolean {
        val sites = preview.sites.filter { it.key in selectedKeys }
        if (!preview.ok || sites.isEmpty()) return false
        viewModelScope.launch {
            val existing = store.sources.value.firstOrNull { it.id == preview.id }
            val finalName = name.trim().ifBlank { preview.name }
            val entity = SourceEntity(
                id = preview.id,
                name = finalName,
                type = preview.type,
                origin = preview.origin,
                raw = preview.raw,
                url = preview.url,
                enabled = enable || existing?.enabled == true,
                status = SourceStatus.VALID,
                message = preview.message,
                siteCount = sites.size,
                sample = sites.firstOrNull()?.name,
                importedAt = existing?.importedAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                sites = sites.map { it.copy(sourceId = preview.id, sourceName = finalName) }
            )
            store.upsert(entity)
            _import.value = ImportUiState()
            delay(120)
            resolveSelection()
            refreshHome()
        }
        return true
    }

    fun selectRepoEntries(entries: List<RepoEntry>): List<RepoEntry> = entries

    private fun stableId(type: SourceType, raw: String, url: String?, hint: String? = null): String {
        // 目录导入时 url 只是「tree:根#子目录」，同目录多个文件会撞 id；把文件名并入 key。
        val base = if (!url.isNullOrBlank()) "url:$url|${hint.orEmpty()}" else "raw:$raw"
        val digest = MessageDigest.getInstance("SHA-1").digest((type.name + "|" + base).toByteArray())
        return "src_" + digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
    }

    // ---------------- 设置 ----------------

    fun clearImageAndNetCache(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val loader = coil.ImageLoader.Builder(context).build()
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
            runCatching {
                val disk = context.cacheDir
                disk?.listFiles()?.forEach { f ->
                    if (f.name.contains("image", true) || f.name.contains("coil", true)) {
                        f.deleteRecursively()
                    }
                }
            }
            com.glassbox.app.core.Net.clearMemoryCache()
        }
    }

    fun backupAll(context: android.content.Context): String? {
        return runCatching {
            val dir = java.io.File(context.getExternalFilesDir(null), "backup").apply { mkdirs() }
            val file = java.io.File(dir, "glassbox_backup_${System.currentTimeMillis()}.json")
            val payload = mapOf(
                "settings" to store.settings.value,
                "sources" to store.sources.value,
                "exportedAt" to System.currentTimeMillis()
            )
            file.writeText(StoreJson().gson.toJson(payload))
            file.absolutePath
        }.getOrNull()
    }

    fun restoreBackup(context: android.content.Context, uri: android.net.Uri): String {
        return runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@runCatching "无法读取备份"
            val root = com.google.gson.JsonParser.parseString(text).asJsonObject
            val settingsEl = root.get("settings")
            if (settingsEl != null && settingsEl.isJsonObject) {
                val s = StoreJson().gson.fromJson(settingsEl, AppSettings::class.java)
                kotlinx.coroutines.runBlocking { store.saveSettings(s) }
                com.glassbox.app.core.Net.applyHttpProxy(s.proxyEnabled, s.proxyHost, s.proxyPort)
            }
            "备份已恢复（源列表请在资源页确认）"
        }.getOrElse { "恢复失败：${it.message}" }
    }

    fun setParseListJson(json: String) {
        updateSettings { it.copy(parseListJson = json) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            val next = transform(store.settings.value)
            com.glassbox.app.core.Net.applyHttpProxy(next.proxyEnabled, next.proxyHost, next.proxyPort)
            store.saveSettings(next)
        }
    }

    fun clearHistory() {
        viewModelScope.launch { store.clearHistory() }
    }

    fun removeHistory(key: String) {
        viewModelScope.launch { store.removeHistory(key) }
    }

    fun clearSearchHistory() {
        viewModelScope.launch { store.clearSearchHistory() }
    }

    fun testSite(site: SiteDef, callback: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val source = store.sourceOf(site)
            val result = withContext(Dispatchers.IO) { engine.testSite(site, source) }
            callback(result.ok, result.message)
        }
    }

    fun activeSiteCount(): Int = store.activeSites().size

    fun totalSiteCount(): Int = store.sources.value.sumOf { it.sites.size }

    // region P1 融合：直播 / 解析链 / 远程控制 / SQLite 库

    private suspend fun migrateLibraryFromStore() {
        val lib = glassApp.library
        // 若 SQLite 为空而文件库有数据，迁入一次
        if (lib.listHistory().isEmpty() && store.history.value.isNotEmpty()) {
            store.history.value.forEach { lib.upsertHistory(it) }
        }
        if (lib.listFavorites().isEmpty() && store.favorites.value.isNotEmpty()) {
            store.favorites.value.forEach { lib.upsertFavorite(it) }
        }
        // 用 SQLite 覆盖内存 Flow，保证 Library 与持久层一致
        val h = lib.listHistory()
        val f = lib.listFavorites()
        if (h.isNotEmpty()) {
            // 写回 AppStore 文件以保持现有 UI 订阅
            h.forEach { store.recordWatch(it) }
        }
        // favorites 已由 toggle 路径维护；此处不强行全量覆盖以免乱序
    }

    suspend fun loadLiveSource(url: String) {
        if (url.isBlank()) {
            _liveError.value = "请输入直播源地址"
            return
        }
        _liveLoading.value = true
        _liveError.value = null
        try {
            val groups = glassApp.liveParser.parseUrl(url)
            _liveGroups.value = groups
            if (groups.isEmpty()) {
                _liveError.value = "未解析到任何频道，请检查 m3u/txt 格式"
            }
            updateSettings { it.copy(liveSourceUrl = url) }
        } catch (t: Throwable) {
            _liveError.value = t.message ?: "加载失败"
            _liveGroups.value = emptyList()
        } finally {
            _liveLoading.value = false
        }
    }

    private suspend fun tryParseChain(playUrl: String): com.glassbox.app.engine.parse.ParseResult? {
        val json = store.settings.value.parseListJson
        if (json.isBlank() || json == "[]") return null
        val list = runCatching {
            Gson().fromJson<List<ParseDef>>(json, object : TypeToken<List<ParseDef>>() {}.type)
        }.getOrNull().orEmpty().filter { it.enabled && it.url.isNotBlank() }
        if (list.isEmpty()) return null
        return glassApp.parseExecutor.parseFirst(list, playUrl)
    }

    fun applyRemoteControl(settings: AppSettings) {
        if (settings.remoteControlEnabled) {
            runCatching {
                glassApp.startRemoteControl(
                    port = settings.remoteControlPort,
                    onAction = { action, extras ->
                        Log.i("RemoteControl", "action=$action extras=$extras")
                        _remoteCommand.value = RemoteCommand(action = action, extras = extras)
                    },
                    statusProvider = { playbackStatusJson }
                )
            }.onFailure {
                Log.e("RemoteControl", "start failed", it)
            }
        } else {
            glassApp.stopRemoteControl()
        }
    }

    fun setRemoteControlEnabled(enabled: Boolean) {
        updateSettings { it.copy(remoteControlEnabled = enabled) }
        applyRemoteControl(store.settings.value.copy(remoteControlEnabled = enabled))
    }

    fun consumeRemoteCommand(cmd: RemoteCommand) {
        if (_remoteCommand.value?.seq == cmd.seq) {
            _remoteCommand.value = null
        }
    }

    fun updatePlaybackStatus(
        playing: Boolean,
        positionMs: Long = 0,
        durationMs: Long = 0,
        title: String = "",
        episode: String = ""
    ) {
        val safeTitle = title.replace("\"", "'")
        val safeEp = episode.replace("\"", "'")
        playbackStatusJson =
            "{\"ok\":true,\"playing\":$playing,\"positionMs\":$positionMs,\"durationMs\":$durationMs," +
            "\"title\":\"$safeTitle\",\"episode\":\"$safeEp\",\"page\":\"player\"}"
    }


    fun discoverDlna() {
        viewModelScope.launch {
            _dlnaBusy.value = true
            _dlnaMessage.value = "正在搜索 DLNA 设备…"
            try {
                val list = glassApp.dlna.discover()
                _dlnaDevices.value = list
                _dlnaMessage.value = if (list.isEmpty()) "未发现设备（需同一 Wi‑Fi，电视已开投屏）" else "发现 ${list.size} 台设备"
            } catch (t: Throwable) {
                _dlnaMessage.value = t.message ?: "搜索失败"
            } finally {
                _dlnaBusy.value = false
            }
        }
    }

    fun castToDlna(device: DlnaDevice, url: String, title: String) {
        viewModelScope.launch {
            _dlnaBusy.value = true
            _dlnaMessage.value = "正在投屏到 ${device.name}…"
            try {
                val ok = glassApp.dlna.cast(device, url, title)
                _dlnaMessage.value = if (ok) "已投屏：${device.name}" else "投屏失败（设备拒绝或地址不可达）"
            } catch (t: Throwable) {
                _dlnaMessage.value = t.message ?: "投屏失败"
            } finally {
                _dlnaBusy.value = false
            }
        }
    }

    fun dlnaPause(device: DlnaDevice) {
        viewModelScope.launch { runCatching { glassApp.dlna.pause(device) } }
    }

    fun dlnaStop(device: DlnaDevice) {
        viewModelScope.launch { runCatching { glassApp.dlna.stop(device) } }
    }

    fun loadEpg(url: String = store.settings.value.epgUrl, force: Boolean = false) {
        viewModelScope.launch {
            if (url.isBlank()) {
                _epgDays.value = emptyList()
                return@launch
            }
            _epgLoading.value = true
            try {
                val ok = glassApp.epg.load(url, force)
                if (!ok) {
                    _epgDays.value = emptyList()
                }
                updateSettings { it.copy(epgUrl = url) }
            } finally {
                _epgLoading.value = false
            }
        }
    }

    fun refreshEpgForChannel(epgId: String?, channelName: String?) {
        viewModelScope.launch {
            val url = store.settings.value.epgUrl
            if (url.isNotBlank()) {
                runCatching { glassApp.epg.load(url, force = false) }
            }
            _epgDays.value = glassApp.epg.daysForChannel(epgId, channelName)
            _epgNowNext.value = glassApp.epg.nowNext(epgId, channelName)
        }
    }

    // endregion

}
