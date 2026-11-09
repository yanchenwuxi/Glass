package com.glassbox.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PlaylistAddCheck
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassbox.app.core.displayName
import com.glassbox.app.data.SourceEntity
import com.glassbox.app.data.SourceStatus
import com.glassbox.app.data.SourceType
import com.glassbox.app.data.SourceOrigin
import com.glassbox.app.ui.MainViewModel
import com.glassbox.app.ui.SourceBatchAction
import com.glassbox.app.ui.components.*
import com.glassbox.app.ui.components.showToast
import com.glassbox.app.ui.theme.GlassTheme

/**
 * 资源管理（对齐 02 §7.4 + 05 号源导入规范）：
 * 48dp 顶部动作条（筛选/搜索/添加/校验/测速）→ 类型 chip → 2 列源卡（173×122 比例）→
 * 源详情抽屉内按「站点」逐项启停（多源不合并、按平台分组）。
 * 导入抽屉覆盖 13 类入口：媒体服务器 / 影视源 / 直播 / 本地。
 */
@Composable
fun SourcesScreen(
    vm: MainViewModel,
    isRoot: Boolean,
    onBack: () -> Unit
) {
    val palette = GlassTheme.colors
    val context = LocalContext.current
    val sources by vm.sources.collectAsState()
    val settings by vm.settings.collectAsState()
    val importState by vm.importState.collectAsState()
    var typeFilter by remember { mutableStateOf<SourceType?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var detailSource by remember { mutableStateOf<SourceEntity?>(null) }
    // 批量操作：进入多选态 → 勾选 → 全选/反选 → 执行（删除需二次确认）
    var batchMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchRunning by remember { mutableStateOf(false) }
    var batchProgress by remember { mutableStateOf("") }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    fun exitBatch() {
        if (batchRunning) return
        batchMode = false
        selected = emptySet()
    }

    fun runBatch(action: SourceBatchAction) {
        if (selected.isEmpty() || batchRunning) return
        batchRunning = true
        batchProgress = "0/${selected.size}"
        vm.batchSources(
            ids = selected.toList(),
            action = action,
            onProgress = { cur, total -> batchProgress = "$cur/$total" },
            onDone = { ok, failed ->
                batchRunning = false
                batchProgress = ""
                batchMode = false
                selected = emptySet()
                showToast(
                    context,
                    "${action.label}完成：成功 $ok" + if (failed > 0) "，失败 $failed" else ""
                )
            }
        )
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            showImport = false
            vm.importUris(uris) { imported, failed ->
                showToast(context, "已导入 $imported 个源" + if (failed > 0) "，$failed 个未识别" else "")
            }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            showImport = false
            vm.importFolder(uri) { imported, failed ->
                showToast(context, "目录扫描完成：导入 $imported 个" + if (failed > 0) "，跳过 $failed 个" else "")
            }
        }
    }

    val filtered = sources.filter { typeFilter == null || it.type == typeFilter }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.statusBarsPadding())
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
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
            Text("资源", color = palette.textPrimary, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (batchMode) {
                GlassIconButton(
                    Icons.Outlined.Close,
                    "退出批量操作",
                    size = 36.dp,
                    tint = palette.textSecondary,
                    bg = palette.chipBg,
                    onClick = { exitBatch() }
                )
            } else {
                GlassIconButton(
                    Icons.Outlined.Tune,
                    "筛选",
                    size = 36.dp,
                    tint = palette.textSecondary,
                    bg = palette.chipBg,
                    onClick = {
                        typeFilter = when (typeFilter) {
                            null -> SourceType.JSON
                            SourceType.JSON -> SourceType.JS
                            SourceType.JS -> SourceType.PY
                            SourceType.PY -> null
                        }
                    }
                )
                Spacer(Modifier.width(8.dp))
                GlassIconButton(
                    Icons.Outlined.Add,
                    "添加",
                    size = 36.dp,
                    tint = palette.onAccent,
                    bg = palette.accent,
                    onClick = { showImport = true }
                )
            }
        }
        if (batchMode) {
            BatchBar(
                total = filtered.size,
                selectedCount = filtered.count { it.id in selected },
                running = batchRunning,
                progress = batchProgress,
                onSelectAll = { selected = filtered.map { it.id }.toSet() },
                onInvert = { selected = filtered.map { it.id }.toSet() - selected },
                onAction = { action ->
                    if (selected.isNotEmpty()) {
                        if (action == SourceBatchAction.DELETE) confirmBatchDelete = true else runBatch(action)
                    }
                }
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(
                    Icons.Outlined.CheckCircle,
                    "批量操作",
                    size = 36.dp,
                    tint = palette.textSecondary,
                    bg = palette.chipBg,
                    onClick = {
                        batchMode = true
                        selected = emptySet()
                    }
                )
                GlassIconButton(
                    Icons.Outlined.Speed,
                    "测速",
                    size = 36.dp,
                    tint = palette.textSecondary,
                    bg = palette.chipBg,
                    onClick = { showToast(context, "测速为本地模拟占位，未发起真实探测") }
                )
                GlassIconButton(
                    Icons.Outlined.FolderOpen,
                    "目录导入",
                    size = 36.dp,
                    tint = palette.textSecondary,
                    bg = palette.chipBg,
                    onClick = { folderPicker.launch(null) }
                )
                Spacer(Modifier.weight(1f))
                SoftBadge("${sources.size} 个源 · ${sources.sumOf { it.sites.count { s -> s.isEnabled } }} 站点")
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassChip("全部 ${sources.size}", typeFilter == null) { typeFilter = null }
                SourceType.values().forEach { type ->
                    val count = sources.count { it.type == type }
                    GlassChip("${type.label} $count", typeFilter == type) { typeFilter = type }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (filtered.isEmpty()) {
            EmptyState(
                title = "还没有导入源",
                message = "支持 TVBox JSON / JS / PY、海阔视界分享口令、本地文件与文件夹。点右上角「+」开始导入。",
                icon = Icons.Outlined.Dns,
                action = { PrimaryCapsule(text = "导入源", minHeight = 44.dp, onClick = { showImport = true }) }
            )
        } else {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 96.dp)
            ) {
                val cell = (maxWidth - 12.dp) / 2
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    filtered.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { source ->
                                SourceCard(
                                    source = source,
                                    width = cell,
                                    active = source.id == settings.selectedSourceId,
                                    batchMode = batchMode,
                                    selected = source.id in selected,
                                    onClick = {
                                        if (batchMode) {
                                            selected = if (source.id in selected) selected - source.id
                                            else selected + source.id
                                        } else {
                                            detailSource = source
                                        }
                                    }
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.width(cell))
                        }
                    }
                }
            }
        }
    }

    // 批量删除二次确认：误触可取消
    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text("删除 ${selected.size} 个源？") },
            text = { Text("删除后需重新导入，已导入的站点与使用记录将一并移除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmBatchDelete = false
                    runBatch(SourceBatchAction.DELETE)
                }) { Text("删除", color = palette.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchDelete = false }) {
                    Text("取消", color = palette.textSecondary)
                }
            }
        )
    }

    // 源详情：逐站点启停
    detailSource?.let { current ->
        val live = sources.firstOrNull { it.id == current.id }
        GlassBottomSheet(visible = true, onDismiss = { detailSource = null }, maxHeight = 620.dp) {
            if (live == null) {
                detailSource = null
            } else {
                SourceDetail(
                    source = live,
                    onClose = { detailSource = null },
                    onToggleSite = { site, enabled -> vm.toggleSite(site.copy(sourceId = live.id, sourceName = live.name), enabled) },
                    onAll = { enabled -> vm.toggleAllSites(live.id, enabled) },
                    onUpdate = { vm.refreshSource(live.id) },
                    onDelete = {
                        vm.deleteSource(live.id)
                        detailSource = null
                        showToast(context, "已删除源：${displayName(live.name)}")
                    },
                    onUse = { site ->
                        vm.selectSite(site.copy(sourceId = live.id, sourceName = live.name))
                        detailSource = null
                        showToast(context, "已切换到 ${displayName(site.name)}")
                    }
                )
            }
        }
    }

    if (showImport) {
        ImportSheet(
            vm = vm,
            state = importState,
            onDismiss = {
                showImport = false
                vm.resetImport()
            },
            onPickFiles = { filePicker.launch(arrayOf("application/json", "text/plain", "application/javascript", "*/*")) },
            onPickFolder = { folderPicker.launch(null) },
            onPlaceholder = { name -> showToast(context, "$name：本地模拟占位，未接入真实服务") }
        )
    }
}

