package com.glassbox.app.engine.local

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale

enum class LocalEntryKind { DIR, VIDEO, AUDIO, OTHER }

data class LocalEntry(
    val name: String,
    val path: String,
    val uri: String? = null,
    val kind: LocalEntryKind,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L,
    val isSaf: Boolean = false
) {
    val isDirectory: Boolean get() = kind == LocalEntryKind.DIR
    val isPlayable: Boolean get() = kind == LocalEntryKind.VIDEO || kind == LocalEntryKind.AUDIO
}

object LocalMediaExt {
    val VIDEO = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "mpg", "mpeg", "3gp", "rmvb", "rm"
    )
    val AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "ape")
    val PLAYLIST = setOf("m3u", "m3u8")

    fun kindOf(name: String): LocalEntryKind {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when {
            ext in VIDEO || ext in PLAYLIST -> LocalEntryKind.VIDEO
            ext in AUDIO -> LocalEntryKind.AUDIO
            else -> LocalEntryKind.OTHER
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }
}

class LocalFileBrowser(private val context: Context) {

    fun defaultRoots(): List<LocalEntry> {
        val roots = mutableListOf<LocalEntry>()
        val external = Environment.getExternalStorageDirectory()
        if (external != null && external.exists()) {
            roots += LocalEntry(
                name = "内部存储",
                path = external.absolutePath,
                kind = LocalEntryKind.DIR,
                lastModified = external.lastModified()
            )
            val movie = File(external, "Movies")
            if (movie.isDirectory) {
                roots += LocalEntry("Movies", movie.absolutePath, kind = LocalEntryKind.DIR, lastModified = movie.lastModified())
            }
            val download = File(external, "Download")
            if (download.isDirectory) {
                roots += LocalEntry("Download", download.absolutePath, kind = LocalEntryKind.DIR, lastModified = download.lastModified())
            }
            val dcim = File(external, "DCIM")
            if (dcim.isDirectory) {
                roots += LocalEntry("DCIM", dcim.absolutePath, kind = LocalEntryKind.DIR, lastModified = dcim.lastModified())
            }
        }
        context.getExternalFilesDir(null)?.let { app ->
            roots += LocalEntry("应用目录", app.absolutePath, kind = LocalEntryKind.DIR, lastModified = app.lastModified())
        }
        return roots.distinctBy { it.path }
    }

    fun listPath(path: String): List<LocalEntry> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { !it.name.startsWith(".") }
            .map { f ->
                when {
                    f.isDirectory -> LocalEntry(
                        name = f.name,
                        path = f.absolutePath,
                        kind = LocalEntryKind.DIR,
                        lastModified = f.lastModified()
                    )
                    else -> LocalEntry(
                        name = f.name,
                        path = f.absolutePath,
                        uri = "file://${f.absolutePath}",
                        kind = LocalMediaExt.kindOf(f.name),
                        sizeBytes = f.length(),
                        lastModified = f.lastModified()
                    )
                }
            }
            .sortedWith(
                compareBy<LocalEntry> { if (it.isDirectory) 0 else 1 }
                    .thenBy { it.name.lowercase(Locale.US) }
            )
    }

    fun listSafTree(treeUri: Uri): List<LocalEntry> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        return listSafDir(root)
    }

    fun listSafDir(doc: DocumentFile): List<LocalEntry> {
        return doc.listFiles()
            .filter { it.name?.startsWith(".") != true }
            .mapNotNull { child ->
                val name = child.name ?: return@mapNotNull null
                when {
                    child.isDirectory -> LocalEntry(
                        name = name,
                        path = child.uri.toString(),
                        uri = child.uri.toString(),
                        kind = LocalEntryKind.DIR,
                        lastModified = child.lastModified(),
                        isSaf = true
                    )
                    else -> LocalEntry(
                        name = name,
                        path = child.uri.toString(),
                        uri = child.uri.toString(),
                        kind = LocalMediaExt.kindOf(name),
                        sizeBytes = child.length(),
                        lastModified = child.lastModified(),
                        isSaf = true
                    )
                }
            }
            .sortedWith(
                compareBy<LocalEntry> { if (it.isDirectory) 0 else 1 }
                    .thenBy { it.name.lowercase(Locale.US) }
            )
    }

    fun parentPath(path: String): String? {
        if (path.startsWith("content://")) return null
        val parent = File(path).parentFile ?: return null
        return parent.absolutePath
    }

    fun canReadExternal(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }
}
