package com.glassbox.app.engine.live

import com.glassbox.app.core.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * XMLTV EPG 解析（TVBox 直播常用）。
 * 支持 channel id / display-name 匹配，按天分组节目。
 */
class XmltvEpgParser {

    private var cachedUrl: String? = null
    private var cachedAt: Long = 0
    private var channels: Map<String, String> = emptyMap() // id -> display name
    private var programmes: List<RawProgramme> = emptyList()

    data class RawProgramme(
        val channelId: String,
        val startMs: Long,
        val stopMs: Long,
        val title: String,
        val desc: String?
    )

    suspend fun load(url: String, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!force && url == cachedUrl && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS && programmes.isNotEmpty()) {
            return@withContext true
        }
        if (url.isBlank()) return@withContext false
        val body = runCatching { Net.get(url, retries = 1) }.getOrNull() ?: return@withContext false
        parseXml(body)
        cachedUrl = url
        cachedAt = System.currentTimeMillis()
        true
    }

    fun daysForChannel(epgId: String?, channelName: String?): List<LiveEpgDay> {
        val ids = matchChannelIds(epgId, channelName)
        if (ids.isEmpty()) return emptyList()
        val filtered = programmes.filter { it.channelId in ids }.sortedBy { it.startMs }
        if (filtered.isEmpty()) return emptyList()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd E", Locale.CHINA).apply {
            timeZone = TimeZone.getDefault()
        }
        val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA).apply {
            timeZone = TimeZone.getDefault()
        }
        return filtered.groupBy { dayFmt.format(Date(it.startMs)) }
            .map { (day, list) ->
                LiveEpgDay(
                    dateLabel = day,
                    programs = list.map { p ->
                        LiveEpgProgram(
                            title = p.title,
                            startTime = timeFmt.format(Date(p.startMs)),
                            endTime = timeFmt.format(Date(p.stopMs)),
                            description = p.desc
                        )
                    }
                )
            }
    }

    fun nowNext(epgId: String?, channelName: String?): Pair<LiveEpgProgram?, LiveEpgProgram?> {
        val ids = matchChannelIds(epgId, channelName)
        val now = System.currentTimeMillis()
        val list = programmes.filter { it.channelId in ids }.sortedBy { it.startMs }
        val current = list.lastOrNull { it.startMs <= now && now < it.stopMs }
        val next = list.firstOrNull { it.startMs >= now }
            ?: list.firstOrNull { it.startMs > (current?.startMs ?: 0) }
        fun toProg(p: RawProgramme?): LiveEpgProgram? {
            p ?: return null
            val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
            return LiveEpgProgram(
                title = p.title,
                startTime = timeFmt.format(Date(p.startMs)),
                endTime = timeFmt.format(Date(p.stopMs)),
                description = p.desc
            )
        }
        return toProg(current) to toProg(next)
    }

    private fun matchChannelIds(epgId: String?, channelName: String?): Set<String> {
        val result = linkedSetOf<String>()
        if (!epgId.isNullOrBlank()) {
            result += epgId
            channels.entries.filter { it.key.equals(epgId, true) || it.value.equals(epgId, true) }
                .forEach { result += it.key }
        }
        if (!channelName.isNullOrBlank()) {
            channels.entries.filter {
                it.value.equals(channelName, true) ||
                    it.value.contains(channelName, true) ||
                    channelName.contains(it.value, true)
            }.forEach { result += it.key }
        }
        return result
    }

    private fun parseXml(xml: String) {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser().apply { setInput(xml.reader()) }
        val ch = linkedMapOf<String, String>()
        val prog = mutableListOf<RawProgramme>()
        var event = parser.eventType
        var channelId = ""
        var displayName = ""
        var pChannel = ""
        var pStart = ""
        var pStop = ""
        var pTitle = ""
        var pDesc: String? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        channelId = parser.getAttributeValue(null, "id") ?: ""
                        displayName = ""
                    }
                    "display-name" -> if (channelId.isNotEmpty()) {
                        val t = parser.nextText().trim()
                        if (t.isNotEmpty() && displayName.isEmpty()) displayName = t
                    }
                    "programme" -> {
                        pChannel = parser.getAttributeValue(null, "channel") ?: ""
                        pStart = parser.getAttributeValue(null, "start") ?: ""
                        pStop = parser.getAttributeValue(null, "stop") ?: ""
                        pTitle = ""
                        pDesc = null
                    }
                    "title" -> if (pChannel.isNotEmpty()) pTitle = parser.nextText().trim()
                    "desc" -> if (pChannel.isNotEmpty()) pDesc = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> if (channelId.isNotEmpty()) {
                        ch[channelId] = displayName.ifBlank { channelId }
                    }
                    "programme" -> {
                        val start = parseXmltvTime(pStart)
                        val stop = parseXmltvTime(pStop)
                        if (pChannel.isNotEmpty() && start > 0 && pTitle.isNotEmpty()) {
                            prog += RawProgramme(
                                channelId = pChannel,
                                startMs = start,
                                stopMs = if (stop > start) stop else start + 30 * 60_000L,
                                title = pTitle,
                                desc = pDesc
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }
        channels = ch
        programmes = prog
    }

    /** XMLTV time: 20240101120000 +0100 或 20240101120000 */
    private fun parseXmltvTime(raw: String): Long {
        val s = raw.trim()
        if (s.length < 14) return 0L
        val core = s.substring(0, 14)
        val tz = if (s.length >= 18) s.substring(15).replace(" ", "") else null
        return runCatching {
            val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            fmt.timeZone = when {
                tz.isNullOrBlank() -> TimeZone.getDefault()
                tz == "Z" || tz == "+0000" || tz == "-0000" -> TimeZone.getTimeZone("UTC")
                else -> {
                    val custom = TimeZone.getTimeZone("GMT$tz")
                    if (custom.rawOffset == 0 && tz != "+0000") TimeZone.getDefault() else custom
                }
            }
            fmt.parse(core)?.time ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        private const val CACHE_TTL_MS = 30 * 60_000L
    }
}