/** 源 message 里带耗时（如「21ms」）时优先展示，否则回落状态文案。 */
private fun sourceStatusText(source: SourceEntity): String {
    val ms = Regex("(\\d+)\\s*ms").find(source.message)
    return ms?.let { "${it.groupValues[1]}ms" } ?: source.status.label
}

@Composable
private fun SourceCard(
    source: SourceEntity,
    width: androidx.compose.ui.unit.Dp,
    active: Boolean,
    batchMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = GlassTheme.colors
    val shape = RoundedCornerShape(18.dp)
    val highlighted = if (batchMode) selected else active
    val ringColor = when {
        batchMode && selected -> palette.accent
        !batchMode && active -> palette.iconBlue
        else -> palette.glassBorder
    }
    Box(
        Modifier
            .width(width)
            .height(128.dp)
            .clip(shape)
            .background(palette.glassFill1)
            .border(if (highlighted) 1.5.dp else 1.dp, ringColor, shape)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(palette.track),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Dns,
                        contentDescription = null,
                        tint = palette.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        displayName(source.name),
                        color = palette.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (active) "当前使用" else source.type.label,
                        color = if (active) palette.iconBlue else palette.textTertiary,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
                if (batchMode) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (selected) palette.accent else Color.Transparent)
                            .border(1.dp, if (selected) palette.accent else palette.divider, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = palette.onAccent,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                } else {
                    Icon(
                        Icons.Outlined.MoreHoriz,
                        contentDescription = "更多",
                        tint = palette.textTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sourceStatusText(source),
                    color = when (source.status) {
                        SourceStatus.VALID -> palette.iconGreen
                        SourceStatus.PENDING -> palette.iconOrange
                        else -> palette.danger
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    " · ${relativeTime(source.updatedAt.takeIf { it > 0 } ?: source.importedAt)}",
                    color = palette.textTertiary,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Layers,
                    contentDescription = null,
                    tint = palette.textTertiary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(3.dp))
                Text("${source.sites.size}", color = palette.textSecondary, fontSize = 11.sp)
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = palette.textTertiary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(3.dp))
                Text("${source.sites.count { it.searchable }}", color = palette.textSecondary, fontSize = 11.sp)
            }
        }
    }
}

