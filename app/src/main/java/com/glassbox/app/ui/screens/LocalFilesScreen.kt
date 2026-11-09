package com.glassbox.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassbox.app.engine.local.LocalEntry
import com.glassbox.app.engine.local.LocalEntryKind
import com.glassbox.app.engine.local.LocalFileBrowser
import com.glassbox.app.engine.local.LocalMediaExt
import com.glassbox.app.ui.components.GlassIconButton
import com.glassbox.app.ui.theme.GlassTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本地文件浏览：内部存储路径 + SAF 选目录。
 * 可播放的视频/音频点击后回调 onPlay(uriOrPath, title)。
 */
@Composable
fun LocalFilesScreen(
    onBack: () -> Unit,
    onPlay: (url: String, title: String) -> Unit
) {
    val palette = GlassTheme.colors
    val context = LocalContext.current
    val browser = remember { LocalFileBrowser(context) }
    val scope = rememberCoroutineScope()

    var stack by remember { mutableStateOf<List<LocalEntry>>(emptyList()) }
    var entries by remember { mutableStateOf<List<LocalEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var atRoot by remember { mutableStateOf(true) }
    var mediaOnly by remember { mutableStateOf(true) }
    var safMode by remember { mutableStateOf(false) }

    val openTree = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        safMode = true
        atRoot = false
        stack = listOf(
            LocalEntry(
                name = "SAF 目录",
                path = uri.toString(),
                uri = uri.toString(),
                kind = LocalEntryKind.DIR,
                isSaf = true
            )
        )
        scope.launch { loadSaf(browser, uri, mediaOnly) { e, err -> entries = e; error = err } }
    }

    fun loadRoot() {
        atRoot = true
        safMode = false
        stack = emptyList()
        scope.launch {
            loading = true
            error = null
            entries = withContext(Dispatchers.IO) { browser.defaultRoots() }
            if (!browser.canReadExternal() && entries.isEmpty()) {
                error = "需要「所有文件访问权限」才能浏览内部存储，或使用「选文件夹」"
            }
            loading = false
        }
    }

    fun openDir(entry: LocalEntry) {
        atRoot = false
        stack = stack + entry
        scope.launch {
            loading = true
            error = null
            entries = withContext(Dispatchers.IO) {
                if (entry.isSaf || entry.path.startsWith("content://")) {
                    val uri = Uri.parse(entry.path)
                    val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                        ?: androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                    if (doc != null && doc.isDirectory) browser.listSafDir(doc) else emptyList()
                } else {
                    browser.listPath(entry.path)
                }
            }.let { list ->
                if (mediaOnly) list.filter { it.isDirectory || it.isPlayable } else list
            }
            loading = false
        }
    }

    fun goUp() {
        if (stack.isEmpty()) {
            loadRoot()
            return
        }
        if (stack.size == 1) {
            loadRoot()
            return
        }
        stack = stack.dropLast(1)
        val parent = stack.last()
        openDir(parent)
        // openDir pushes again — fix by setting stack then loading without push
    }

    // Fix goUp: reload without pushing
    fun navigateUp() {
        when {
            stack.isEmpty() -> loadRoot()
            stack.size == 1 -> loadRoot()
            else -> {
                val newStack = stack.dropLast(1)
                stack = newStack
                val parent = newStack.last()
                scope.launch {
                    loading = true
                    error = null
                    entries = withContext(Dispatchers.IO) {
                        if (parent.isSaf || parent.path.startsWith("content://")) {
                            val uri = Uri.parse(parent.path)
                            val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                            if (root != null) browser.listSafDir(root) else emptyList()
                        } else {
                            browser.listPath(parent.path)
                        }
                    }.let { list ->
                        if (mediaOnly) list.filter { it.isDirectory || it.isPlayable } else list
                    }
                    loading = false
                }
            }
        }
    }

    LaunchedEffect(Unit) { loadRoot() }

    // re-filter when mediaOnly toggles
    LaunchedEffect(mediaOnly) {
        if (atRoot) return@LaunchedEffect
        val current = stack.lastOrNull() ?: return@LaunchedEffect
        scope.launch {
            loading = true
            entries = withContext(Dispatchers.IO) {
                if (current.isSaf || current.path.startsWith("content://")) {
                    val uri = Uri.parse(current.path)
                    val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                        ?: androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                    if (doc != null && doc.isDirectory) browser.listSafDir(doc) else emptyList()
                } else {
                    browser.listPath(current.path)
                }
            }.let { list ->
                if (mediaOnly) list.filter { it.isDirectory || it.isPlayable } else list
            }
            loading = false
        }
    }

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
                onClick = {
                    if (atRoot) onBack() else navigateUp()
                }
            )
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Outlined.FolderOpen, null, tint = palette.accent, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("本地文件", color = palette.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                val crumb = if (atRoot) "存储根目录" else stack.joinToString(" / ") { it.name }
                Text(
                    crumb,
                    color = palette.textTertiary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChipButton(
                text = if (mediaOnly) "仅媒体" else "全部",
                active = mediaOnly,
                onClick = { mediaOnly = !mediaOnly }
            )
            ChipButton(text = "选文件夹", active = false, onClick = { openTree.launch(null) })
            if (!browser.canReadExternal()) {
                ChipButton(
                    text = "授权存储",
                    active = false,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 30) {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        }
                    }
                )
            }
        }

        if (!error.isNullOrBlank()) {
            Text(
                error ?: "",
                color = palette.iconOrange,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.accent)
            }
            return@Column
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("此目录为空", color = palette.textTertiary, fontSize = 14.sp)
            }
            return@Column
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            items(entries, key = { it.path + it.name }) { entry ->
                FileRow(entry) {
                    when {
                        entry.isDirectory -> openDir(entry)
                        entry.isPlayable -> {
                            val url = entry.uri ?: "file://${entry.path}"
                            onPlay(url, entry.name)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun loadSaf(
    browser: LocalFileBrowser,
    uri: Uri,
    mediaOnly: Boolean,
    done: (List<LocalEntry>, String?) -> Unit
) {
    withContext(Dispatchers.Main) { /* placeholder */ }
    val list = withContext(Dispatchers.IO) {
        runCatching { browser.listSafTree(uri) }.getOrElse { emptyList() }
    }.let { if (mediaOnly) it.filter { e -> e.isDirectory || e.isPlayable } else it }
    done(list, if (list.isEmpty()) "目录为空或无法读取" else null)
}

@Composable
private fun ChipButton(text: String, active: Boolean, onClick: () -> Unit) {
    val palette = GlassTheme.colors
    Text(
        text,
        color = if (active) androidx.compose.ui.graphics.Color.White else palette.textSecondary,
        fontSize = 13.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) palette.accent else palette.chipBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun FileRow(entry: LocalEntry, onClick: () -> Unit) {
    val palette = GlassTheme.colors
    val icon: ImageVector = when (entry.kind) {
        LocalEntryKind.DIR -> Icons.Outlined.Folder
        LocalEntryKind.VIDEO -> Icons.Outlined.Movie
        LocalEntryKind.AUDIO -> Icons.Outlined.AudioFile
        LocalEntryKind.OTHER -> Icons.Outlined.InsertDriveFile
    }
    val tint = when (entry.kind) {
        LocalEntryKind.DIR -> palette.iconBlue
        LocalEntryKind.VIDEO -> palette.accent
        LocalEntryKind.AUDIO -> palette.iconGreen
        LocalEntryKind.OTHER -> palette.textTertiary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                color = palette.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = buildString {
                if (entry.isDirectory) append("文件夹")
                else {
                    append(LocalMediaExt.formatSize(entry.sizeBytes))
                    if (entry.isPlayable) append(" · 可播放")
                }
            }
            if (sub.isNotBlank()) {
                Text(sub, color = palette.textTertiary, fontSize = 11.sp)
            }
        }
        if (entry.isPlayable) {
            Text("播放", color = palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
