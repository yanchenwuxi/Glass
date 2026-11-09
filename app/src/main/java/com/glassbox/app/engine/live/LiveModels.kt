package com.glassbox.app.engine.live

import com.glassbox.app.core.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * 直播数据模型 —— 从 TVBox OSC 的 LiveChannelGroup / LiveChannelItem / EPG 抽取并 Kotlin 化。
 */
data class LiveChannelGroup(
    val groupName: String,
    val channels: List<LiveChannelItem> = emptyList(),
    val password: String? = null
)

data class LiveChannelItem(
    val name: String,
    val urls: List<String> = emptyList(),
    val logo: String? = null,
    val epgId: String? = null,
    val groupIndex: Int = 0,
    val channelIndex: Int = 0,
    var sourceIndex: Int = 0
) {
    val currentUrl: String?
        get() = urls.getOrNull(sourceIndex) ?: urls.firstOrNull()

    val sourceCount: Int get() = urls.size
}

data class LiveEpgProgram(
    val title: String,
    val startTime: String,
    val endTime: String,
    val description: String? = null
)

data class LiveEpgDay(
    val dateLabel: String,
    val programs: List<LiveEpgProgram> = emptyList()
)

interface LiveSourceParser {
    suspend fun parse(content: String, baseUrl: String? = null): List<LiveChannelGroup>
    suspend fun parseUrl(url: String): List<LiveChannelGroup>
    suspend fun loadEpg(epgId: String, date: String? = null): List<LiveEpgDay>
}

/**
 * 支持：
 * - M3U / M3U8（#EXTINF）
 * - TVBox 风格 TXT：`分组名,#genre#` 后跟 `频道名,url` 或 `频道名,url1#url2`
 */
class M3uLiveSourceParser : LiveSourceParser {

    override suspend fun parse(content: String, baseUrl: String?): List<LiveChannelGroup> =
        withContext(Dispatchers.Default) {
            val text = content.trim()
            if (text.isEmpty()) return@withContext emptyList()
            if (text.startsWith("#EXTM3U", ignoreCase = true) || text.contains("#EXTINF", ignoreCase = true)) {
                parseM3u(text, baseUrl)
            } else {
                parseTxt(text, baseUrl)
            }
        }

    override suspend fun parseUrl(url: String): List<LiveChannelGroup> = withContext(Dispatchers.IO) {
        val body = runCatching { Net.get(url) }.getOrNull() ?: return@withContext emptyList()
        parse(body, url)
    }

    override suspend fun loadEpg(epgId: String, date: String?): List<LiveEpgDay> = emptyList()

    private fun parseM3u(text: String, baseUrl: String?): List<LiveChannelGroup> {
        val groups = linkedMapOf<String, MutableList<LiveChannelItem>>()
        var currentGroup = "默认"
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingEpg: String? = null

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#EXTM3U", true)) return@forEach
            if (line.startsWith("#EXTGRP:", true)) {
                currentGroup = line.substringAfter(':').trim().ifBlank { "默认" }
                return@forEach
            }
            if (line.startsWith("#EXTINF:", true)) {
                val meta = line.substringAfter(':')
                val comma = meta.indexOf(',')
                val attrs = if (comma >= 0) meta.substring(0, comma) else meta
                pendingName = if (comma >= 0) meta.substring(comma + 1).trim() else "未命名"
                pendingLogo = attr(attrs, "tvg-logo") ?: attr(attrs, "logo")
                pendingEpg = attr(attrs, "tvg-id") ?: attr(attrs, "tvg-name")
                attr(attrs, "group-title")?.let { currentGroup = it }
                return@forEach
            }
            if (line.startsWith("#")) return@forEach
            val url = resolveUrl(line, baseUrl) ?: return@forEach
            val name = pendingName ?: url.substringAfterLast('/').ifBlank { "频道" }
            val list = groups.getOrPut(currentGroup) { mutableListOf() }
            val existing = list.indexOfFirst { it.name == name }
            if (existing >= 0) {
                val old = list[existing]
                list[existing] = old.copy(urls = (old.urls + url).distinct())
            } else {
                list += LiveChannelItem(
                    name = name,
                    urls = listOf(url),
                    logo = pendingLogo,
                    epgId = pendingEpg,
                    groupIndex = groups.keys.indexOf(currentGroup),
                    channelIndex = list.size
                )
            }
            pendingName = null
            pendingLogo = null
            pendingEpg = null
        }
        return groups.entries.mapIndexed { gi, (name, ch) ->
            LiveChannelGroup(
                groupName = name,
                channels = ch.mapIndexed { ci, c -> c.copy(groupIndex = gi, channelIndex = ci) }
            )
        }
    }

    private fun parseTxt(text: String, baseUrl: String?): List<LiveChannelGroup> {
        val groups = linkedMapOf<String, MutableList<LiveChannelItem>>()
        var currentGroup = "默认"
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) return@forEach
            if (line.contains(",#genre#", ignoreCase = true) || line.endsWith("#genre#", true)) {
                currentGroup = line.substringBefore(',').trim().ifBlank {
                    line.removeSuffix("#genre#").trim()
                }.ifBlank { "默认" }
                groups.putIfAbsent(currentGroup, mutableListOf())
                return@forEach
            }
            val sep = line.indexOf(',')
            if (sep <= 0) return@forEach
            val name = line.substring(0, sep).trim()
            val urlPart = line.substring(sep + 1).trim()
            if (name.isEmpty() || urlPart.isEmpty()) return@forEach
            val urls = urlPart.split('#', '|')
                .mapNotNull { resolveUrl(it.trim(), baseUrl) }
                .distinct()
            if (urls.isEmpty()) return@forEach
            val list = groups.getOrPut(currentGroup) { mutableListOf() }
            val existing = list.indexOfFirst { it.name == name }
            if (existing >= 0) {
                val old = list[existing]
                list[existing] = old.copy(urls = (old.urls + urls).distinct())
            } else {
                list += LiveChannelItem(
                    name = name,
                    urls = urls,
                    groupIndex = groups.keys.indexOf(currentGroup),
                    channelIndex = list.size
                )
            }
        }
        return groups.entries.mapIndexed { gi, (name, ch) ->
            LiveChannelGroup(
                groupName = name,
                channels = ch.mapIndexed { ci, c -> c.copy(groupIndex = gi, channelIndex = ci) }
            )
        }
    }

    private fun attr(attrs: String, key: String): String? {
        val re = Regex(
            """$key\s*=\s*"([^"]*)"|$key\s*=\s*'([^']*)'|$key\s*=\s*([^\s,]+)""",
            RegexOption.IGNORE_CASE
        )
        val m = re.find(attrs) ?: return null
        return (m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
            ?: m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
            ?: m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() })
            ?.trim()
    }

    private fun resolveUrl(raw: String, baseUrl: String?): String? {
        val u = raw.trim()
        if (u.isEmpty()) return null
        if (u.startsWith("http://", true) || u.startsWith("https://", true) ||
            u.startsWith("rtmp://", true) || u.startsWith("rtsp://", true)
        ) return u
        if (baseUrl.isNullOrBlank()) return u
        return runCatching { URI(baseUrl).resolve(u).toString() }.getOrNull() ?: u
    }
}