/** 批量操作条：已选数量 + 全选/反选 + 启用/停用/更新/删除 + 执行中进度。 */
@Composable
private fun BatchBar(
    total: Int,
    selectedCount: Int,
    running: Boolean,
    progress: String,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onAction: (SourceBatchAction) -> Unit
) {
    val palette = GlassTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已选 $selectedCount / $total",
                color = if (selectedCount > 0) palette.accent else palette.textSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            SmallTextAction("全选", enabled = !running) { onSelectAll() }
            Spacer(Modifier.width(6.dp))
            SmallTextAction("反选", enabled = !running) { onInvert() }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val canRun = selectedCount > 0 && !running
            ActionPill("启用", palette.iconBlue, enabled = canRun) { onAction(SourceBatchAction.ENABLE) }
            ActionPill("停用", palette.iconOrange, enabled = canRun) { onAction(SourceBatchAction.DISABLE) }
            ActionPill("更新", palette.iconGreen, enabled = canRun) { onAction(SourceBatchAction.REFRESH) }
            ActionPill("删除", palette.danger, filled = true, enabled = canRun) { onAction(SourceBatchAction.DELETE) }
            if (running) {
                Spacer(Modifier.width(2.dp))
                CircularProgressIndicator(
                    Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = palette.accent
                )
                Spacer(Modifier.width(6.dp))
                Text("执行中 $progress", color = palette.textSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SmallTextAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = GlassTheme.colors
    Box(
        Modifier
            .clip(GlassTheme.shapes.pill)
            .background(palette.chipBg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            color = if (enabled) palette.textSecondary else palette.textTertiary,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SourceDetail(
    source: SourceEntity,
    onClose: () -> Unit,
    onToggleSite: (com.glassbox.app.data.SiteDef, Boolean) -> Unit,
    onAll: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
    onUse: (com.glassbox.app.data.SiteDef) -> Unit
) {
    val palette = GlassTheme.colors
    LaunchedEffect(source.id) {}
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        SheetHeader(displayName(source.name), "${source.type.label} · ${source.sites.size} 个平台站点")
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionPill("全部启用", palette.iconBlue) { onAll(true) }
            ActionPill("全部停用", palette.iconOrange) { onAll(false) }
            ActionPill("更新", palette.iconGreen) { onUpdate() }
            ActionPill("删除该源", palette.danger, filled = true) { onDelete() }
            ActionPill("关闭", palette.textSecondary) { onClose() }
        }
        source.sites.forEach { site ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .clip(GlassTheme.shapes.sm)
                        .clickable { onUse(site) }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        displayName(site.name),
                        color = if (site.isEnabled) palette.textPrimary else palette.textTertiary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        site.kindLabel + " · " + runCatching { java.net.URI(site.api).host }.getOrNull().orEmpty(),
                        color = palette.textSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = site.isEnabled,
                    onCheckedChange = { onToggleSite(site, it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = palette.onAccent,
                        checkedTrackColor = palette.accent,
                        uncheckedThumbColor = palette.textTertiary,
                        uncheckedTrackColor = palette.track
                    )
                )
            }
        }
    }
}

@Composable
private fun ActionPill(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val palette = GlassTheme.colors
    val tint = if (enabled) color else color.copy(alpha = 0.4f)
    Box(
        Modifier
            .defaultMinSize(minHeight = 36.dp, minWidth = 60.dp)
            .clip(GlassTheme.shapes.pill)
            .background(
                when {
                    !enabled && filled -> palette.chipBg
                    filled -> color
                    else -> palette.chipBg
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (filled && enabled) palette.onAccent else tint, fontSize = 12.sp)
    }
}

@Composable
private fun ImportSheet(
    vm: MainViewModel,
    state: com.glassbox.app.ui.ImportUiState,
    onDismiss: () -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onPlaceholder: (String) -> Unit
) {
    val palette = GlassTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var mode by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var textInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var enableAfter by remember { mutableStateOf(true) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    val preview = state.preview

    LaunchedEffect(preview?.id) {
        preview?.let { selectedKeys = it.sites.map { s -> s.key }.toSet() }
    }

    GlassBottomSheet(visible = true, onDismiss = onDismiss, maxHeight = GlassTheme.dimens.importSheetMaxHeight) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 20.dp)
        ) {
            SheetHeader("导入源", "多源不合并 · 按平台分组")
            Spacer(Modifier.height(10.dp))
            if (mode == null) {
                ImportGroup("影视源") {
                    ImportRow("TVBox 影视源", "在线链接（JSON 配置）", palette.iconBlue) {
                        mode = "url"; nameInput = ""; urlInput = ""
                    }
                    ImportRow("粘贴配置文本", "JSON / JS / PY 脚本", palette.iconPurple) {
                        mode = "text"; nameInput = ""; textInput = ""
                    }
                    ImportRow("海阔视界分享口令", "支持 ￥标记￥链接 / rule:// / Base64 / pasteme", palette.iconPink) {
                        mode = "hiker"
                        textInput = clipboard.getText()?.text.orEmpty()
                    }
                    ImportRow("私有解析接口", "编辑 JSON 解析链，播放失败时回退", palette.iconBlueAlt) {
                        mode = "parse"
                        textInput = vm.settings.value.parseListJson
                    }
                }
                ImportGroup("直播") {
                    ImportRow("直播源 M3U / TXT", "填写订阅地址，写入设置并打开直播", palette.iconOrange) {
                        mode = "live"
                        urlInput = vm.settings.value.liveSourceUrl
                    }
                    ImportRow("局域网推送 / 远程控制", "开启后可用局域网控制播放", palette.iconGreen) {
                        vm.updateSettings { it.copy(remoteControlEnabled = true) }
                        showToast(context, "已开启远程控制，可在设置页查看地址")
                        onDismiss()
                    }
                }
                ImportGroup("从本地导入") {
                    ImportRow("本地文件（可多选）", "JSON / JS / PY / TXT", palette.iconBlue) { onPickFiles() }
                    ImportRow("本地文件夹", "递归扫描所选目录", palette.iconPurple) { onPickFolder() }
                    ImportRow("HTML 网页源", "粘贴网页规则 / 源文本", palette.iconPink) {
                        mode = "text"
                        nameInput = "HTML源"
                        textInput = clipboard.getText()?.text.orEmpty()
                    }
                }
            } else {
                val isUrl = mode == "url" || mode == "live"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when (mode) {
                            "url" -> "TVBox 影视源"
                            "live" -> "直播源 M3U / TXT"
                            "parse" -> "解析接口 JSON"
                            "hiker" -> "海阔视界分享口令"
                            else -> "粘贴配置文本"
                        },
                        color = palette.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "返回",
                        color = palette.accent,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(GlassTheme.shapes.pill)
                            .clickable { mode = null; vm.resetImport() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (mode) {
                        "hiker" -> {
                            MultiLineField(textInput) { textInput = it }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionPill("从剪贴板粘贴", palette.iconBlue) {
                                    textInput = clipboard.getText()?.text.orEmpty()
                                }
                                ActionPill("解析口令", palette.iconPink) {
                                    if (textInput.isBlank()) showToast(context, "请先粘贴海阔口令")
                                    else vm.previewFromHikerShare(textInput)
                                }
                            }
                            Text(
                                "支持：￥home_rule_url￥链接、rule://Base64、整段Base64、pasteme 链接、口令内直链",
                                color = palette.textTertiary,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                        "live" -> {
                            FormField("M3U / TXT 地址", urlInput) { urlInput = it }
                            PrimaryCapsule(
                                text = "保存并使用",
                                minHeight = 44.dp,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (urlInput.isBlank()) showToast(context, "请填写直播源地址")
                                    else {
                                        vm.updateSettings { it.copy(liveSourceUrl = urlInput.trim()) }
                                        showToast(context, "直播源已保存，请到设置或直播页打开")
                                        onDismiss()
                                    }
                                }
                            )
                        }
                        "parse" -> {
                            MultiLineField(textInput) { textInput = it }
                            PrimaryCapsule(
                                text = "保存解析链",
                                minHeight = 44.dp,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val j = textInput.trim().ifBlank { "[]" }
                                    vm.setParseListJson(j)
                                    showToast(context, "解析接口已保存")
                                    onDismiss()
                                }
                            )
                        }
                        else -> {
                            if (isUrl) {
                                FormField("名称（可选）", nameInput) { nameInput = it }
                                FormField("链接地址", urlInput) { urlInput = it }
                                PrimaryCapsule(
                                    text = "解析预览",
                                    minHeight = 44.dp,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        if (urlInput.isBlank()) showToast(context, "请先填写链接")
                                        else vm.previewFromUrl(urlInput.trim())
                                    }
                                )
                            } else {
                                FormField("名称（可选）", nameInput) { nameInput = it }
                                MultiLineField(textInput) { textInput = it }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ActionPill("从剪贴板粘贴", palette.iconBlue) {
                                        textInput = clipboard.getText()?.text.orEmpty()
                                    }
                                    ActionPill("解析预览", palette.iconGreen) {
                                        if (textInput.isBlank()) showToast(context, "请先粘贴配置内容")
                                        else vm.previewFromText(textInput, nameInput.ifBlank { null }, SourceOrigin.CLIPBOARD)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (state.loading) {
                LoadingState(text = state.progress.ifBlank { "正在解析源…" })
            }
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                ErrorNotice(message = it, title = "解析失败", onRetry = null)
            }
            preview?.let { pv ->
                Spacer(Modifier.height(12.dp))
                SurfaceDivider()
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(displayName(pv.name), color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TypeBadge(pv.type)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(pv.message, color = palette.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                    if (pv.ok && pv.sites.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("站点（默认全部启用，可单独取消）", color = palette.textSecondary, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            pv.sites.forEach { site ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = site.key in selectedKeys,
                                        onCheckedChange = { c ->
                                            selectedKeys = if (c) selectedKeys + site.key else selectedKeys - site.key
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = palette.onAccent,
                                            checkedTrackColor = palette.accent,
                                            uncheckedThumbColor = palette.textTertiary,
                                            uncheckedTrackColor = palette.track
                                        )
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(displayName(site.name), color = palette.textPrimary, fontSize = 13.sp, maxLines = 1)
                                    Spacer(Modifier.weight(1f))
                                    TypeBadge(pv.type)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = enableAfter,
                                onCheckedChange = { enableAfter = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = palette.onAccent,
                                    checkedTrackColor = palette.accent,
                                    uncheckedThumbColor = palette.textTertiary,
                                    uncheckedTrackColor = palette.track
                                )
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("导入后立即启用所选站点", color = palette.textSecondary, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        PrimaryCapsule(
                            text = "确认导入",
                            minHeight = 48.dp,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val name = nameInput.ifBlank { displayName(pv.name) }
                                val ok = vm.confirmImport(pv, name, selectedKeys, enableAfter)
                                if (ok) {
                                    showToast(context, "已导入：${displayName(name)}")
                                    onDismiss()
                                }
                            }
                        )
                    } else if (pv.repos.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        pv.repos.forEach { repo ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(GlassTheme.shapes.sm)
                                    .background(palette.chipBg)
                                    .clickable {
                                        val from = pv.url ?: ""
                                        vm.loadRepo(repo, from)
                                    }
                                    .padding(12.dp)
                            ) {
                                Text(displayName(repo.name), color = palette.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text("选择", color = palette.accent, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val palette = GlassTheme.colors
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, color = palette.textTertiary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        GlassSurface(level = 1, modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

@Composable
private fun ImportRow(
    title: String,
    subtitle: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    val palette = GlassTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(GlassTheme.shapes.sm)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Add, null, tint = palette.onAccent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(subtitle, color = palette.textSecondary, fontSize = 11.sp, maxLines = 1)
        }
        Text("›", color = palette.textTertiary, fontSize = 16.sp)
    }
}

@Composable
private fun FormField(label: String, value: String, onChange: (String) -> Unit) {
    val palette = GlassTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = palette.textSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = palette.textPrimary, fontSize = 14.sp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(GlassTheme.shapes.sm)
                .background(palette.chipBg)
                .padding(horizontal = 12.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun MultiLineField(value: String, onChange: (String) -> Unit) {
    val palette = GlassTheme.colors
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        minLines = 4,
        maxLines = 8,
        textStyle = androidx.compose.ui.text.TextStyle(color = palette.textPrimary, fontSize = 13.sp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlassTheme.shapes.sm)
            .background(palette.chipBg)
            .padding(12.dp)
    )
}

private fun relativeTime(ms: Long): String {
    if (ms <= 0) return "未知时间"
    val diff = System.currentTimeMillis() - ms
    val day = diff / (1000 * 60 * 60 * 24)
    return when {
        day <= 0 -> "今天导入"
        day == 1L -> "昨天导入"
        day < 30 -> "${day} 天前"
        else -> "${day / 30} 个月前"
    }
}

private fun iconColorFor(seed: String): androidx.compose.ui.graphics.Color {
    val colors = listOf(
        androidx.compose.ui.graphics.Color(0xFF5856D6),
        androidx.compose.ui.graphics.Color(0xFFFF9500),
        androidx.compose.ui.graphics.Color(0xFF34C759),
        androidx.compose.ui.graphics.Color(0xFF007AFF),
        androidx.compose.ui.graphics.Color(0xFFFF2D55),
        androidx.compose.ui.graphics.Color(0xFFAF52DE)
    )
    if (seed.isEmpty()) return colors.first()
    return colors[kotlin.math.abs(seed.hashCode()) % colors.size]
}
