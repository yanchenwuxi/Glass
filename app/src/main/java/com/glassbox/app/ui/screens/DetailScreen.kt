package com.glassbox.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassbox.app.data.MediaDetail
import com.glassbox.app.data.MediaItem
import com.glassbox.app.data.playableFirst
import com.glassbox.app.ui.MainViewModel
import com.glassbox.app.ui.components.*
import com.glassbox.app.ui.theme.GlassTheme

/**
 * 详情（对齐 BieCuiLe 1.1.5，`orig_shots/24_detail2.png`）：
 * 出血头图 + 居中标題/元信息 → 简介 → 浅灰全宽主按钮 → 5 个无文字圆形快捷操作 →
 * 选集头（线路胶囊 + 刷新 + 正序 + 定位）→ 分页 + 4 列剧集格 → 推荐横滑。
 */
@Composable
fun DetailScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onOpenDetail: (MediaItem) -> Unit
) {
    val palette = GlassTheme.colors
    val context = LocalContext.current
    val state by vm.detail.collectAsState()
    val detail = state.detail
    val item = state.item

    var selectedGroup by remember(detail?.item?.globalKey) { mutableIntStateOf(0) }
    var selectedEpisode by remember(detail?.item?.globalKey) { mutableIntStateOf(0) }
    var page by remember(detail?.item?.globalKey) { mutableIntStateOf(0) }
    var descExpanded by remember { mutableStateOf(false) }
    var reversed by remember { mutableStateOf(false) }
    var showLineMenu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(palette.bgTop)) {
        when {
            state.loading && detail == null -> LoadingState(text = "正在加载详情…")
            state.error != null && detail == null -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                Spacer(Modifier.statusBarsPadding())
                BackRow(onBack)
                Spacer(Modifier.height(24.dp))
                ErrorNotice(message = state.error ?: "", onRetry = { vm.retryDetail() })
            }
            item == null -> EmptyState(title = "没有内容", message = "该条目缺少详情信息。", icon = Icons.Outlined.CloudOff)
            else -> {
                val groups = remember(detail?.groups) { detail?.groups?.playableFirst() ?: emptyList() }
                val currentGroup = groups.getOrNull(selectedGroup.coerceAtLeast(0))
                val episodesRaw = currentGroup?.episodes ?: emptyList()
                val episodes = remember(episodesRaw, reversed) { if (reversed) episodesRaw.reversed() else episodesRaw }
                val pageSize = 20
                val pages = (episodes.size + pageSize - 1) / pageSize
                val watch = state.watch

                // 默认选中：观看记录里的同一集；没有记录则第 1 集（原版「继续 第XX集」行为）。
                LaunchedEffect(groups, watch) {
                    val record = watch ?: return@LaunchedEffect
                    val groupIndex = groups.indexOfFirst { group ->
                        group.episodes.any { it.name == record.episodeName }
                    }
                    if (groupIndex >= 0) {
                        selectedGroup = groupIndex
                        val episodeIndex = groups[groupIndex].episodes
                            .indexOfFirst { it.name == record.episodeName }
                        selectedEpisode = episodeIndex.coerceAtLeast(0)
                        page = (selectedEpisode / pageSize).coerceAtLeast(0)
                    }
                }

                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp)
                ) {
                    DetailHead(
                        poster = (detail?.item?.backdrop ?: item.backdrop).ifBlank { item.poster },
                        onBack = onBack
                    )
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            if (detail != null) detail.item.title else item.title,
                            color = palette.textPrimary,
                            fontSize = 32.sp,
                            lineHeight = 38.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        val meta = listOf(
                            item.remarks,
                            item.year,
                            item.area,
                            item.typeName
                        ).filter { it.isNotBlank() }.joinToString(" • ")
                        if (meta.isNotBlank()) {
                            Text(
                                meta,
                                color = palette.textPrimary.copy(alpha = 0.72f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (item.score.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .clip(GlassTheme.shapes.pill)
                                        .background(Color(0xFF2E8B57).copy(alpha = 0.16f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        "豆瓣 ${item.score}",
                                        color = Color(0xFF2E8B57),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        val desc = detail?.description.orEmpty()
                        if (desc.isNotBlank()) {
                            Text(
                                desc,
                                color = palette.textSecondary,
                                fontSize = 13.sp,
                                lineHeight = 21.sp,
                                maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { descExpanded = !descExpanded }
                            )
                            if (desc.length > 60) {
                                Text(
                                    if (descExpanded) "收起" else "展开",
                                    color = palette.textTertiary,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .clickable { descExpanded = !descExpanded }
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        DetailPlayButton(
                            text = playButtonText(watch?.episodeName),
                            enabled = episodes.isNotEmpty(),
                            onClick = {
                                val d = detail
                                if (d != null) {
                                    vm.preparePlay(d, selectedGroup, selectedEpisode)
                                    onPlay()
                                }
                            }
                        )
                        Spacer(Modifier.height(16.dp))
                        QuickActions(
                            favorite = state.favorite,
                            onSwitchPlatform = {
                                showToast(context, "换平台：已按平台分组，可在资源管理中启用其它站点")
                            },
                            onDownload = { showToast(context, "下载：本地模拟占位，未接入下载服务") },
                            onFavorite = { vm.toggleFavorite() },
                            onFollow = { showToast(context, "追更：本地模拟占位") },
                            onComment = { showToast(context, "评论：本地模拟占位") }
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("选集", color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Icon(
                                Icons.Outlined.KeyboardArrowDown,
                                "线路",
                                tint = palette.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.weight(1f))
                            if (groups.isNotEmpty()) {
                                Box {
                                    Box(
                                        Modifier
                                            .height(30.dp)
                                            .clip(GlassTheme.shapes.pill)
                                            .background(palette.chipBg)
                                            .clickable { showLineMenu = true }
                                            .padding(horizontal = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                Modifier
                                                    .size(5.dp)
                                                    .clip(CircleShape)
                                                    .background(palette.accent)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                currentGroup?.title?.ifBlank { "线路 ${selectedGroup + 1}" }
                                                    ?: "线路 ${selectedGroup + 1}",
                                                color = palette.textPrimary,
                                                fontSize = 12.sp,
                                                maxLines = 1
                                            )
                                            Spacer(Modifier.width(2.dp))
                                            Icon(
                                                Icons.Outlined.KeyboardArrowDown,
                                                "展开线路",
                                                tint = palette.textSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = showLineMenu,
                                        onDismissRequest = { showLineMenu = false },
                                        containerColor = palette.surface
                                    ) {
                                        groups.forEachIndexed { index, group ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        group.title.ifBlank { "线路 ${index + 1}" },
                                                        color = if (index == selectedGroup) palette.accent else palette.textPrimary,
                                                        fontSize = 13.sp
                                                    )
                                                },
                                                onClick = {
                                                    showLineMenu = false
                                                    val previousName = episodes.getOrNull(selectedEpisode)?.name
                                                    selectedGroup = index
                                                    val newEpisodes = groups[index].episodes
                                                    val newIndex = newEpisodes.indexOfFirst { it.name == previousName }
                                                    selectedEpisode = newIndex.coerceAtLeast(0)
                                                    page = (selectedEpisode / pageSize).coerceAtLeast(0)
                                                }
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                GlassIconButton(
                                    icon = Icons.Outlined.Refresh,
                                    contentDescription = "刷新",
                                    size = 30.dp,
                                    tint = palette.textSecondary,
                                    bg = palette.chipBg,
                                    onClick = { vm.retryDetail() }
                                )
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier
                                        .height(30.dp)
                                        .clip(GlassTheme.shapes.pill)
                                        .background(palette.chipBg)
                                        .clickable { reversed = !reversed }
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Outlined.SwapVert,
                                            "排序",
                                            tint = palette.textSecondary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            if (reversed) "倒序" else "正序",
                                            color = palette.textSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                GlassIconButton(
                                    icon = Icons.Outlined.MyLocation,
                                    contentDescription = "定位当前集",
                                    size = 30.dp,
                                    tint = palette.textSecondary,
                                    bg = palette.chipBg,
                                    onClick = {
                                        page = (selectedEpisode / pageSize).coerceAtLeast(0)
                                    }
                                )
                            }
                        }
                        if (state.error != null && detail != null) {
                            Spacer(Modifier.height(10.dp))
                            ErrorNotice(message = state.error ?: "", onRetry = { vm.retryDetail() })
                        }
                        Spacer(Modifier.height(10.dp))
                        if (pages > 1) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                repeat(pages) { index ->
                                    val start = index * pageSize + 1
                                    val end = minOf((index + 1) * pageSize, episodes.size)
                                    PageChip("$start-$end", index == page) { page = index }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        val pageEpisodes = episodes.drop(page * pageSize).take(pageSize)
                        if (pageEpisodes.isEmpty()) {
                            EmptyState(
                                title = "暂无可播放剧集",
                                message = if (groups.isEmpty()) {
                                    "该站点详情没有返回线路；可在上方重试或返回换平台。"
                                } else {
                                    "当前线路没有剧集，试试其它线路。"
                                },
                                icon = Icons.Outlined.CloudOff
                            )
                        } else {
                            EpisodeGrid(
                                episodes = pageEpisodes,
                                selectedIndex = selectedEpisode - page * pageSize,
                                columns = 4,
                                onSelect = { local ->
                                    selectedEpisode = page * pageSize + local
                                    val d = detail
                                    if (d != null) {
                                        vm.preparePlay(d, selectedGroup, selectedEpisode)
                                        onPlay()
                                    }
                                }
                            )
                        }
                        if (!detail?.recommend.isNullOrEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            SectionTitle("相关推荐", "来自当前平台")
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    if (!detail?.recommend.isNullOrEmpty()) {
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(detail!!.recommend.size) { index ->
                                val rec = detail!!.recommend[index]
                                PosterCard(item = rec, onClick = { onOpenDetail(rec) })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun playButtonText(watched: String?): String =
    if (!watched.isNullOrBlank()) "继续 $watched" else "播放"

@Composable
private fun BackRow(onBack: () -> Unit) {
    val palette = GlassTheme.colors
    GlassIconButton(
        icon = Icons.Outlined.ArrowBack,
        contentDescription = "返回",
        size = 46.dp,
        bg = palette.surface.copy(alpha = 0.92f),
        tint = palette.textPrimary,
        onClick = onBack
    )
}

@Composable
private fun DetailHead(poster: String, onBack: () -> Unit) {
    val palette = GlassTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(GlassTheme.dimens.heroHeight)
    ) {
        NetworkPoster(poster, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.42f to Color.Transparent,
                        0.76f to palette.bgTop.copy(alpha = 0.62f),
                        1f to palette.bgTop
                    )
                )
        )
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            BackRow(onBack)
        }
    }
}

/** 原版主按钮：全宽浅灰半透明底 + 深色文字，不是玫红实底。 */
@Composable
private fun DetailPlayButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = GlassTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(GlassTheme.dimens.mainButtonHeight)
            .clip(GlassTheme.shapes.pill)
            // 原版播放钮为淡玫红实底、无描边。
            .background(if (enabled) palette.accentSoft else palette.chipBg.copy(alpha = 0.6f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            "播放",
            tint = if (enabled) palette.textPrimary else palette.textTertiary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (enabled) text else "暂无可用剧集",
            color = if (enabled) palette.textPrimary else palette.textTertiary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun QuickActions(
    favorite: Boolean,
    onSwitchPlatform: () -> Unit,
    onDownload: () -> Unit,
    onFavorite: () -> Unit,
    onFollow: () -> Unit,
    onComment: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        QuickAction(Icons.Outlined.Search, "换平台", onSwitchPlatform)
        QuickAction(Icons.Outlined.Download, "下载", onDownload)
        QuickAction(Icons.Outlined.FavoriteBorder, "收藏", onFavorite, active = favorite)
        QuickAction(Icons.Outlined.Apps, "追更", onFollow)
        QuickAction(Icons.Outlined.ChatBubbleOutline, "评论", onComment)
    }
}

/** 原版快捷操作：46dp 浅灰圆 + 单色图标，无文字标签。 */
@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    val palette = GlassTheme.colors
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (active) palette.accentSoft else palette.chipBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            label,
            tint = if (active) palette.accent else palette.textPrimary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun PageChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val palette = GlassTheme.colors
    Box(
        Modifier
            .height(32.dp)
            .clip(GlassTheme.shapes.pill)
            .background(if (selected) palette.accent else palette.chipBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) palette.onAccent else palette.textPrimary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}
