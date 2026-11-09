package com.glassbox.app.engine.parse

import android.util.Log
import com.glassbox.app.GlassApp
import com.glassbox.app.core.Net
import com.glassbox.app.server.M3u8ProxyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 拉清单 → 清洗 → 写入本地代理 → 返回可热切换的地址。
 */
object M3u8AdCleaner {

    data class CleanResult(
        val proxyUrl: String?,
        val adCount: Int,
        val message: String
    )

    suspend fun clean(
        playUrl: String,
        headers: Map<String, String> = emptyMap()
    ): CleanResult = withContext(Dispatchers.IO) {
        if (!looksLikeM3u8(playUrl)) {
            return@withContext CleanResult(null, 0, "非 m3u8")
        }
        val body = fetch(playUrl, headers)
            ?: return@withContext CleanResult(null, 0, "清单拉取失败")
        if (!body.trimStart().startsWith("#EXTM3U")) {
            return@withContext CleanResult(null, 0, "不是有效 m3u8")
        }
        // 多码率 master：跟一条子清单再洗
        var workUrl = playUrl
        var workBody = body
        if (body.contains("#EXT-X-STREAM-INF")) {
            val sub = firstVariantUrl(playUrl, body)
            if (sub != null) {
                val subBody = fetch(sub, headers)
                if (subBody != null && subBody.trimStart().startsWith("#EXTM3U")) {
                    workUrl = sub
                    workBody = subBody
                }
            }
        }
        val purified = M3u8Purifier.purify(workUrl, workBody)
        if (purified.adCount <= 0) {
            return@withContext CleanResult(null, 0, "未检测到广告")
        }
        val proxy = ensureProxy()
            ?: return@withContext CleanResult(null, purified.adCount, "代理未启动")
        val token = proxy.put(purified.content)
        val url = proxy.proxyUrl(token)
        Log.i("M3u8AdCleaner", "proxy=$url ads=${purified.adCount}")
        CleanResult(url, purified.adCount, "已移除视频广告 ${purified.adCount} 条")
    }

    private fun looksLikeM3u8(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".m3u8") || u.contains("m3u8?") || u.contains("/hls/")
    }

    private fun fetch(url: String, headers: Map<String, String>): String? {
        return runCatching {
            val req = Request.Builder().url(Net.normalizeUrl(url)).header("User-Agent", Net.UA).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.get().build()
            Net.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.onFailure { Log.w("M3u8AdCleaner", "fetch fail: ${it.message}") }.getOrNull()
    }

    private fun firstVariantUrl(base: String, content: String): String? {
        val lines = content.lineSequence().map { it.trim() }.toList()
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                for (j in (i + 1) until lines.size) {
                    val t = lines[j]
                    if (t.isEmpty()) continue
                    if (t.startsWith("#")) break
                    return M3u8Purifier.resolveUrl(base, t)
                }
            }
        }
        return null
    }

    private fun ensureProxy(): M3u8ProxyServer? {
        val app = runCatching { GlassApp.instance }.getOrNull() ?: return null
        return app.ensureM3u8Proxy()
    }
}
