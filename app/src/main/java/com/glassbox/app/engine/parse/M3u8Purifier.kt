package com.glassbox.app.engine.parse

import android.util.Log
import java.net.URI
import java.util.Locale
import java.util.regex.Pattern

/**
 * M3U8 去广告 —— 精简自 TVBox `M3u8.purify` 的高收益规则：
 * 1. 广告域名 / 路径 / 切片名特征
 * 2. #EXT-X-CUE-OUT … #EXT-X-CUE-IN 区间
 * 3. VOD：短 DISCONTINUITY 分组（中插贴片常见）
 * 4. 相对路径补成绝对 URL（代理后播放器才能继续拉切片）
 *
 * 安全阀：删除超过总切片 50% 或结果不可播时回退原文。
 */
object M3u8Purifier {

    data class Result(
        val content: String,
        val adCount: Int,
        val originalTotalMs: Long = 0
    )

    private val adUri = Pattern.compile(
        "(?i)(^|[/?&=_.-])(ads?|adv|advert(ise(ment)?)?|commercial|preroll|pre-roll|midroll|mid-roll|postroll|post-roll|sponsor|scte|vast|vmap|interstitial|bumper)([/?&=_.-]|$)"
    )
    private val adPath = Pattern.compile(
        "(?i)(?:/mov/AD/|/ad/|/ads/|adjump|ppvod-ad|ad-injected|/advert/|erlgnf\\.com)"
    )
    private val adDomains = listOf(
        "adservice", "adserver", "adsystem", "doubleclick", "googlesyndication",
        "advertising", "2mdn.net", "moatads", "scorecardresearch", "ad-cdn", "adplay"
    )
    private val adText = Pattern.compile(
        "(?i)(SCTE35|CUE-OUT|CUE-IN|SPLICE|X-AD-|X-ASSET|advertisement|commercial)"
    )

    fun purify(baseUrl: String, raw: String): Result {
        if (raw.isBlank()) return Result(raw, 0)
        var content = raw.trimStart('\uFEFF')
        if (!content.startsWith("#EXTM3U")) return Result(raw, 0)

        val isVod = content.contains("#EXT-X-ENDLIST")
        val totalSeg = countSegments(content)
        var adCount = 0

        // master playlist：只展开相对路径，不做切片级删除
        if (content.contains("#EXT-X-STREAM-INF")) {
            return Result(absolutize(baseUrl, content), 0)
        }

        val afterCue = removeCueRanges(content)
        adCount += afterCue.second
        content = afterCue.first

        val afterUri = removeAdUriLines(baseUrl, content)
        adCount += afterUri.second
        content = afterUri.first

        if (isVod) {
            val afterGroup = removeShortDiscontinuityGroups(content)
            adCount += afterGroup.second
            content = afterGroup.first
        }

        content = absolutize(baseUrl, content)

        if (totalSeg > 0 && adCount > totalSeg * 0.5) {
            Log.w(TAG, "removed too many segments $adCount/$totalSeg, rollback")
            return Result(absolutize(baseUrl, raw.trimStart('\uFEFF')), 0)
        }
        if (adCount > 0 && countSegments(content) == 0) {
            Log.w(TAG, "playlist empty after purify, rollback")
            return Result(absolutize(baseUrl, raw.trimStart('\uFEFF')), 0)
        }

        val totalMs = estimateDurationMs(raw)
        Log.i(TAG, "purify removed=$adCount totalSeg=$totalSeg vod=$isVod")
        return Result(content, adCount, totalMs)
    }

    private fun countSegments(content: String): Int =
        content.lineSequence().count { line ->
            val t = line.trim()
            t.isNotEmpty() && !t.startsWith("#")
        }

    private fun estimateDurationMs(content: String): Long {
        var sum = 0.0
        val p = Pattern.compile("#EXTINF:([\\d.]+)")
        content.lineSequence().forEach { line ->
            val m = p.matcher(line)
            if (m.find()) sum += m.group(1)?.toDoubleOrNull() ?: 0.0
        }
        return (sum * 1000).toLong()
    }

