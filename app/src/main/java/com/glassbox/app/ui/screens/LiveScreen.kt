package com.glassbox.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
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
import com.glassbox.app.engine.live.LiveChannelGroup
import com.glassbox.app.engine.live.LiveChannelItem
import com.glassbox.app.ui.MainViewModel
import com.glassbox.app.ui.components.GlassIconButton
import com.glassbox.app.ui.theme.GlassTheme
import kotlinx.coroutines.launch

/**
 * 直播页（P1）：导入 M3U/TXT URL，分组列表 + 频道列表。
 * 选中频道后回调 onPlay(url, title)，由外层进入播放页或直接起播。
 */
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
    var urlInput by remember { mutableStateOf(settings.liveSourceUrl) }
    var selectedGroup by remember { mutableStateOf(0) }
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
                icon = Icons.Outlined.Refresh,
                contentDescription = "刷新",
                size = 36.dp,
                tint = palette.textSecondary,
                bg = palette.chipBg,
                onClick = {
                    scope.launch { vm.loadLiveSource(urlInput.trim()) }
                }
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("直播源 URL（m3u / txt）") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary,
                    focusedBorderColor = palette.accent,
                    unfocusedBorderColor = palette.chipBg,
                    focusedLabelColor = palette.textSecondary,
                    unfocusedLabelColor = palette.textTertiary,
                    cursorColor = palette.accent
                ),
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.accent)
                    .clickable {
                        scope.launch { vm.loadLiveSource(urlInput.trim()) }
                    }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text("加载直播源", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.accent)
            }
            return@Column
        }

        if (!error.isNullOrBlank()) {
            Text(
                error ?: "",
                color = palette.iconOrange,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("粘贴 m3u/txt 地址后点击加载", color = palette.textTertiary, fontSize = 14.sp)
            }
            return@Column
        }

        Row(Modifier.fillMaxSize()) {
            // 分组
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
                            .background(if (selected) palette.accent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 14.dp)
                    )
                }
            }
            // 频道
            val channels = groups.getOrNull(selectedGroup)?.channels.orEmpty()
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp)
            ) {
                itemsIndexed(channels) { _, ch ->
                    ChannelRow(ch, palette) {
                        val url = ch.currentUrl ?: return@ChannelRow
                        onPlay(url, ch.name)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    ch: LiveChannelItem,
    palette: com.glassbox.app.ui.theme.GlassPalette,
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
            if (ch.sourceCount > 1) {
                Text("${ch.sourceCount} 条线路", color = palette.textTertiary, fontSize = 11.sp)
            }
        }
        Text("播放", color = palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
