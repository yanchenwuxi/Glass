package com.glassbox.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassbox.app.engine.live.LiveChannelItem
import com.glassbox.app.engine.live.LiveEpgDay
import com.glassbox.app.ui.MainViewModel
import com.glassbox.app.ui.components.GlassIconButton
import com.glassbox.app.ui.theme.GlassPalette
import com.glassbox.app.ui.theme.GlassTheme
import kotlinx.coroutines.launch

@Composable
fun LiveScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onPlay: (url: String, title: String) -> Unit
) {
    val palette = GlassTheme.colors
    val groups by vm.liveGroups.collectAsState()
    val loading by vm.liveLoading.collectAsState()
    val error by vm.liveError.collectAsState()
    val settings by vm.settings.collectAsState()
    val epgDays by vm.epgDays.collectAsState()
    val epgLoading by vm.epgLoading.collectAsState()
    val epgNowNext by vm.epgNowNext.collectAsState()
    var urlInput by remember { mutableStateOf(settings.liveSourceUrl) }
    var epgInput by remember { mutableStateOf(settings.epgUrl) }
    var selectedGroup by remember { mutableStateOf(0) }
    var selectedChannel by remember { mutableStateOf<LiveChannelItem?>(null) }
    var showEpg by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.statusBarsPadding())
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(
                icon = Icons.Outlined.ArrowBack,
                contentDescription = "返回",
                size = 36.dp,
                tint = palette.textSecondary,
                onClick = onBack
            )
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Outlined.LiveTv, null, tint = palette.accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text("直播", color = palette.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            GlassIconButton(
                icon = Icons.Outlined.DateRange,
                contentDescription = "EPG",
                size = 36.dp,
                tint = if (showEpg) palette.accent else palette.textSecondary,
                bg = palette.chipBg,
                onClick = { showEpg = !showEpg }
            )
            Spacer(Modifier.width(8.dp))
            GlassIconButton(
                icon = Icons.Outlined.Refresh,
                contentDescription = "刷新",
                size = 36.dp,
                tint = palette.textSecondary,
                bg = palette.chipBg,
                onClick = { scope.launch { vm.loadLiveSource(urlInput.trim()) } }
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("直播源 URL（m3u / txt）") },
                colors = fieldColors(palette),
                shape = RoundedCornerShape(14.dp)
            )
            if (showEpg) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = epgInput,
                    onValueChange = { epgInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("EPG XMLTV 地址（可选）") },
                    colors = fieldColors(palette),
                    shape = RoundedCornerShape(14.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("加载直播源", Modifier.weight(1f), palette) {
                    scope.launch { vm.loadLiveSource(urlInput.trim()) }
                }
                if (showEpg) {
                    ActionButton(
                        if (epgLoading) "EPG…" else "加载 EPG",
                        Modifier.weight(1f),
                        palette
                    ) {
                        vm.loadEpg(epgInput.trim(), force = true)
                    }
                }
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.accent)
            }
            return@Column
        }

        if (!error.isNullOrBlank()) {
            Text(error ?: "", color = palette.iconOrange, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }

        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("粘贴 m3u/txt 地址后点击加载", color = palette.textTertiary, fontSize = 14.sp)
            }
            return@Column
        }

        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier
                    .width(110.dp)
                    .fillMaxHeight()
                    .background(palette.chipBg.copy(alpha = 0.35f))
            ) {
                itemsIndexed(groups) { index, g ->
                    val selected = index == selectedGroup
                    Text(
                        text = g.groupName,
                        color = if (selected) palette.accent else palette.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedGroup = index }
                            .background(
                                if (selected) palette.accent.copy(alpha = 0.12f)
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .padding(horizontal = 12.dp, vertical = 14.dp)
                    )
                }
            }
            val channels = groups.getOrNull(selectedGroup)?.channels.orEmpty()
            Column(Modifier.weight(1f).fillMaxHeight()) {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    itemsIndexed(channels) { _, ch ->
                        ChannelRow(ch, palette, epgNowNext.takeIf { selectedChannel?.name == ch.name }) {
                            selectedChannel = ch
                            vm.refreshEpgForChannel(ch.epgId, ch.name)
                            val url = ch.currentUrl ?: return@ChannelRow
                            onPlay(url, ch.name)
                        }
                    }
                }
                if (showEpg && (epgDays.isNotEmpty() || epgLoading)) {
                    EpgPanel(
                        days = epgDays,
                        loading = epgLoading,
                        channelName = selectedChannel?.name,
                        palette = palette,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .background(palette.chipBg.copy(alpha = 0.4f))
                    )
                }
            }
        }
    }
}

@Composable
private fun EpgPanel(
    days: List<LiveEpgDay>,
    loading: Boolean,
    channelName: String?,
    palette: GlassPalette,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(12.dp)) {
        Text(
            "节目单" + (channelName?.let { " · $it" } ?: ""),
            color = palette.textPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(6.dp))
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = palette.accent, strokeWidth = 2.dp)
            return
        }
        if (days.isEmpty()) {
            Text("暂无 EPG，请加载 XMLTV 地址后点选频道", color = palette.textTertiary, fontSize = 12.sp)
            return
        }
        LazyColumn {
            days.forEach { day ->
                item {
                    Text(day.dateLabel, color = palette.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                itemsIndexed(day.programs) { _, p ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            "${p.startTime}-${p.endTime}",
                            color = palette.textTertiary,
                            fontSize = 11.sp,
                            modifier = Modifier.width(90.dp)
                        )
                        Text(p.title, color = palette.textPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    ch: LiveChannelItem,
    palette: GlassPalette,
    nowNext: Pair<com.glassbox.app.engine.live.LiveEpgProgram?, com.glassbox.app.engine.live.LiveEpgProgram?>?,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(ch.name, color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val now = nowNext?.first
            if (now != null) {
                Text("正在播放：${now.title}", color = palette.textTertiary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (ch.sourceCount > 1) {
                Text("${ch.sourceCount} 条线路", color = palette.textTertiary, fontSize = 11.sp)
            }
        }
        Text("播放", color = palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ActionButton(text: String, modifier: Modifier, palette: GlassPalette, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.accent)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(text, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun fieldColors(palette: GlassPalette) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = palette.textPrimary,
    unfocusedTextColor = palette.textPrimary,
    focusedBorderColor = palette.accent,
    unfocusedBorderColor = palette.chipBg,
    focusedLabelColor = palette.textSecondary,
    unfocusedLabelColor = palette.textTertiary,
    cursorColor = palette.accent
)