    /** 删除 CUE-OUT 到 CUE-IN（含）之间的全部行。 */
    private fun removeCueRanges(content: String): Pair<String, Int> {
        val lines = content.split('\n')
        val out = ArrayList<String>(lines.size)
        var inCue = false
        var removed = 0
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("#EXT-X-CUE-OUT") || (t.startsWith("#EXT-X-DATERANGE") && t.contains("SCTE35"))) {
                inCue = true
                removed++
                continue
            }
            if (inCue) {
                if (t.startsWith("#EXT-X-CUE-IN")) {
                    inCue = false
                    removed++
                    continue
                }
                if (t.isNotEmpty() && !t.startsWith("#")) removed++
                else if (t.startsWith("#EXTINF")) removed++
                continue
            }
            out += line
        }
        return out.joinToString("\n") to removed
    }

    private fun removeAdUriLines(baseUrl: String, content: String): Pair<String, Int> {
        val lines = content.split('\n')
        val out = ArrayList<String>(lines.size)
        var pendingTags = ArrayList<String>()
        var removed = 0
        fun flushKeep() {
            out.addAll(pendingTags)
            pendingTags = ArrayList()
        }
        fun flushDrop() {
            removed += pendingTags.count { it.trim().startsWith("#EXTINF") }
            pendingTags = ArrayList()
        }
        for (line in lines) {
            val t = line.trim()
            when {
                t.isEmpty() -> {
                    flushKeep()
                    out += line
                }
                t.startsWith("#") -> {
                    if (t.startsWith("#EXTINF") || t.startsWith("#EXT-X-KEY") || t.startsWith("#EXT-X-MAP") ||
                        t.startsWith("#EXT-X-DISCONTINUITY") || adText.matcher(t).find()
                    ) {
                        pendingTags += line
                    } else {
                        flushKeep()
                        out += line
                    }
                }
                else -> {
                    val abs = resolveUrl(baseUrl, t)
                    if (isAdUrl(abs) || isAdUrl(t)) {
                        flushDrop()
                        removed++
                    } else {
                        flushKeep()
                        out += abs
                    }
                }
            }
        }
        flushKeep()
        return out.joinToString("\n") to removed
    }

    /**
     * 按 DISCONTINUITY 切组：主组（切片最多）保留，其余总时长很短的组视为中插广告删除。
     */
    private fun removeShortDiscontinuityGroups(content: String): Pair<String, Int> {
        val lines = content.split('\n')
        data class Group(val lines: MutableList<String> = mutableListOf(), var seg: Int = 0, var dur: Double = 0.0)

        val groups = mutableListOf(Group())
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("#EXT-X-DISCONTINUITY") && groups.last().seg > 0) {
                groups += Group()
            }
            val g = groups.last()
            g.lines += line
            if (t.startsWith("#EXTINF:")) {
                val n = t.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                g.dur += n
            } else if (t.isNotEmpty() && !t.startsWith("#")) {
                g.seg++
            }
        }
        if (groups.size < 3) return content to 0
        val main = groups.maxByOrNull { it.seg } ?: return content to 0
        if (main.seg < 5) return content to 0
        val mainAvg = if (main.seg > 0) main.dur / main.seg else 0.0
        var removed = 0
        val kept = mutableListOf<String>()
        for (g in groups) {
            if (g === main) {
                kept += g.lines
                continue
            }
            // 短组 + 切片少 → 广告；或平均时长与主组差很大且总长短
            val short = g.dur in 0.1..45.0 && g.seg in 1..12
            val avg = if (g.seg > 0) g.dur / g.seg else 0.0
            val weirdAvg = mainAvg > 0 && avg > 0 && (avg < mainAvg * 0.55 || avg > mainAvg * 1.8) && g.dur <= 60
            if (short || weirdAvg) {
                removed += g.seg
            } else {
                kept += g.lines
            }
        }
        return if (removed == 0) content to 0 else kept.joinToString("\n") to removed
    }

    private fun absolutize(baseUrl: String, content: String): String {
        val sb = StringBuilder()
        content.lineSequence().forEach { line ->
            val t = line.trim()
            when {
                t.isEmpty() || t.startsWith("#") -> {
                    // KEY/MAP URI 属性
                    if ((t.startsWith("#EXT-X-KEY") || t.startsWith("#EXT-X-MAP")) && t.contains("URI=\"")) {
                        val uri = t.substringAfter("URI=\"").substringBefore('"')
                        if (uri.isNotBlank() && !uri.startsWith("http", true) && !uri.startsWith("data:", true)) {
                            val abs = resolveUrl(baseUrl, uri)
                            sb.append(t.replace("URI=\"$uri\"", "URI=\"$abs\"")).append('\n')
                        } else sb.append(line).append('\n')
                    } else sb.append(line).append('\n')
                }
                else -> sb.append(resolveUrl(baseUrl, t)).append('\n')
            }
        }
        return sb.toString().trimEnd() + "\n"
    }

    fun isAdUrl(url: String): Boolean {
        val u = url.lowercase(Locale.US)
        if (adUri.matcher(u).find()) return true
        if (adPath.matcher(u).find()) return true
        return adDomains.any { it in u }
    }

    fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) return ref
        return try {
            URI(base).resolve(ref).toString()
        } catch (_: Exception) {
            val b = base.substringBeforeLast('/', base)
            "$b/$ref"
        }
    }

    private const val TAG = "M3u8Purifier"
}
