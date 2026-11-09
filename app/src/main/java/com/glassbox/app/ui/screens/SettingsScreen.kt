package com.glassbox.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hardware
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.MotionPhotosAuto
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import com.glassbox.app.core.DeviceNet
import com.glassbox.app.ui.components.showToast
import com.glassbox.app.engine.PlaybackKernel
import com.glassbox.app.ui.MainViewModel
import com.glassbox.app.ui.components.GlassSurface
import com.glassbox.app.ui.theme.GlassTheme

/**
 * 设置页：对齐 base.apk（Flutter Glass）文案与能力分组，全部可操作、无占位。
 */
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    isRoot: Boolean,
    onBack: () -> Unit,
    onLive: () -> Unit = {},
    onLocalFiles: () -> Unit = {}
) {
    val palette = GlassTheme.colors
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    var showProxyDialog by remember { mutableStateOf(false) }
    var showParseDialog by remember { mutableStateOf(false) }
    var proxyHost by remember(settings.proxyHost) { mutableStateOf(settings.proxyHost) }
    var proxyPort by remember(settings.proxyPort) { mutableStateOf(if (settings.proxyPort > 0) settings.proxyPort.toString() else "") }
    var parseJson by remember(settings.parseListJson) { mutableStateOf(settings.parseListJson) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val msg = vm.restoreBackup(context, uri)
            showToast(context, msg)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.statusBarsPadding())
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isRoot) {
                Icon(
                    Icons.Outlined.ArrowBack, "返回",
                    tint = palette.textPrimary,
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { onBack() }
                )
                Spacer(Modifier.width(8.dp))
            }
            Text("设置", color = palette.textPrimary, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 18.dp, bottom = 96.dp)
        ) {
            SettingsGroup("外观与交互") {
                SettingsRow(Icons.Outlined.DarkMode, palette.iconPurple, "深色模式") {
                    Switch(
                        checked = settings.darkTheme,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(darkTheme = on) } },
                        colors = switchColors()
                    )
                }
                SettingsRow(Icons.Outlined.ColorLens, palette.iconOrange, "强调色", "首页与按钮主色") {
                    val labels = listOf("玫红", "蓝", "紫", "青", "橙")
                    ValueText(labels.getOrElse(settings.accentIndex) { "玫红" }) {
                        vm.updateSettings { it.copy(accentIndex = (settings.accentIndex + 1) % labels.size) }
                    }
                }
                SettingsRow(Icons.Outlined.AutoAwesome, palette.iconPink, "字号", "界面文字缩放") {
                    val options = listOf(0.85f, 1.0f, 1.15f)
                    val label = when (settings.fontScale) {
                        0.85f -> "小"; 1.15f -> "大"; else -> "标准"
                    }
                    ValueText(label) {
                        val i = options.indexOf(settings.fontScale).let { if (it < 0) 1 else it }
                        vm.updateSettings { it.copy(fontScale = options[(i + 1) % options.size]) }
                    }
                }
                SettingsRow(Icons.Outlined.MotionPhotosAuto, palette.iconPink, "动态光晕", "首页大图缓慢漂移") {
                    Switch(
                        checked = settings.animateGlow,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(animateGlow = on) } },
                        colors = switchColors()
                    )
                }
                SettingsRow(Icons.Outlined.ColorLens, palette.iconOrange, "减少动效") {
                    Switch(
                        checked = settings.reduceMotion,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(reduceMotion = on) } },
                        colors = switchColors()
                    )
                }
                SettingsRow(Icons.Outlined.Movie, palette.iconViolet, "首页轮播海报") {
                    Switch(
                        checked = settings.homeCarousel,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(homeCarousel = on) } },
                        colors = switchColors()
                    )
                }
                SettingsRow(Icons.Outlined.LiveTv, palette.iconBlue, "电视模式", "更大焦点区域，适合遥控") {
                    Switch(
                        checked = settings.tvMode,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(tvMode = on) } },
                        colors = switchColors()
                    )
                }
            }

            SettingsGroup("播放") {
                SettingsRow(Icons.Outlined.Movie, palette.iconViolet, "默认内核", "MPV · ExoPlayer · IJK") {
                    ValueText(PlaybackKernel.fromKey(settings.playerKernel).label) {
                        val kernels = PlaybackKernel.entries
                        val current = PlaybackKernel.fromKey(settings.playerKernel)
                        val next = kernels[(kernels.indexOf(current) + 1) % kernels.size]
                        vm.updateSettings { it.copy(playerKernel = next.key) }
                        showToast(context, "播放内核：${next.label}")
                    }
                }
                SettingsRow(Icons.Outlined.Hardware, palette.iconBlue, "硬件解码", "默认偏好（MPV 起播仍优先稳妥软解）") {
                    Switch(
                        checked = settings.hardwareDecode,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(hardwareDecode = on) } },
                        colors = switchColors()
                    )
                }
                SettingsRow(Icons.Outlined.PlayCircle, palette.iconPink, "自动续播", "从上次进度继续") {
                    Switch(
                        checked = settings.autoResume,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(autoResume = on) } },
                        colors = switchColors()
                    )
                }
                SettingsRow(Icons.Outlined.PlayCircle, palette.iconGreen, "后台播放", "通知栏 / 锁屏控制") {
                    Switch(
                        checked = settings.backgroundPlay,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(backgroundPlay = on) } },
                        colors = switchColors()
                    )
                }
                SettingsRow(Icons.Outlined.Speed, palette.iconBlue, "默认倍速") {
                    ValueText(speedText(settings.defaultSpeed)) {
                        val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f)
                        val next = speeds[(speeds.indexOf(settings.defaultSpeed).coerceAtLeast(0) + 1) % speeds.size]
                        vm.updateSettings { it.copy(defaultSpeed = next) }
                    }
                }
                SettingsRow(Icons.Outlined.VideoSettings, palette.iconViolet, "首选线路") {
                    ValueText("线路 ${settings.preferredGroup + 1}") {
                        vm.updateSettings { it.copy(preferredGroup = (settings.preferredGroup + 1) % 5) }
                    }
                }
                SettingsRow(Icons.Outlined.DeleteSweep, palette.iconOrange, "去广告", "线路过滤 + M3U8 切片清洗热切换") {
                    Switch(
                        checked = settings.adFilterEnabled,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(adFilterEnabled = on) } },
                        colors = switchColors()
                    )
                }
                SettingsRow(Icons.Outlined.Speed, palette.iconBlue, "跳过片头", "起播自动跳过") {
                    ValueText(if (settings.skipIntroSec <= 0) "关" else "${settings.skipIntroSec}s") {
                        val options = listOf(0, 30, 60, 90, 120)
                        val idx = options.indexOf(settings.skipIntroSec).let { if (it < 0) 0 else it }
                        vm.updateSettings { it.copy(skipIntroSec = options[(idx + 1) % options.size]) }
                    }
                }
                SettingsRow(Icons.Outlined.Speed, palette.iconPink, "跳过片尾", "剩余不足 N 秒提示") {
                    ValueText(if (settings.skipOutroSec <= 0) "关" else "${settings.skipOutroSec}s") {
                        val options = listOf(0, 30, 60, 90, 120)
                        val idx = options.indexOf(settings.skipOutroSec).let { if (it < 0) 0 else it }
                        vm.updateSettings { it.copy(skipOutroSec = options[(idx + 1) % options.size]) }
                    }
                }
                SettingsRow(Icons.Outlined.MotionPhotosAuto, palette.iconPurple, "隐藏状态栏", "播放页沉浸") {
                    Switch(
                        checked = settings.hideStatusBar,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(hideStatusBar = on) } },
                        colors = switchColors()
                    )
                }
                SettingsRow(Icons.Outlined.QueryStats, palette.iconOrange, "弹幕", "播放页显示弹幕层（源支持时）") {
                    Switch(
                        checked = settings.danmuEnabled,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(danmuEnabled = on) } },
                        colors = switchColors()
                    )
                }
                SettingsRow(Icons.Outlined.AutoAwesome, palette.iconPink, "短剧预载") {
                    Switch(
                        checked = settings.shortDramaPreload,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(shortDramaPreload = on) } },
                        colors = switchColors()
                    )
                }
            }

            SettingsGroup("网络与解析") {
                SettingsRow(
                    Icons.Outlined.Link,
                    palette.iconBlue,
                    "网络代理",
                    if (settings.proxyEnabled && settings.proxyHost.isNotBlank())
                        "${settings.proxyHost}:${settings.proxyPort}"
                    else "关闭"
                ) {
                    ValueText(if (settings.proxyEnabled) "已开启" else "配置") {
                        proxyHost = settings.proxyHost
                        proxyPort = if (settings.proxyPort > 0) settings.proxyPort.toString() else ""
                        showProxyDialog = true
                    }
                }
                SettingsRow(Icons.Outlined.Extension, palette.iconPurple, "解析接口", "JSON 解析链，播放时回退使用") {
                    ValueText(if (settings.parseListJson.isBlank() || settings.parseListJson == "[]") "未配置" else "已配置") {
                        parseJson = settings.parseListJson
                        showParseDialog = true
                    }
                }
            }

            SettingsGroup("媒体入口") {
                SettingsRow(Icons.Outlined.LiveTv, palette.iconPink, "直播", "M3U / TXT 订阅") {
                    ValueText("打开") { onLive() }
                }
                SettingsRow(Icons.Outlined.FolderOpen, palette.iconBlue, "本地文件", "浏览并播放本地视频/音频") {
                    ValueText("打开") { onLocalFiles() }
                }
                SettingsRow(
                    Icons.Outlined.WifiTethering,
                    palette.iconGreen,
                    "局域网远程控制",
                    if (settings.remoteControlEnabled) {
                        val ip = DeviceNet.localIpv4() ?: "本机"
                        "http://$ip:${settings.remoteControlPort}"
                    } else {
                        "关闭 · 端口 ${settings.remoteControlPort}"
                    }
                ) {
                    Switch(
                        checked = settings.remoteControlEnabled,
                        onCheckedChange = { on ->
                            vm.updateSettings { it.copy(remoteControlEnabled = on) }
                            showToast(context, if (on) "远程控制已开启" else "远程控制已关闭")
                        },
                        colors = switchColors()
                    )
                }
                SettingsRow(Icons.Outlined.VideoSettings, palette.iconViolet, "远程端口") {
                    ValueText(settings.remoteControlPort.toString()) {
                        val ports = listOf(9978, 9979, 8080, 8888)
                        val i = ports.indexOf(settings.remoteControlPort).let { if (it < 0) 0 else it }
                        vm.updateSettings { it.copy(remoteControlPort = ports[(i + 1) % ports.size]) }
                    }
                }
            }

            SettingsGroup("数据与存储") {
                SettingsRow(Icons.Outlined.DeleteSweep, palette.iconOrange, "清空观看历史") {
                    ValueText("清空") {
                        vm.clearHistory()
                        showToast(context, "已清空播放历史")
                    }
                }
                SettingsRow(Icons.Outlined.Cloud, palette.iconBlue, "清除图片与网络缓存") {
                    ValueText("清理") {
                        vm.clearImageAndNetCache(context)
                        showToast(context, "缓存已清理")
                    }
                }
                SettingsRow(Icons.Outlined.FolderOpen, palette.iconGreen, "备份配置与源", "导出到应用外部目录") {
                    ValueText("备份") {
                        val path = vm.backupAll(context)
                        showToast(context, if (path != null) "已备份：$path" else "备份失败")
                    }
                }
                SettingsRow(Icons.Outlined.FolderOpen, palette.iconPurple, "恢复备份", "选择 JSON 备份文件") {
                    ValueText("选择") {
                        restoreLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    }
                }
            }

            SettingsGroup("关于") {
                SettingsRow(Icons.Outlined.Info, palette.iconBlue, "版本") {
                    ValueText("2.0.0-alpha5.5") {}
                }
                SettingsRow(Icons.Outlined.Link, palette.iconGreen, "开源仓库") {
                    ValueText("GitHub") {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yanchenwuxi/Glass"))
                            )
                        }
                    }
                }
                SettingsRow(Icons.Outlined.Info, palette.iconBlue, "开源组件") {
                    ValueText("查看") {
                        showToast(context, "Media3 / Compose / Coil / libmpv / IJK / Chaquopy / NanoHTTPD")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "本应用与任何第三方影视平台、网盘、豆瓣/TMDB 无关联；品牌标识均未使用。",
                color = palette.textTertiary,
                fontSize = 11.sp,
                lineHeight = 17.sp
            )
        }
    }

    if (showProxyDialog) {
        AlertDialog(
            onDismissRequest = { showProxyDialog = false },
            title = { Text("网络代理") },
            text = {
                Column {
                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = { proxyHost = it },
                        label = { Text("主机") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = proxyPort,
                        onValueChange = { proxyPort = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val port = proxyPort.toIntOrNull() ?: 0
                    val enable = proxyHost.isNotBlank() && port in 1..65535
                    vm.updateSettings {
                        it.copy(proxyEnabled = enable, proxyHost = proxyHost.trim(), proxyPort = port)
                    }
                    showProxyDialog = false
                    showToast(context, if (enable) "代理已启用" else "代理已关闭")
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.updateSettings { it.copy(proxyEnabled = false) }
                    showProxyDialog = false
                    showToast(context, "代理已关闭")
                }) { Text("关闭代理") }
            }
        )
    }

    if (showParseDialog) {
        AlertDialog(
            onDismissRequest = { showParseDialog = false },
            title = { Text("解析接口 JSON") },
            text = {
                OutlinedTextField(
                    value = parseJson,
                    onValueChange = { parseJson = it },
                    label = { Text("""[{"name":"…","url":"https://…"}]""") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val normalized = parseJson.trim().ifBlank { "[]" }
                    vm.setParseListJson(normalized)
                    showParseDialog = false
                    showToast(context, "解析链已保存")
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showParseDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = GlassTheme.colors.onAccent,
    checkedTrackColor = GlassTheme.colors.accent,
    uncheckedThumbColor = GlassTheme.colors.textTertiary,
    uncheckedTrackColor = GlassTheme.colors.track
)

private fun speedText(speed: Float): String = when (speed) {
    1.0f -> "1.0x"
    1.25f -> "1.25x"
    1.5f -> "1.5x"
    2.0f -> "2.0x"
    else -> "${speed}x"
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val palette = GlassTheme.colors
    Column(Modifier.padding(bottom = 20.dp)) {
        Text(
            title,
            color = palette.textTertiary,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        GlassSurface(level = 1, modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    color: Color,
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit
) {
    val palette = GlassTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = palette.onAccent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = palette.textSecondary, fontSize = 11.sp)
            }
        }
        trailing()
    }
}

@Composable
private fun ValueText(value: String, onClick: () -> Unit) {
    val palette = GlassTheme.colors
    Row(
        Modifier
            .clip(GlassTheme.shapes.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(value, color = palette.textSecondary, fontSize = 13.sp)
        Spacer(Modifier.width(2.dp))
        Text("›", color = palette.textTertiary, fontSize = 16.sp)
    }
}
