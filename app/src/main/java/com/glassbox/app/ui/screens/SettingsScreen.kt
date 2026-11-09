package com.glassbox.app.ui.screens

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
import androidx.compose.material.icons.outlined.Hardware
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material.icons.outlined.MotionPhotosAuto
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassbox.app.BuildConfig
import com.glassbox.app.core.DeviceNet
import com.glassbox.app.engine.PlaybackKernel
import com.glassbox.app.ui.MainViewModel
import com.glassbox.app.ui.components.*
import com.glassbox.app.ui.theme.GlassTheme

/**
 * 设置（对齐 02 §7.8）：40dp 彩色图标方块 + iOS 语义色分组，
 * 行高约 69dp；右侧为 Switch / 文字值 / 箭头。
 * 「账号与服务」一栏全部标注「未配置 / 模拟占位」。
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
            }

            SettingsGroup("播放") {
                SettingsRow(Icons.Outlined.Movie, palette.iconViolet, "播放内核", "MPV 默认 · ExoPlayer · IJK") {
                    ValueText(PlaybackKernel.fromKey(settings.playerKernel).label) {
                        val kernels = PlaybackKernel.entries
                        val current = PlaybackKernel.fromKey(settings.playerKernel)
                        val next = kernels[(kernels.indexOf(current) + 1) % kernels.size]
                        vm.updateSettings { it.copy(playerKernel = next.key) }
                        showToast(context, "播放内核：${next.label}（下次起播生效）")
                    }
                }
                SettingsRow(Icons.Outlined.PlayCircle, palette.iconPink, "自动续播", "从上次进度继续") {
                    Switch(
                        checked = settings.autoResume,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(autoResume = on) } },
                        colors = switchColors()
                    )
                }
                SettingsRow(Icons.Outlined.Hardware, palette.iconBlue, "硬件解码") {
                    Switch(
                        checked = settings.hardwareDecode,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(hardwareDecode = on) } },
                        colors = switchColors()
                    )
                }
                SettingsRow(Icons.Outlined.Speed, palette.iconGreen, "默认倍速") {
                    ValueText(speedText(settings.defaultSpeed)) {
                        val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f)
                        val next = speeds[(speeds.indexOf(settings.defaultSpeed).coerceAtLeast(0) + 1) % speeds.size]
                        vm.updateSettings { it.copy(defaultSpeed = next) }
                    }
                }
                SettingsRow(Icons.Outlined.VideoSettings, palette.iconViolet, "首选线路") {
                    ValueText("线路 ${settings.preferredGroup + 1}") {
                        vm.updateSettings { it.copy(preferredGroup = (settings.preferredGroup + 1) % 3) }
                    }
                }
                SettingsRow(Icons.Outlined.DeleteSweep, palette.iconOrange, "去广告", "线路过滤 + M3U8切片清洗热切换") {
                    Switch(
                        checked = settings.adFilterEnabled,
                        onCheckedChange = { on -> vm.updateSettings { it.copy(adFilterEnabled = on) } },
                        colors = switchColors()
                    )
                }
                SettingsRow(Icons.Outlined.Speed, palette.iconBlue, "片头跳过", "起播自动跳过前 N 秒") {
                    ValueText(if (settings.skipIntroSec <= 0) "关" else "${settings.skipIntroSec}s") {
                        val options = listOf(0, 30, 60, 90, 120)
                        val idx = options.indexOf(settings.skipIntroSec).let { if (it < 0) 0 else it }
                        val next = options[(idx + 1) % options.size]
                        vm.updateSettings { it.copy(skipIntroSec = next) }
                        showToast(context, if (next == 0) "片头跳过已关" else "片头跳过 ${next}s")
                    }
                }
            }

            SettingsGroup("融合能力") {
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
                        onCheckedChange = { on -> vm.setRemoteControlEnabled(on) },
                        colors = switchColors()
                    )
                }
            }

            SettingsGroup("数据") {
                SettingsRow(
                    Icons.Outlined.Extension,
                    palette.iconBlue,
                    "所有文件访问权限",
                    if (Environment.isExternalStorageManager()) "已授权" else "PY 源扫描本地目录需要"
                ) {
                    val granted = Environment.isExternalStorageManager()
                    ValueText(if (granted) "已授权" else "去授权") {
                        if (!granted) {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }.onFailure { showToast(context, "无法打开权限设置，请到系统设置手动授权") }
                        } else {
                            showToast(context, "已拥有所有文件访问权限")
                        }
                    }
                }
                SettingsRow(Icons.Outlined.QueryStats, palette.iconBlueAlt, "清空搜索历史") {
                    ValueText("清空") {
                        vm.clearSearchHistory()
                        showToast(context, "已清空搜索历史")
                    }
                }
                SettingsRow(Icons.Outlined.DeleteSweep, palette.iconOrange, "清空播放历史") {
                    ValueText("清空") {
                        vm.clearHistory()
                        showToast(context, "已清空播放历史")
                    }
                }
            }

            SettingsGroup("账号与服务（可配置占位）") {
                SettingsRow(Icons.Outlined.Cloud, palette.iconBlue, "网盘账号", "未配置 · 本地模拟占位") {
                    ValueText("未配置") { showToast(context, "网盘账号：可配置占位，未接入任何网盘服务") }
                }
                SettingsRow(Icons.Outlined.AutoAwesome, palette.iconPurple, "AI 大模型", "未接入 · 本地模拟占位") {
                    ValueText("未配置") { showToast(context, "AI：可配置占位，未接入模型服务") }
                }
                SettingsRow(Icons.Outlined.Extension, palette.iconGreen, "组件仓库", "未接入 · 本地模拟占位") {
                    ValueText("未配置") { showToast(context, "组件仓库：可配置占位，未拉取任何组件") }
                }
                SettingsRow(Icons.Outlined.Link, palette.iconOrange, "私有解析接口", "未配置 · 本地模拟占位") {
                    ValueText("未配置") { showToast(context, "私有解析：可配置占位，未发起真实解析") }
                }
            }

            SettingsGroup("关于") {
                SettingsRow(Icons.Outlined.Info, palette.textSecondary, "版本") {
                    ValueText(BuildConfig.VERSION_NAME) {}
                }
                SettingsRow(Icons.Outlined.Info, palette.iconBlue, "开源组件") {
                    ValueText("查看") {
                        showToast(context, "Media3 / Compose / Coil / Chaquopy 等开源组件")
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
            Text(
                title,
                color = palette.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
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
