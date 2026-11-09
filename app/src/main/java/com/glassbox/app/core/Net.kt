package com.glassbox.app.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * 网络层：超时预算、幂等 GET 重试、响应编码嗅探（UTF-8 / GBK / GB18030）、
 * 同 URL 并发去重与短时缓存。对应 02 规范 §6.1。
 */
object Net {
    const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val CONNECT_TIMEOUT = 6L
    private const val READ_TIMEOUT = 10L
    private const val CALL_TIMEOUT = 12L

    @Volatile
    private var _client: OkHttpClient = buildClient(null)

    val client: OkHttpClient get() = _client

    private fun buildClient(proxy: Proxy?): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT, TimeUnit.SECONDS)
            .followRedirects(true)
        if (proxy != null) b.proxy(proxy)
        return b.build()
    }

    /** 应用或清除 HTTP 代理（设置页修改后立即生效）。 */
    fun applyHttpProxy(enabled: Boolean, host: String, port: Int) {
        _client = if (enabled && host.isNotBlank() && port in 1..65535) {
            buildClient(Proxy(Proxy.Type.HTTP, InetSocketAddress(host.trim(), port)))
        } else {
            buildClient(null)
        }
    }

    fun clearMemoryCache() {
        synchronized(cache) { cache.clear() }
        synchronized(inflight) { inflight.clear() }
    }

    private val mutex = Mutex()
    private val inflight = mutableMapOf<String, CompletableDeferred<String>>()
    private val cache = LinkedHashMap<String, CacheEntry>()
    private data class CacheEntry(val text: String, val at: Long)

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), retries: Int = 1): String {
        val key = cacheKey(url, headers)
        return singleFlight(key) {
            var lastError: Throwable? = null
            repeat(retries + 1) { attempt ->
                try {
                    return@singleFlight fetchOnce(url, headers)
                } catch (t: Throwable) {
                    lastError = t
                    if (attempt < retries) delay(if (attempt == 0) 400L else 1200L)
                }
            }
            throw lastError ?: IllegalStateException("请求失败")
        }
    }

    suspend fun getCached(url: String, headers: Map<String, String> = emptyMap(), ttlMs: Long = 3 * 60_000L): String {
        val key = cacheKey(url, headers)
        val now = System.currentTimeMillis()
        mutex.withLock {
            cache[key]?.let { entry ->
                if (now - entry.at <= ttlMs) return entry.text
            }
        }
        val text = get(url, headers)
        mutex.withLock {
            cache[key] = CacheEntry(text, now)
            while (cache.size > 80) {
                val first = cache.entries.firstOrNull() ?: break
                cache.remove(first.key)
            }
        }
        return text
    }

    private suspend fun singleFlight(key: String, block: suspend () -> String): String {
        var owner = false
        val deferred = mutex.withLock {
            val existing = inflight[key]
            if (existing != null) existing
            else CompletableDeferred<String>().also {
                inflight[key] = it
                owner = true
            }
        }
        if (!owner) return deferred.await()
        try {
            val value = block()
            deferred.complete(value)
            return value
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            mutex.withLock { inflight.remove(key) }
        }
    }

    private suspend fun fetchOnce(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(normalizeUrl(url))
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, application/xml, */*")
            headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("空响应")
                if (body.contentLength() > 8L * 1024 * 1024) throw IOException("响应超过 8MB")
                val bytes = body.bytes()
                if (bytes.isEmpty()) throw IOException("响应内容为空")
                decode(bytes, resp.header("Content-Type")).trim()
            }
        }

    suspend fun post(url: String, body: String, contentType: String = "application/json", headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(normalizeUrl(url))
                .header("User-Agent", UA)
                .post(body.toRequestBody(contentType.toMediaType()))
            headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val bytes = resp.body?.bytes() ?: throw IOException("空响应")
                decode(bytes, resp.header("Content-Type")).trim()
            }
        }

    /** 编码优先级：响应头 charset → BOM → XML/HTML 声明 → UTF-8 合法则用 → GB18030 兜底。 */
    fun decode(bytes: ByteArray, contentType: String?): String {
        val fromHeader = contentType?.let {
            Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.get(1)
        }
        val charset = runCatching { fromHeader?.let { Charset.forName(it) } }.getOrNull()
            ?: bomCharset(bytes)
            ?: declaredCharset(bytes)
            ?: if (isValidUtf8(bytes)) StandardCharsets.UTF_8 else Charset.forName("GB18030")
        val text = runCatching {
            bytes.toString(charset)
        }.getOrElse {
            bytes.toString(StandardCharsets.UTF_8)
        }
        return text.removePrefix("\uFEFF").trimStart('\uFEFF')
    }

    private fun bomCharset(bytes: ByteArray): Charset? {
        if (bytes.size < 3) return null
        return when {
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                StandardCharsets.UTF_8
            else -> null
        }
    }

    private fun declaredCharset(bytes: ByteArray): Charset? {
        val head = bytes.copyOfRange(0, minOf(bytes.size, 512)).toString(StandardCharsets.ISO_8859_1)
        val name = Regex("encoding\\s*=\\s*[\"']([A-Za-z0-9_\\-]+)[\"']", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)
            ?: Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
                .find(head)?.groupValues?.get(1)
        return runCatching { name?.let { Charset.forName(it) } }.getOrNull()
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (t: CharacterCodingException) {
        false
    }

    fun normalizeUrl(url: String): String {
        val u = url.trim()
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
    }

    fun originOf(url: String): String = runCatching {
        val u = java.net.URI(normalizeUrl(url))
        "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
    }.getOrDefault("")

    fun joinUrl(base: String, path: String): String {
        if (path.isBlank()) return base
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val host = originOf(base).ifBlank { base.trimEnd('/') }
        return if (path.startsWith("/")) host + path else "$host/$path"
    }

    private fun cacheKey(url: String, headers: Map<String, String>): String =
        normalizeUrl(url) + "#" + headers.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" }
}
