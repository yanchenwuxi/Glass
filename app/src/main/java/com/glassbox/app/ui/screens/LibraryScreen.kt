package com.glassbox.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassbox.app.data.MediaItem
import com.glassbox.app.data.WatchRecord
import com.glassbox.app.ui.MainViewModel
import com.glassbox.app.ui.components.*
import com.glassbox.app.ui.theme.GlassTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 收藏 / 历史（对齐 02 §7.6）：
 * 36sp 大标题 + 收藏/历史 文本标签；右侧圆形清空/排序按钮；
 * 收藏为 2 列 2:3 网格，历史为 96×128 缩略图行 + 进度条 + 玫红圆形播放入口。
 */
@Composable
fun LibraryScreen(
    vm: MainViewModel,
    initialHistory: Boolean,
    isRoot: Boolean,
    onBack: () -> Unit,
    onDetail: (MediaItem) -> Unit,
    onHistory: (WatchRecord) -> Unit,
    bottomInset: Dp
) {
    val palette = GlassTheme.colors
    val context = LocalContext.current
    val favorites by vm.favorites.collectAsState()
    val history by vm.history.collectAsState()
    var historyTab by remember { mutableStateOf(initialHistory) }
    var sortAsc by remember { mutableStateOf(true) }

    val sortedFavorites = remember(favorites, sortAsc) {
        if (sortAsc) favorites.sortedBy { it.title } else favorites.sortedByDescending { it.title }
    }
    val sortedHistory = remember(history, sortAsc) {
        if (sortAsc) history.sortedByDescending { it.updatedAt } else history.sortedBy { it.updatedAt }
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.statusBarsPadding())
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isRoot) {
                GlassIconButton(
                    icon = Icons.Outlined.ArrowBack,
                    contentDescription = "返回",
                    size = 36.dp,
                    tint = palette.textSecondary,
                    onClick = onBack
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                if (historyTab) "历史" else "收藏",
                color = palette.textPrimary,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(14.dp))
            Text(
                "收藏",
                color = if (!historyTab) palette.accent else palette.textTertiary,
                fontSize = 14.sp,
                fontWeight = if (!historyTab) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(GlassTheme.shapes.pill)
                    .clickable { historyTab = false }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            )
            Text(
                "历史",
                color = if (historyTab) palette.accent else palette.textTertiary,
                fontSize = 14.sp,
                fontWeight = if (historyTab) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(GlassTheme.shapes.pill)
                    .clickable { historyTab = true }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            )
            Spacer(Modifier.weight(1f))
            GlassIconButton(
                icon = Icons.Outlined.Sort,
                contentDescription = "排序",
                size = 36.dp,
                tint = palette.textSecondary,
                bg = palette.chipBg,
                onClick = { sortAsc = !sortAsc }
            )
            Spacer(Modifier.width(8.dp))
            GlassIconButton(
                icon = Icons.Outlined.DeleteSweep,
                contentDescription = "清空",
                size = 36.dp,
                tint = palette.textSecondary,
                bg = palette.chipBg,
                onClick = {
                    if (historyTab) {
                        vm.clearHistory()
                        showToast(context, "已清空播放历史")
                    } else {
                        showToast(context, "收藏项请在详情页逐条取消")
                    }
                }
            )
        }
        Spacer(Modifier.height(18.dp))

        if (historyTab) {
            if (sortedHistory.isEmpty()) {
                EmptyState(
                    title = "还没有播放记录",
                    message = "播放任意剧集后会自动记录进度，下次可以接着看。",
                    icon = Icons.Outlined.History
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = bottomInset),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    sortedHistory.forEach { record ->
                        HistoryRow(
                            record = record,
                            onClick = { onHistory(record) },
                            onRemove = { vm.removeHistory(record.key) }
                        )
                    }
                }
            }
        } else {
            if (sortedFavorites.isEmpty()) {
                EmptyState(
                    title = "收藏夹是空的",
                    message = "在详情页点「收藏」，作品会出现在这里。",
                    icon = Icons.Outlined.FavoriteBorder
                )
            } else {
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = bottomInset)
                ) {
                    val cellWidth = (maxWidth - 12.dp) / 2
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        sortedFavorites.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { item ->
                                    PosterCard(item = item, width = cellWidth, onClick = { onDetail(item) })
                                }
                                if (row.size == 1) Spacer(Modifier.width(cellWidth))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    record: WatchRecord,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val palette = GlassTheme.colors
    val progress = if (record.durationMs > 0) {
        (record.positionMs.toFloat() / record.durationMs).coerceIn(0f, 1f)
    } else 0f
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    GlassSurface(
        level = 1,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        contentPadding = PaddingValues(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(96.dp)
                    .height(128.dp)
                    .clip(GlassTheme.shapes.sm)
            ) {
                NetworkPoster(record.poster, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    record.title,
                    color = palette.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    record.episodeName.ifBlank { "上次观看" },
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(GlassTheme.shapes.pill)
                        .background(palette.track)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .background(palette.accent)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已看 ${(progress * 100).toInt()}%",
                        color = palette.textSecondary,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (record.updatedAt > 0) fmt.format(Date(record.updatedAt)) else "",
                        color = palette.textTertiary,
                        fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(palette.accent)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, "继续播放", tint = palette.onAccent, modifier = Modifier.size(22.dp))
            }
        }
    }
}
