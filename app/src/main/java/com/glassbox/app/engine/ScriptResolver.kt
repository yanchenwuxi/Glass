package com.glassbox.app.engine

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.glassbox.app.GlassApp
import com.glassbox.app.core.Net
import com.glassbox.app.data.SiteDef
import com.glassbox.app.data.SourceEntity
import java.io.File

/**
 * 站点脚本定位。
 *
 * TVBox 配置里 `api` 很常见是脚本路径（`./js/x.js`、`py/x.py`、`https://host/x.js`），
 * 而不是脚本文本本身。之前 JS/PY 内核一律拿导入的 `source.raw` 当脚本执行，
 * 于是「本地.json 里 56 个 PY 站点」会把整份 JSON 当成 Python 源码，报「脚本未实现 home()」。
 *
 * 这里的规则：
 * 1. `js://` / `py://` / 空 api → 单脚本源，使用导入时保存的脚本文本；
 * 2. api 是 http(s)/`//` → 直接下载；
 * 3. api 是绝对/相对路径 → 相对配置来源解析：
 *    - 在线配置 → 用 URL 作为 base；
 *    - 目录导入 → 用 SAF tree 逐级查找同名文件；
 *    - 单文件导入 → 尝试用同一文档 URI 的父目录（系统只授权了该文件，通常读不到兄弟文件）。
 */
object ScriptResolver {

    suspend fun code(site: SiteDef, source: SourceEntity): String {
        val api = site.api.trim()
        if (api.startsWith("js://") || api.startsWith("py://") || api.isBlank()) {
            return SourceParser.normalizeExport(source.raw)
        }
        val target = site.scriptUrl?.takeIf { it.isNotBlank() } ?: resolve(api, source.url)
            ?: fail("脚本定位失败：api=$api base=${source.url}（配置来源地址为空或格式不支持）")
        val text = try {
            when {
                target.startsWith("tree:") -> readTree(target)
                target.startsWith("http") -> Net.getCached(Net.normalizeUrl(target), ttlMs = 10 * 60_000L)
                target.startsWith("content://") -> readDocument(target)
                target.startsWith("file://") -> readFile(Uri.parse(target).path)
                else -> readFile(target)
            }
        } catch (t: Throwable) {
            Log.e("GlassBoxPlayer", "脚本读取异常：$target", t)
            fail("脚本读取异常：$target · ${t.message ?: t.javaClass.simpleName}")
        }
        if (text.isNullOrBlank()) {
            Log.e("GlassBoxPlayer", "脚本读取为空：api=$api -> $target")
            fail("脚本读取为空：$target（文件不存在、目录授权失效或内容为空）")
        }
        Log.i("GlassBoxPlayer", "脚本定位命中：api=$api -> $target (${text.length} 字节)")
        return SourceParser.normalizeExport(text)
    }

    /**
     * 给 Python/JS 脚本一个「像真实文件」的路径：脚本常用 `os.path.dirname(__file__)`
     * 做自排除或拼同级资源，缺失时原版 Chaquopy 不会报错、我们直接 `NameError`。
     */
    fun logicalPath(api: String, base: String?): String {
        val a = api.trim()
        if (a.startsWith("http")) return a
        if (a.startsWith("/") || a.startsWith("file://")) {
            return SourceParser.collapseDuplicateStorage(a.removePrefix("file://"))
        }
        // 无前导 / 但已是绝对风格：storage/emulated/0/...
        if (a.startsWith("storage/") || a.startsWith("sdcard/")) {
            return SourceParser.collapseDuplicateStorage("/$a")
        }
        val b = base?.trim().orEmpty()
        val root = when {
            b.startsWith("tree:") -> {
                val dir = treeDocumentPath(b.substringBefore('#'))
                val rel = if ('#' in b) b.substringAfter('#').trim('/') else ""
                listOfNotNull(dir, rel.ifBlank { null }).joinToString("/")
            }
            b.startsWith("file://") -> Uri.parse(b).path
            b.startsWith("/") -> b.substringBeforeLast('/').ifBlank { b }
            else -> null
        } ?: return a
        return SourceParser.collapseDuplicateStorage(
            "$root/${a.removePrefix("./")}"
        )
    }

    private fun treeDocumentPath(treeUri: String): String? = runCatching {
        val id = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
        val volume = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        rel.split('/').filter { it.isNotBlank() }.fold(base) { acc, seg -> "$acc/$seg" }
    }.getOrNull()

