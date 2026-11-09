package com.glassbox.app.engine

import android.util.Log
import com.glassbox.app.core.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 起播前地址探测：先确认「拿到的到底是不是媒体流」。
 * `PARSING_CONTAINER_UNSUPPORTED` 通常不是编码不支持，而是把 HTML/JSON/跳转页喂给了内核。
 */
data class MediaProbe(
    val ok: Boolean,
    val finalUrl: String,
    val status: Int,
    val contentType: String,
    val head: String,
    val reason: String,
    /** 响应体真的是 #EXTM3U：即使地址/Content-Type 伪装成图片，也要强制按 HLS 打开。 */
    val isHls: Boolean = false
)

object MediaProbeRunner {

    private val client = Net.client.newBuilder()
        .callTimeout(10, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): MediaProbe =
        withContext(Dispatchers.IO) {
            val h = headers.toMutableMap()
            if (h.keys.none { it.equals("User-Agent", true) }) h["User-Agent"] = Net.UA
            val request = Request.Builder()
                .url(url)
                .get()
                .apply { h.forEach { (k, v) -> header(k, v) } }
                .header("Range", "bytes=0-2047")
                .build()
            val result = runCatching { client.newCall(request).execute() }
            val response = result.getOrNull()
                ?: return@withContext MediaProbe(
                    ok = false, finalUrl = url, status = -1, contentType = "", head = "",
                    reason = "请求失败：${result.exceptionOrNull()?.message}"
                )
            response.use { r ->
                val finalUrl = r.request.url.toString()
                val status = r.code
                val type = r.header("Content-Type").orEmpty()
                val body = r.body?.byteStream()
                val bytes = runCatching { readUpTo(body, 2048) }.getOrDefault(ByteArray(0))
                val text = String(bytes, Charsets.UTF_8)
                val head = text.take(80).replace("\n", " ").replace("\r", " ")
                val clean = text.trimStart('\uFEFF')
                val hls = clean.startsWith("#EXTM3U")
                val reason = when {
                    status !in 200..299 -> "HTTP $status"
                    looksLikePage(text, type) -> "返回的是网页/接口而不是媒体流（$type）"
                    hls -> segmentFailure(finalUrl, text, h).let { it ?: "" }
                    else -> ""
                }
                val ok = reason.isEmpty()
                Log.i(
                    "GlassBoxPlayer",
                    "起播探测 url=$url → final=$finalUrl status=$status type=$type " +
                        "bytes=${bytes.size} isHls=$hls head=$head headers=$h"
                )
                MediaProbe(ok, finalUrl, status, type, head, reason, isHls = hls)
            }
        }

    /**
     * HLS 只探到 m3u8 是不够的：m3u8 常见两种情况——列表正常但分片被 CDN/防盗链拒绝（403），
     * 此时内核只能报「无画面」或容器解析失败，看起来像解码问题，实际是地址层问题。
     * 这里按 m3u8 里的第一条分片做一次只读 128 字节的探测，把真实状态码暴露出来。
     */
    private fun segmentFailure(baseUrl: String, playlist: String, headers: Map<String, String>): String? {
        val first = playlist.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val segUrl = runCatching { java.net.URI(baseUrl).resolve(first).toString() }.getOrElse { first }
        val req = Request.Builder().url(segUrl).get()
            .header("Range", "bytes=0-127")
            .apply { headers.forEach { (k, v) -> if (!k.equals("Range", true)) header(k, v) } }
            .build()
        return runCatching {
            client.newCall(req).execute().use { sr ->
                val code = sr.code
                val type = sr.header("Content-Type").orEmpty()
                val bytes = readUpTo(sr.body?.byteStream(), 128)
                Log.i("GlassBoxPlayer", "起播探测·分片 status=$code type=$type bytes=${bytes.size} url=$segUrl")
                if (code in 200..299) null
                else "HLS 分片被拒绝 HTTP $code（m3u8 本身正常，属片源 CDN/防盗链层；分片地址=$segUrl）"
            }
        }.getOrElse { "HLS 分片请求异常：${it.message}（分片地址=$segUrl）" }
    }

    private fun readUpTo(input: java.io.InputStream?, limit: Int): ByteArray {
        if (input == null) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(512)
        while (out.size() < limit) {
            val n = input.read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, minOf(n, limit - out.size()))
        }
        return out.toByteArray()
    }

    private fun looksLikePage(text: String, contentType: String): Boolean {
        val t = text.trimStart('\uFEFF', '\u0000', '\uFEFF')
        if (t.startsWith("#EXTM3U")) return false
        val lower = contentType.lowercase()
        if (lower.startsWith("text/html") || lower.contains("text/xml") || lower.contains("json")) return true
        if (t.startsWith("<!DOCTYPE") || t.startsWith("<html") || t.startsWith("<?xml")) return true
        if (t.startsWith("{") || t.startsWith("[")) return true
        if (lower.startsWith("video/") || lower.startsWith("audio/") || lower.contains("mpegurl")) return false
        return false
    }
}
