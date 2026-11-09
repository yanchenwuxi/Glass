package com.glassbox.app.engine

import com.glassbox.app.data.EpisodeGroup

/**
 * 去广告（精简自 TVBox 常见策略）：
 * 1. 过滤线路/分集名中的广告标记
 * 2. 过滤明显广告域名/路径的播放地址
 * 3. 片头自动跳过秒数由设置控制（播放页应用）
 *
 * 不做浏览器网页广告拦截；只处理片源返回的播放列表与直链。
 */
object AdFilter {

    private val flagKeywords = listOf(
        "广告", "推广", "赠片", "推荐", "福利", "加群", "关注",
        "公众号", "防失联", "更新群", "QQ群", "微信", "赞助", "加载中",
        "勿点", "无效", "测试", "预告广告"
    )

    private val urlKeywords = listOf(
        "/ad/", "/ads/", "/advert", "adserver", "doubleclick",
        "googlesyndication", "preroll", "midroll", "ad-cdn",
        "adplay", "adtrack", "adservice"
    )

    fun isAdName(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        return flagKeywords.any { n.contains(it, ignoreCase = true) }
    }

    fun isAdUrl(url: String): Boolean {
        val u = url.lowercase()
        if (u.isBlank()) return false
        return urlKeywords.any { it in u }
    }

    fun filterGroups(groups: List<EpisodeGroup>, enabled: Boolean): List<EpisodeGroup> {
        if (!enabled) return groups
        return groups.mapNotNull { g ->
            if (isAdName(g.title)) return@mapNotNull null
            val eps = g.episodes.filter { !isAdName(it.name) && !isAdUrl(it.url) }
            if (eps.isEmpty()) null else g.copy(episodes = eps)
        }
    }

    fun pickPlayUrl(url: String, enabled: Boolean): String? {
        if (!enabled) return url
        return if (isAdUrl(url)) null else url
    }
}