    /** 定位/读取失败必须可见，不能静默回退成源文本（否则会把 JSON 当 Python/JS 执行）。 */
    private fun fail(message: String): Nothing {
        Log.e("GlassBoxPlayer", message)
        throw IllegalStateException(message)
    }

    /** 把 api 解析成绝对地址；tree 目标编码为 `tree:<treeUri>#<相对路径>`。 */
    private fun resolve(api: String, base: String?): String? {
        if (api.startsWith("//")) return "https:$api"
        if (api.startsWith("http", true)) return api
        if (api.startsWith("/") || api.startsWith("file://")) {
            return SourceParser.collapseDuplicateStorage(api.removePrefix("file://"))
        }
        if (api.startsWith("storage/") || api.startsWith("sdcard/")) {
            return SourceParser.collapseDuplicateStorage("/$api")
        }
        val b = base?.trim().orEmpty()
        if (b.isBlank()) return null
        if (b.startsWith("http", true)) {
            return runCatching {
                java.net.URI(b).resolve(api.removePrefix("./")).toString()
            }.getOrNull()
        }
        if (b.startsWith("tree:")) {
            // base 可能是 `tree:<treeUri>`（整棵目录）或 `tree:<treeUri>#<子目录>`；
            // 没有 '#' 时必须用 "" 作为相对目录，否则会把整段 base 当成相对路径拼进去。
            val treeUri = b.substringBefore('#')
            val rel0 = if ('#' in b) b.substringAfter('#').trim('/') else ""
            val rel = listOf(rel0, api.removePrefix("./")).filter { it.isNotBlank() }.joinToString("/")
            return "$treeUri#$rel"
        }
        if (b.startsWith("content://")) {
            return if (b.contains("/tree/")) "tree:$b#${api.removePrefix("./")}"
            else singleDocumentSibling(b, api)
        }
        val dir: File? = when {
            b.startsWith("file://") -> Uri.parse(b).path?.let { p -> File(p).parentFile }
            else -> File(b).let { if (it.isDirectory) it else it.parentFile }
        }
        return dir?.let { d ->
            SourceParser.collapseDuplicateStorage(
                File(d, api.removePrefix("./")).absolutePath
            )
        }
    }

    /** 单文件授权：documentId 形如 `primary:目录/文件.json`，据此拼出同级文件路径。 */
    private fun singleDocumentSibling(docUri: String, api: String): String? = runCatching {
        val docId = DocumentsContract.getDocumentId(Uri.parse(docUri))
        val path = docId.substringAfter(':')
        val parent = path.substringBeforeLast('/', "")
        val rel = api.removePrefix("./")
        val full = if (parent.isBlank()) rel else "$parent/$rel"
        SourceParser.collapseDuplicateStorage("/storage/emulated/0/$full")
    }.getOrNull()

    private fun readDocument(uri: String): String? = runCatching {
        GlassApp.instance.contentResolver.openInputStream(Uri.parse(uri))?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()

    private fun readFile(path: String?): String? = runCatching {
        path?.let { File(it).takeIf { f -> f.isFile }?.readText(Charsets.UTF_8) }
    }.getOrNull()

    /** 按 `tree:<treeUri>#<a/b.js>` 逐级查找；需要调用方持有 OPEN_DOCUMENT_TREE 的持久授权。 */
    private fun readTree(tag: String): String? {
        val body = tag.removePrefix("tree:")
        val treeUri = Uri.parse(body.substringBefore('#'))
        val rel = body.substringAfter('#')
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (t: Throwable) {
            Log.e("GlassBoxPlayer", "SAF 读取 treeDocId 失败：$treeUri", t)
            return null
        }
        Log.i("GlassBoxPlayer", "SAF tree=$treeUri root=$rootId rel=$rel")
        var docId = rootId
        for (seg in rel.split('/').filter { it.isNotBlank() }) {
            val child = findChild(treeUri, docId, seg)
            if (child == null) {
                Log.e("GlassBoxPlayer", "SAF 未找到子项：$seg（父=$docId）")
                return null
            }
            docId = child
        }
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return try {
            GlassApp.instance.contentResolver.openInputStream(docUri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            Log.e("GlassBoxPlayer", "SAF 打开文档失败：$docUri", t)
            null
        }
    }

    private fun findChild(treeUri: Uri, parentDocId: String, name: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        return try {
            GlassApp.instance.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == name) return c.getString(0)
                }
            }
            null
        } catch (t: Throwable) {
            Log.e("GlassBoxPlayer", "SAF 查询子目录失败：$childrenUri", t)
            null
        }
    }
}
