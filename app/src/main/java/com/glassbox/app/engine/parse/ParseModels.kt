package com.glassbox.app.engine.parse

import com.glassbox.app.core.Net
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 解析链模型 —— 对齐 TVBox ParseBean / SuperParse。
 */
data class ParseDef(
    val name: String,
    val url: String,
    /** 0=拼接型, 1=JSON, 2=JSON 扩展 */
    val type: Int = 0,
    val enabled: Boolean = true,
    val ext: String? = null
)

data class ParseResult(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val parseName: String? = null,
    val fromSniffer: Boolean = false
)

interface ParseExecutor {
    suspend fun parse(parse: ParseDef, playUrl: String, flag: String? = null): ParseResult?

    /** 按顺序/并行尝试多个解析器，返回第一个成功结果。 */
    suspend fun parseFirst(
        parses: List<ParseDef>,
        playUrl: String,
        flag: String? = null,
        parallel: Boolean = true,
        timeoutMs: Long = 12_000L
    ): ParseResult?
}

/**
 * JSON / 拼接解析实现（TVBox 常见约定）：
 * - type 0：`parse.url + playUrl` 再 GET，从正文里抠 http(s) 媒体地址
 * - type 1/2：请求 `parse.url + playUrl`，解析 JSON 的 url / headers 字段
 */
class JsonParseExecutor : ParseExecutor {

    private val mediaSuffix = listOf(
        ".m3u8", ".mp4", ".flv", ".mkv", ".ts", ".mov", ".webm", ".avi", ".m4v"
    )

    override suspend fun parse(parse: ParseDef, playUrl: String, flag: String?): ParseResult? =
        withContext(Dispatchers.IO) {
            if (!parse.enabled || parse.url.isBlank() || playUrl.isBlank()) return@withContext null
            val target = buildRequestUrl(parse.url, playUrl)
            runCatching {
                when (parse.type) {
                    1, 2 -> parseJsonResponse(parse, target)
                    else -> parsePlainResponse(parse, target)
                }
            }.getOrNull()
        }

    override suspend fun parseFirst(
        parses: List<ParseDef>,
        playUrl: String,
        flag: String?,
        parallel: Boolean,
        timeoutMs: Long
    ): ParseResult? {
        val active = parses.filter { it.enabled && it.url.isNotBlank() }
        if (active.isEmpty()) return null
        return if (parallel) {
            coroutineScope {
                val jobs = active.map { p ->
                    async {
                        withTimeoutOrNull(timeoutMs) { parse(p, playUrl, flag) }
                    }
                }
                jobs.awaitAll().firstOrNull { it != null && !it.url.isNullOrBlank() }
            }
        } else {
            for (p in active) {
                val r = withTimeoutOrNull(timeoutMs) { parse(p, playUrl, flag) }
                if (r != null && r.url.isNotBlank()) return r
            }
            null
        }
    }

    private fun buildRequestUrl(parseUrl: String, playUrl: String): String {
        val base = parseUrl.trim()
        return when {
            base.contains("{url}", ignoreCase = true) ->
                base.replace("{url}", java.net.URLEncoder.encode(playUrl, "UTF-8"), ignoreCase = true)
            base.contains("??") -> base.replace("??", playUrl)
            base.endsWith("=") || base.endsWith("/") || base.contains("?") -> base + playUrl
            else -> base + playUrl
        }
    }

    private suspend fun parseJsonResponse(parse: ParseDef, requestUrl: String): ParseResult? {
        val body = Net.get(requestUrl)
        if (body.isBlank()) return null
        val root = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return sniffUrl(body, parse.name)
        val obj = when {
            root.isJsonObject -> root.asJsonObject
            root.isJsonArray && root.asJsonArray.size() > 0 && root.asJsonArray[0].isJsonObject ->
                root.asJsonArray[0].asJsonObject
            else -> return sniffUrl(body, parse.name)
        }
        val url = sequenceOf("url", "src", "play", "video", "data")
            .mapNotNull { key ->
                val el = obj.get(key) ?: return@mapNotNull null
                when {
                    el.isJsonPrimitive -> el.asString
                    el.isJsonObject -> el.asJsonObject.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                    else -> null
                }
            }
            .firstOrNull { !it.isNullOrBlank() && it.startsWith("http") }
            ?: return sniffUrl(body, parse.name)

        val headers = mutableMapOf<String, String>()
        obj.getAsJsonObject("headers")?.entrySet()?.forEach { (k, v) ->
            if (v.isJsonPrimitive) headers[k] = v.asString
        }
        obj.get("header")?.takeIf { it.isJsonPrimitive }?.asString?.let { headers["User-Agent"] = it }
        obj.get("ua")?.takeIf { it.isJsonPrimitive }?.asString?.let { headers["User-Agent"] = it }
        obj.get("referer")?.takeIf { it.isJsonPrimitive }?.asString?.let { headers["Referer"] = it }
        return ParseResult(url = url, headers = headers, parseName = parse.name)
    }

    private suspend fun parsePlainResponse(parse: ParseDef, requestUrl: String): ParseResult? {
        val body = Net.get(requestUrl)
        return sniffUrl(body, parse.name)
    }

    private fun sniffUrl(body: String, parseName: String?): ParseResult? {
        // 优先匹配带常见媒体后缀的 URL
        val regex = Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
        val candidates = regex.findAll(body).map { it.value.trimEnd(')', ']', '"', '\'', ',', '\\') }.toList()
        val preferred = candidates.firstOrNull { c ->
            val u = c.lowercase().substringBefore('?')
            mediaSuffix.any { u.endsWith(it) } || u.contains(".m3u8")
        } ?: candidates.firstOrNull()
        return preferred?.let { ParseResult(url = it, parseName = parseName, fromSniffer = true) }
    }
}
