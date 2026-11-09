package com.glassbox.app.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassbox.app.core.displayName
import com.glassbox.app.data.FilterGroup
import com.glassbox.app.data.MediaItem
import com.glassbox.app.data.SiteCategory
import com.glassbox.app.ui.MainViewModel
import com.glassbox.app.ui.components.EmptyState
import com.glassbox.app.ui.components.ErrorNotice
import com.glassbox.app.ui.components.GlassBottomSheet
import com.glassbox.app.ui.components.GlassChip
import com.glassbox.app.ui.components.GridPosterCard
import com.glassbox.app.ui.components.OriginalSheet
import com.glassbox.app.ui.components.OriginalSheetAction
import com.glassbox.app.ui.components.OriginalSheetRow
import com.glassbox.app.ui.components.PrimaryCapsule
import com.glassbox.app.ui.components.SheetHeader
import com.glassbox.app.ui.components.liquidGlass
import com.glassbox.app.ui.theme.GlassTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

/**
 * 首页（按 BieCuiLe 1.1.5 的按钮外观 + 本轮五条要求）：
 * - 顶部：左＝接口名胶囊（188×42，纯文字），右＝站点名胶囊（163×40，纯文字），不含任何图标或角标。
 * - 分类栏：接口/站点按钮下方单行横滑，数据来自站点自身分类，选中项玫红胶囊高亮。
 * - 内容：三列网格，2:3 封面 + 海报下方标题（最多三行）。
 * - 筛选：搜索按钮正上方的白色圆钮，筛选项来自当前分类自带的 filters。
 */
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onSearch: () -> Unit,
    onSources: () -> Unit,
    onDetail: (MediaItem) -> Unit,
    bottomInset: Dp,
    hazeState: HazeState? = null,
    filterSheetOpen: Boolean = false,
    onFilterSheetClose: () -> Unit = {}
) {
    val palette = GlassTheme.colors
    val home by vm.home.collectAsState()
    val currentSite by vm.currentSite.collectAsState()
    val sources by vm.sources.collectAsState()

    var showSiteSheet by remember { mutableStateOf(false) }
    var showInterfaceDialog by remember { mutableStateOf(false) }

    val currentSource = currentSite?.let { site -> sources.firstOrNull { it.id == site.sourceId } }
    // 展示名统一去掉源类型后缀；内部 key / 文件名 / 类型判定仍用原名。
    val interfaceLabel = displayName(
        (currentSource?.name ?: currentSite?.sourceName ?: "未选择接口").ifBlank { "未选择接口" }
    )
    val siteLabel = displayName(currentSite?.name ?: home.siteName.ifBlank { "未选择站点" })
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    // 胶囊最短约 5 个中文字宽（13sp 实测），再加左右内边距。
    val pillMinWidth = remember(textMeasurer, density) {
        val measured = textMeasurer.measure(
            AnnotatedString("中中中中中"),
            TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
        )
        with(density) { measured.size.width.toDp() } + 26.dp
    }
    // 悬浮玻璃栏自身高度（不含状态栏）：给内容留出顶部安全区，保证第一行完整可见。
    var glassBarHeightPx by remember { mutableStateOf(0) }
    val glassBarHeight = if (glassBarHeightPx > 0) with(density) { glassBarHeightPx.toDp() } else 116.dp
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val contentTopPadding = glassBarHeight + statusBarTop

    // 从详情返回时 Navigation 会恢复 rememberSaveable，这里的 key/滚动位置一并还原：
    // 只有「分类或筛选真的变了」才回第 1 页并重新请求，正常返回只做恢复。
    var scrollKey by rememberSaveable { mutableStateOf("") }
    var userScrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(home.selectedCategoryId, home.extend) {
        val key = "${home.selectedCategoryId}|${home.extend}"
        if (key != scrollKey) {
            val restored = scrollKey.isNotEmpty()
            scrollKey = key
            userScrolled = false
            gridState.scrollToItem(0)
            Log.i(
                "GlassHome",
                if (restored) "分类/筛选变化：重置到第 1 页并重新请求 key=$key"
                else "首次进入：滚动到顶部 key=$key"
            )
        } else {
            // 返回恢复：清掉滑动标记，避免停在底部时自动再拉一页。
            userScrolled = false
            Log.i(
                "GlassHome",
                "正常恢复：不重新请求 key=$key page=${home.page} items=${home.items.size}"
            )
        }
    }

    // 触底分页：用户真正滑动后才允许触发，避免返回时停在底部又自动拉下一页。
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.collect { if (it) userScrolled = true }
    }
    val pagerState by rememberUpdatedState(home)
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val s = pagerState
            userScrolled && info.totalItemsCount > 0 && last >= info.totalItemsCount - 4 &&
                s.items.isNotEmpty() && !s.loading && !s.loadingMore &&
                s.hasMore && s.loadMoreError == null
        }.collect { near -> if (near) vm.loadMoreCategory() }
    }

    Box(Modifier.fillMaxSize()) {
        // ---------- 内容层：铺满整屏，可滚到顶部玻璃栏下方 ----------
        Box(
            Modifier
                .fillMaxSize()
                .then(if (hazeState != null) Modifier.hazeSource(hazeState) else Modifier)
                .background(palette.bgTop)
        ) {
            when {
                home.loading && home.items.isEmpty() -> HomeLoading(bottomInset, contentTopPadding)
                home.failed -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = contentTopPadding + 20.dp,
                            bottom = bottomInset
                        )
                ) {
                    ErrorNotice(
                        message = home.errors.firstOrNull() ?: "请检查网络",
                        title = "加载失败",
                        onRetry = { vm.refreshHome() }
                    )
                }
                home.items.isEmpty() -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = contentTopPadding, bottom = bottomInset)
                ) {
                    EmptyState(
                        title = if (home.categories.isEmpty()) "这里还没有内容" else "该分类暂无内容",
                        message = when {
                            home.categories.isEmpty() ->
                                home.emptyReason.ifBlank { "当前站点没有返回分类，可以换一个站点或稍后刷新。" }
                            home.extend.isNotEmpty() -> "当前筛选条件下没有条目，可以重置筛选或换一个分类。"
                            else -> "这个分类下没有条目，可以换一个分类或刷新。"
                        },
                        icon = Icons.Outlined.CloudOff,
                        action = {
                            PrimaryCapsule(
                                text = if (home.extend.isNotEmpty()) "重置筛选" else "刷新",
                                minHeight = 44.dp,
                                onClick = {
                                    if (home.extend.isNotEmpty()) vm.applyFilters(emptyMap())
                                    else vm.refreshHome()
                                }
                            )
                        }
                    )
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        // 静止时第一行完整落在玻璃栏下方；滚动时海报会钻到玻璃栏背后。
                        top = contentTopPadding + 8.dp,
                        // 底部导航 + 搜索圆钮 + 其正上方筛选圆钮的总高度，避免最后一行被悬浮按钮遮住。
                        bottom = bottomInset + 80.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    gridItems(home.items, key = { it.globalKey }) { item ->
                        GridPosterCard(
                            item = item,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onDetail(item) }
                        )
                    }
                    // 底部状态项：加载中 / 可重试错误 / 没有更多了。
                    item(span = { GridItemSpan(maxLineSpan) }, key = "pager-footer") {
                        PagerFooter(
                            loading = home.loadingMore,
                            error = home.loadMoreError,
                            hasMore = home.hasMore,
                            onRetry = { vm.retryLoadMore() }
                        )
                    }
                }
            }
        }

        // ---------- 顶部悬浮玻璃层：接口/站点胶囊 + 分类栏 ----------
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { glassBarHeightPx = it.height }
            ) {
                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp)
                ) {
                    // 每颗胶囊最宽不超过半屏；右胶囊右侧留 16dp 后仍不越过屏幕中线。
                    val pillMaxWidth = (maxWidth / 2 - 24.dp).coerceAtLeast(pillMinWidth)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HeaderPill(
                            text = interfaceLabel,
                            minWidth = pillMinWidth,
                            maxWidth = pillMaxWidth,
                            height = 42.dp,
                            hazeState = hazeState,
                            onClick = { showInterfaceDialog = true }
                        )
                        Spacer(Modifier.weight(1f))
                        HeaderPill(
                            text = siteLabel,
                            minWidth = pillMinWidth,
                            maxWidth = pillMaxWidth,
                            height = 40.dp,
                            hazeState = hazeState,
                            onClick = { showSiteSheet = true }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                CategoryBar(
                    categories = home.categories,
                    selectedId = home.selectedCategoryId,
                    loading = home.loading,
                    hazeState = hazeState,
                    onClick = vm::selectCategory
                )

                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // ---------- 原版「切换平台」抽屉：仅当前接口下的站点，两列平铺 ----------
    var siteQuery by remember { mutableStateOf("") }
    var siteSearchOpen by remember { mutableStateOf(false) }
    var siteSorted by remember { mutableStateOf(false) }
    val currentSourceId = currentSite?.sourceId
    val siteRows = remember(sources, currentSourceId, siteSorted, siteQuery) {
        val rows = sources
            .filter { it.id == currentSourceId }
            .flatMap { source ->
                source.sites.filter { !it.unsupported }.map { source to it }
            }
        val ordered = if (siteSorted) rows.sortedBy { it.second.name.lowercase() } else rows
        if (siteQuery.isBlank()) ordered
        else ordered.filter { it.second.name.contains(siteQuery, ignoreCase = true) }
    }
    val siteListState = rememberLazyGridState()
    val sheetScope = rememberCoroutineScope()
    fun scrollToSite() {
        val index = siteRows.indexOfFirst { it.second.key == currentSite?.key }
        if (index >= 0) {
            sheetScope.launch {
                siteListState.animateScrollToItem((index - 1).coerceAtLeast(0))
            }
        }
    }
    LaunchedEffect(showSiteSheet) {
        if (showSiteSheet) scrollToSite()
    }
    OriginalSheet(
        visible = showSiteSheet,
        title = "切换平台",
        onDismiss = { showSiteSheet = false },
        // 列表内滑动只滚列表，不会连带把面板拖下去；只有顶部把手和遮罩能关闭。
        handleOnlyDismiss = true,
        actions = listOf(
            OriginalSheetAction(Icons.Outlined.SwapVert, "刷新", { vm.refreshHome(true) }),
            OriginalSheetAction(Icons.Outlined.MyLocation, "定位当前", { scrollToSite() }),
            OriginalSheetAction(Icons.Outlined.FormatListBulleted, "排序", { siteSorted = !siteSorted }),
            OriginalSheetAction(Icons.Outlined.Search, "搜索", {
                siteSearchOpen = !siteSearchOpen
                if (!siteSearchOpen) siteQuery = ""
            })
        )
    ) {
        if (siteSearchOpen) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(GlassTheme.shapes.pill)
                    .background(GlassTheme.colors.chipBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (siteQuery.isEmpty()) {
                    Text("搜索站点", color = GlassTheme.colors.textTertiary, fontSize = 13.sp)
                }
                BasicTextField(
                    value = siteQuery,
                    onValueChange = { siteQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(color = GlassTheme.colors.textPrimary, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = siteListState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            gridItems(siteRows, key = { it.second.key }) { (source, site) ->
                OriginalSheetRow(
                    title = displayName(site.name),
                    subtitle = if (site.isEnabled) site.kindLabel else "已停用",
                    logoColor = accentFor(site.name),
                    selected = site.key == currentSite?.key,
                    enabled = site.isEnabled,
                    compact = true,
                    onClick = {
                        vm.selectSite(site.copy(sourceId = source.id, sourceName = source.name))
                        showSiteSheet = false
                    }
                )
            }
        }
    }

    // ---------- 原版「切换资源」抽屉：已导入接口列表 ----------
    var resourceQuery by remember { mutableStateOf("") }
    var resourceSearchOpen by remember { mutableStateOf(false) }
    var resourceSorted by remember { mutableStateOf(false) }
    val resourceRows = remember(sources, resourceSorted, resourceQuery) {
        val ordered = if (resourceSorted) sources.sortedBy { it.name.lowercase() } else sources
        if (resourceQuery.isBlank()) ordered
        else ordered.filter { it.name.contains(resourceQuery, ignoreCase = true) }
    }
    val interfaceListState = rememberLazyListState()
    LaunchedEffect(showInterfaceDialog) {
        if (!showInterfaceDialog) return@LaunchedEffect
        val index = sources.indexOfFirst { it.id == currentSite?.sourceId }
        if (index >= 0) interfaceListState.scrollToItem((index - 1).coerceAtLeast(0))
    }
    OriginalSheet(
        visible = showInterfaceDialog,
        title = "切换资源",
        onDismiss = { showInterfaceDialog = false },
        actions = listOf(
            OriginalSheetAction(Icons.Outlined.SwapVert, "刷新", { vm.refreshHome(true) }),
            OriginalSheetAction(Icons.Outlined.MyLocation, "定位当前", {
                sheetScope.launch {
                    val index = sources.indexOfFirst { it.id == currentSite?.sourceId }
                    if (index >= 0) interfaceListState.animateScrollToItem((index - 1).coerceAtLeast(0))
                }
            }),
            OriginalSheetAction(Icons.Outlined.FormatListBulleted, "排序", { resourceSorted = !resourceSorted }),
            OriginalSheetAction(Icons.Outlined.Search, "搜索", {
                resourceSearchOpen = !resourceSearchOpen
                if (!resourceSearchOpen) resourceQuery = ""
            })
        )
    ) {
        if (resourceSearchOpen) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(GlassTheme.shapes.pill)
                    .background(GlassTheme.colors.chipBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (resourceQuery.isEmpty()) {
                    Text("搜索资源", color = GlassTheme.colors.textTertiary, fontSize = 13.sp)
                }
                BasicTextField(
                    value = resourceQuery,
                    onValueChange = { resourceQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(color = GlassTheme.colors.textPrimary, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        LazyColumn(
            state = interfaceListState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(resourceRows, key = { it.id }) { source ->
                val usable = source.sites.count { it.isEnabled && !it.unsupported }
                val isCurrent = source.id == currentSite?.sourceId
                OriginalSheetRow(
                    title = displayName(source.name),
                    subtitle = "$usable 个站点 · ${source.status.label}",
                    logoColor = accentFor(source.name),
                    selected = isCurrent,
                    enabled = usable > 0,
                    onClick = {
                        vm.selectSource(source)
                        showInterfaceDialog = false
                    }
                )
            }
        }
    }

    // ---------- 筛选面板：筛选项完全来自当前分类的 filters ----------
    GlassBottomSheet(
        visible = filterSheetOpen,
        onDismiss = onFilterSheetClose,
        maxHeight = GlassTheme.dimens.importSheetMaxHeight
    ) {
        FilterSheetContent(
            category = home.selectedCategory,
            groups = home.filters,
            current = home.extend,
            onApply = {
                vm.applyFilters(it)
                onFilterSheetClose()
            },
            onClose = onFilterSheetClose
        )
    }
}

/**
 * 顶部纯文字胶囊：宽度随名称长度自适应（最短约 5 个中文字，最长不超过半屏），
 * 文字左对齐；超出部分单行省略。
 */
@Composable
private fun HeaderPill(
    text: String,
    minWidth: Dp,
    maxWidth: Dp,
    height: Dp,
    hazeState: HazeState?,
    onClick: () -> Unit
) {
    val palette = GlassTheme.colors
    Box(
        Modifier
            .widthIn(min = minWidth, max = maxWidth)
            .height(height)
            .liquidGlass(
                shape = GlassTheme.shapes.pill,
                hazeState = hazeState,
                blur = 18.dp,
                elevation = 10.dp
            )
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text,
            color = palette.textPrimary,
            fontSize = 13.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start
        )
    }
}

/**
 * 分类栏：去掉整条大胶囊底板，每个分类各自一颗 32dp 小胶囊（未选玻璃白、选中玫红实底），
 * 单行横滑，左右 16dp。
 */
@Composable
private fun CategoryBar(
    categories: List<SiteCategory>,
    selectedId: String?,
    loading: Boolean,
    hazeState: HazeState?,
    onClick: (SiteCategory) -> Unit
) {
    val palette = GlassTheme.colors
    if (categories.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                if (loading) "正在加载分类…" else "该站点没有返回分类",
                color = palette.textSecondary,
                fontSize = 12.sp
            )
        }
        return
    }
    LazyRow(
        Modifier
            .fillMaxWidth()
            .height(32.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(categories, key = { it.id }) { category ->
            val selected = category.id == selectedId
            val base = Modifier
                .height(32.dp)
                .clip(GlassTheme.shapes.pill)
            if (selected) {
                Box(
                    base
                        .background(palette.accent)
                        .clickable { onClick(category) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        category.name,
                        color = palette.onAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            } else {
                Box(
                    base
                        .liquidGlass(
                            shape = GlassTheme.shapes.pill,
                            hazeState = hazeState,
                            blur = 16.dp,
                            tint = palette.glassTint.copy(alpha = 0.72f),
                            elevation = 6.dp
                        )
                        .clickable { onClick(category) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        category.name,
                        color = palette.textPrimary,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSheetContent(
    category: SiteCategory?,
    groups: List<FilterGroup>,
    current: Map<String, String>,
    onApply: (Map<String, String>) -> Unit,
    onClose: () -> Unit
) {
    val palette = GlassTheme.colors
    var pending by remember(category?.id, current) { mutableStateOf(current) }

    SheetHeader(
        "筛选",
        "当前分类 · ${category?.name ?: "未选择"}",
        trailing = {
            Text(
                "重置",
                color = if (pending.isNotEmpty()) palette.accent else palette.textTertiary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(GlassTheme.shapes.pill)
                    .clickable { pending = emptyMap() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    )

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        if (groups.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "当前分类没有提供筛选条件。",
                color = palette.textSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            PrimaryCapsule(
                text = "关闭",
                modifier = Modifier.fillMaxWidth(),
                minHeight = 44.dp,
                onClick = onClose
            )
            return@Column
        }

        groups.forEach { group ->
            Spacer(Modifier.height(12.dp))
            Text(
                group.name,
                color = palette.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                group.options.forEach { option ->
                    val active = if (option.value.isBlank()) {
                        pending[group.key].isNullOrBlank()
                    } else {
                        pending[group.key] == option.value
                    }
                    GlassChip(
                        text = option.name,
                        selected = active,
                        onClick = {
                            pending = pending.toMutableMap().apply {
                                if (option.value.isBlank()) remove(group.key)
                                else put(group.key, option.value)
                            }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(GlassTheme.shapes.pill)
                    .background(palette.chipBg)
                    .clickable { pending = emptyMap() },
                contentAlignment = Alignment.Center
            ) {
                Text("重置", color = palette.textPrimary, fontSize = 14.sp)
            }
            PrimaryCapsule(
                text = "应用筛选",
                modifier = Modifier.weight(1.4f),
                minHeight = 46.dp,
                onClick = { onApply(pending) }
            )
        }
    }
}

@Composable
private fun PagerFooter(
    loading: Boolean,
    error: String?,
    hasMore: Boolean,
    onRetry: () -> Unit
) {
    val palette = GlassTheme.colors
    // 正常可继续滚动时留空，不额外占高度。
    if (!loading && error == null && hasMore) return
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    color = palette.textSecondary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp)
                )
                Text("加载中…", color = palette.textSecondary, fontSize = 13.sp)
            }
            error != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "加载失败：$error",
                    color = palette.danger,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                PrimaryCapsule(text = "重试", minHeight = 36.dp, onClick = onRetry)
            }
            else -> Text("没有更多了", color = palette.textTertiary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun HomeLoading(bottomInset: Dp, contentTopPadding: Dp) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = contentTopPadding, bottom = bottomInset),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = GlassTheme.colors.accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("正在加载片源…", color = GlassTheme.colors.textSecondary, fontSize = 13.sp)
    }
}

private fun accentFor(seed: String): Color {
    val colors = listOf(
        Color(0xFF5856D6),
        Color(0xFFFF9500),
        Color(0xFF34C759),
        Color(0xFF007AFF),
        Color(0xFFFF2D55),
        Color(0xFFAF52DE)
    )
    if (seed.isEmpty()) return colors.first()
    return colors[(kotlin.math.abs(seed.hashCode())) % colors.size]
}

private fun sourceColor(name: String): Color = accentFor(name)
