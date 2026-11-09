package com.glassbox.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassbox.app.core.displayName
import com.glassbox.app.data.MediaItem
import com.glassbox.app.ui.MainViewModel
import com.glassbox.app.ui.components.*
import com.glassbox.app.ui.theme.GlassTheme

/**
 * 搜索（对齐 02 §7.3）：顶部 56dp 白胶囊输入 + 右侧「取消」；
 * 双通道切换（影视源 / 网盘模拟占位）；结果按平台分组、行式 96×128 海报。
 */
@Composable
fun SearchScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onDetail: (MediaItem) -> Unit
) {
    val palette = GlassTheme.colors
    val state by vm.search.collectAsState()
    val history by vm.searchHistory.collectAsState()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var input by remember { mutableStateOf(state.query) }
    var channel by remember { mutableIntStateOf(0) }
    var filterSite by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.statusBarsPadding())
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassSearchBar(
                hint = "搜索影视、演员、导演",
                value = input,
                modifier = Modifier.weight(1f),
                leadingIcon = Icons.Outlined.Search,
                focusRequester = focus,
                onValueChange = {
                    input = it
                    if (it.isBlank()) vm.clearSearch()
                },
                onSearch = {
                    keyboard?.hide()
                    vm.search(input)
                },
                trailing = {
                    if (input.isNotEmpty()) {
                        Icon(
                            Icons.Outlined.Close,
                            "清空",
                            tint = palette.textTertiary,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable {
                                    input = ""
                                    vm.clearSearch()
                                }
                        )
                    }
                }
            )
            Text(
                "取消",
                color = palette.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(GlassTheme.shapes.pill)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChannelTab("影视源", channel == 0) { channel = 0 }
            ChannelTab("网盘搜索 · 模拟", channel == 1) { channel = 1 }
        }

        if (channel == 1) {
            PlaceholderPanel(
                title = "网盘搜索为本地模拟",
                message = "该入口为可配置占位：未接入任何网盘账号或解析服务，不会返回真实结果。可在设置 → 账号与服务中查看配置项。"
            )
            return@Column
        }

        Spacer(Modifier.height(12.dp))
        when {
            state.loading -> LoadingState(text = "正在搜索「${state.query}」…")
            state.query.isBlank() -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    if (history.isNotEmpty()) {
                        Text("最近搜索", color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            history.forEach { keyword ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(GlassTheme.shapes.sm)
                                        .background(palette.surface)
                                        .clickable {
                                            input = keyword
                                            vm.search(keyword)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(keyword, color = palette.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    Text("搜索", color = palette.textSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    EmptyState(
                        title = "输入关键词开始搜索",
                        message = "会同时在所有已启用平台的接口中检索，结果按平台分组展示，不做合并。",
                        icon = Icons.Outlined.Search
                    )
                }
            }
            state.hits.isEmpty() && state.errors.isEmpty() -> EmptyState(
                title = "没有找到相关结果",
                message = "换一个关键词，或在资源管理里启用更多平台。",
                icon = Icons.Outlined.CloudOff
            )
            state.hits.isEmpty() -> Column(Modifier.padding(horizontal = 16.dp)) {
                ErrorNotice(
                    message = state.errors.firstOrNull() ?: "所有平台都没有返回结果",
                    title = "搜索失败",
                    onRetry = { vm.search(state.query) }
                )
            }
            else -> {
                val siteNames = remember(state.hits) { state.hits.map { it.site.name }.distinct() }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GlassChip("全部 ${state.hits.size}", filterSite == null) { filterSite = null }
                        siteNames.forEach { name ->
                            GlassChip(displayName(name), filterSite == name) { filterSite = name }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val grouped = state.hits
                    .filter { filterSite == null || it.site.name == filterSite }
                    .groupBy { it.site.key to it.site.name }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    grouped.forEach { (key, hits) ->
                        item(key = "hdr_" + key.first) {
                            PlatformHeader(name = displayName(hits.first().site.name), count = hits.size)
                        }
                        items(hits, key = { it.site.key + "::" + it.item.id }) { hit ->
                            SearchRow(hit.item, displayName(hit.site.name)) { onDetail(hit.item) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelTab(text: String, selected: Boolean, onClick: () -> Unit) {
    val palette = GlassTheme.colors
    Box(
        Modifier
            .clip(GlassTheme.shapes.pill)
            .background(if (selected) palette.accentSoft else palette.chipBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            color = if (selected) palette.accent else palette.textSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun PlatformHeader(name: String, count: Int) {
    val palette = GlassTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(palette.accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(name, color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("$count 条", color = palette.textSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun SearchRow(item: MediaItem, siteName: String, onClick: () -> Unit) {
    val palette = GlassTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(GlassTheme.shapes.md)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Box(
            Modifier
                .width(96.dp)
                .height(128.dp)
                .clip(GlassTheme.shapes.sm)
        ) {
            NetworkPoster(item.poster, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = palette.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(5.dp))
            Text(
                listOf(item.year, item.area, item.typeName).filter { it.isNotBlank() }.joinToString(" · "),
                color = palette.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.remarks.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(item.remarks, color = palette.textTertiary, fontSize = 12.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SoftBadge(siteName)
                if (item.score.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(item.score, color = palette.star, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun PlaceholderPanel(title: String, message: String) {
    val palette = GlassTheme.colors
    Box(Modifier.fillMaxWidth().padding(16.dp)) {
        GlassSurface(level = 1, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(palette.chipBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.CloudOff, null, tint = palette.iconBlue, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(title, color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                Text(message, color = palette.textSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(10.dp))
                SoftBadge("可配置占位 · 未接入")
            }
        }
    }
}
