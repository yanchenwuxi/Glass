package com.glassbox.app.data

object DemoContent {
    // 示例素材使用可公开访问、国内网络可达的地址（Google 存储桶在部分网络不可达）
    private const val MUX_HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    private const val APPLE_HLS =
        "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8"
    private const val W3_MP4 = "https://media.w3.org/2010/05/sintel/trailer.mp4"
    const val SITE_KEY = "glassDemo"
    const val SITE_NAME = "示例放映厅"
    const val SOURCE_ID = "builtin_json_demo"

    private fun img(seed: String, w: Int = 400, h: Int = 600) =
        "https://picsum.photos/seed/glass-$seed/$w/$h"

    private data class Entry(
        val id: String,
        val title: String,
        val image: String,
        val year: String,
        val area: String,
        val type: String,
        val remarks: String,
        val score: String,
        val desc: String,
        val actors: String,
        val director: String,
        val hls: String? = null,
        val episodes: List<Pair<String, String>>? = null
    )

    private val entries = listOf(
        Entry(
            "bbb", "大雄兔", "BigBuckBunny", "2008", "美国", "动画短片", "编辑精选", "9.4",
            "一只体型庞大却性情温和的兔子，被三只顽皮的小动物反复挑衅。当底线被触碰，它用一场精心设计的反击，把森林变成了自己的舞台。这是 Blender 开放电影计划的代表作，也是无数播放器测试片源的原点。",
            "Big Buck Bunny 团队", "Sacha Goedegebure",
            hls = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        ),
        Entry(
            "sintel", "辛特尔", "Sintel", "2010", "荷兰", "动画短片", "高分佳作", "9.2",
            "少女辛特尔为寻找失散的伙伴，独自穿越荒原与雪山。她遇见一只受伤的龙，也遇见了自己不愿承认的真相。画面与配乐都带着手绘的粗粝温度。",
            "Halina Reijn, Thom Hoffman", "Colin Levy",
            hls = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        ),
        Entry(
            "tears", "钢铁之泪", "TearsOfSteel", "2012", "荷兰", "科幻短片", "真人实拍", "8.8",
            "未来世界，人类与机器人争夺最后的生存空间。一场关于记忆、牺牲与和解的战争，在废墟与钢铁之间缓慢展开。Blender 开放电影计划的真人实拍实验。",
            "Derek de Lint, Sergio Hasselbaink", "Ian Hubert"
        ),
        Entry(
            "elephants", "大象之梦", "ElephantsDream", "2006", "荷兰", "实验短片", "先锋影像", "8.4",
            "两个男人在一座不断自我重构的机械宫殿中穿行。现实与梦境彼此侵蚀，语言逐渐失去意义。开放电影运动的第一部作品，粗粝却极具野心。",
            "Tygo Gernandt, Cas Jansen", "Bassam Kurdali"
        ),
        Entry(
            "blazes", "更炽热的火焰", "ForBiggerBlazes", "2015", "美国", "广告短片", "短小精悍", "7.9",
            "把大屏幕搬进客厅的几分钟里，火焰、爆炸与追逐轮番登场。适合用来快速检验播放器的解码与色彩表现。",
            "Google 示例影片", "Google Creative Lab"
        ),
        Entry(
            "escapes", "更远的逃离", "ForBiggerEscapes", "2015", "美国", "广告短片", "节奏轻快", "7.8",
            "一场说走就走的逃离，从城市天际线滑向海岸。镜头语言干净利落，是测试画面流畅度的轻量片源。",
            "Google 示例影片", "Google Creative Lab"
        ),
        Entry(
            "fun", "更大的乐趣", "ForBiggerFun", "2015", "美国", "广告短片", "合家欢", "7.6",
            "把日常的琐碎变成一场小小的冒险。色彩明亮，配乐轻快，适合在闲暇时随手播放。",
            "Google 示例影片", "Google Creative Lab"
        ),
        Entry(
            "joyrides", "更尽兴的驰骋", "ForBiggerJoyrides", "2015", "美国", "广告短片", "速度感", "8.0",
            "方向盘、引擎与弯道。短促的剪辑带来强烈的速度感，也是检验运动补偿的好素材。",
            "Google 示例影片", "Google Creative Lab"
        ),
        Entry(
            "meltdown", "更彻底的崩坏", "ForBiggerMeltdowns", "2015", "美国", "广告短片", "荒诞喜剧", "7.7",
            "当一切都开始失控，主角选择用最荒谬的方式解决问题。短短几分钟，情绪从紧绷滑向爆笑。",
            "Google 示例影片", "Google Creative Lab"
        ),
        Entry(
            "subaru", "斯巴鲁 Outback：公路与尘土", "SubaruOutbackOnStreetAndDirt", "2015", "美国", "汽车纪录", "实景测试", "8.2",
            "从城市街道到碎石山路，一台旅行车被推到极限。底盘、四驱与轮胎的每一次反馈，都被镜头诚实记录。",
            "Subaru 测试团队", "Google Creative Lab"
        ),
        Entry(
            "gti", "大众 GTI 深度试驾", "VolkswagenGTIReview", "2015", "美国", "汽车纪录", "机械美学", "8.1",
            "一辆小钢炮的日常与激情。试驾者用近乎偏执的细节，讲述它为何能在几十年里持续被人喜爱。",
            "试驾团队", "Google Creative Lab"
        ),
        Entry(
            "bullrun", "我们要去 Bullrun", "WeAreGoingOnBullrun", "2015", "美国", "公路纪录", "热血公路", "7.9",
            "一群人为一场横跨大陆的公路拉力赛做准备。改装、争执、期待，全部压缩在出发前的几十个小时里。",
            "Bullrun 车队", "Google Creative Lab"
        ),
        Entry(
            "grand", "一千块能买到什么车", "WhatCarCanYouGetForAGrand", "2015", "美国", "汽车纪录", "趣味实验", "7.8",
            "预算只有一千块，却能买到怎样的车？一场带着幽默感的二手车探险，结局比想象中体面。",
            "二手车小队", "Google Creative Lab"
        ),
        Entry(
            "series_demo", "三集小剧场", "SeriesDemo", "2026", "中国", "连载剧集", "切集验证", "—",
            "内置的连载样本：三集分别指向不同的公开测试流，专门用于验证三个播放内核在切集后能否重新起播并正常出帧。",
            "示例演员", "GlassBox",
            episodes = listOf(
                "第一集 · Mux HLS" to "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                "第二集 · Apple HLS" to "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8",
                "第三集 · Sintel MP4" to "https://media.w3.org/2010/05/sintel/trailer.mp4"
            )
        )
    )

    val streamItems = listOf(
        MediaItem(
            id = "stream_bipbop",
            title = "Apple BipBop 多码率测试流",
            poster = img("stream-bipbop"),
            backdrop = img("stream-bipbop-wide", 800, 450),
            remarks = "HLS 自适应",
            year = "2024",
            area = "流媒体",
            typeName = "HLS 实验室",
            score = "—",
            siteKey = SITE_KEY,
            siteName = SITE_NAME,
            sourceId = SOURCE_ID
        ),
        MediaItem(
            id = "stream_mux",
            title = "Mux 1080P 测试流",
            poster = img("stream-mux"),
            backdrop = img("stream-mux-wide", 800, 450),
            remarks = "HLS 点播",
            year = "2024",
            area = "流媒体",
            typeName = "HLS 实验室",
            score = "—",
            siteKey = SITE_KEY,
            siteName = SITE_NAME,
            sourceId = SOURCE_ID
        )
    )

    fun allItems(): List<MediaItem> = entries.map { toItem(it) } + streamItems

    private fun toItem(e: Entry): MediaItem = MediaItem(
        id = e.id,
        title = e.title,
        poster = img(e.id),
        backdrop = img(e.id + "-wide", 800, 450),
        remarks = e.remarks,
        year = e.year,
        area = e.area,
        typeName = e.type,
        score = e.score,
        siteKey = SITE_KEY,
        siteName = SITE_NAME,
        sourceId = SOURCE_ID
    )

    fun itemById(id: String): MediaItem? = allItems().firstOrNull { it.id == id }

    fun homeSections(): List<HomeSection> {
        val all = allItems()
        return listOf(
            HomeSection(
                "今晚，挑一部好片",
                "示例精选 · 开放版权影像",
                listOf("bbb", "sintel", "tears").mapNotNull { itemById(it) },
                SectionStyle.HERO
            ),
            HomeSection(
                "本期精选",
                "适合一个人安静看完的短片",
                listOf("bbb", "sintel", "tears", "elephants", "subaru", "grand").mapNotNull { itemById(it) }
            ),
            HomeSection(
                "动画与想象",
                "手绘温度与开放电影运动",
                listOf("bbb", "sintel", "elephants", "blazes").mapNotNull { itemById(it) }
            ),
            HomeSection(
                "公路与机械",
                "引擎、尘土与远方",
                listOf("subaru", "gti", "bullrun", "grand", "joyrides").mapNotNull { itemById(it) }
            ),
            HomeSection(
                "HLS 流媒体实验室",
                "自适应码率 · 可直接播放",
                streamItems
            )
        )
    }

    fun detail(id: String): MediaDetail? {
        val item = itemById(id) ?: return null
        if (id == "stream_bipbop" || id == "stream_mux") {
            val url = if (id == "stream_bipbop") APPLE_HLS else MUX_HLS
            return MediaDetail(
                item = item,
                description = "公共 HLS 测试流，用于验证播放器对 m3u8 主清单、分片切换与自适应码率的支持。无需登录，可公开访问。",
                actors = "—",
                director = "—",
                groups = listOf(EpisodeGroup("测试流", listOf(Episode("原始地址", url))))
            )
        }
        val e = entries.firstOrNull { it.id == id } ?: return null
        val groups = if (!e.episodes.isNullOrEmpty()) {
            mutableListOf(
                EpisodeGroup("在线线路 · 多集", e.episodes.map { (name, url) -> Episode(name, url) })
            )
        } else {
            mutableListOf(
                EpisodeGroup("在线线路 · HLS", listOf(Episode("自适应码率", e.hls ?: MUX_HLS)))
            )
        }
        if (e.id == "sintel") {
            groups += EpisodeGroup("备用线路 · MP4", listOf(Episode("预告片", W3_MP4)))
        }
        val related = allItems().filter { it.id != id && it.typeName == item.typeName }.take(4)
            .ifEmpty { allItems().filter { it.id != id }.shuffled().take(4) }
        return MediaDetail(
            item = item,
            description = e.desc,
            actors = e.actors,
            director = e.director,
            groups = groups,
            recommend = related
        )
    }

    fun search(keyword: String): List<MediaItem> {
        val k = keyword.trim()
        if (k.isEmpty()) return emptyList()
        return allItems().filter {
            it.title.contains(k, true) || it.typeName.contains(k, true) ||
                it.area.contains(k, true) || it.remarks.contains(k, true)
        }
    }

    fun category(type: String): List<MediaItem> = allItems().filter { it.typeName == type }

    fun categories(): List<String> = allItems().map { it.typeName }.distinct()

    /** 内置示例源自己的分类目录：全部 + 各类型，并按条目里的年份/地区生成筛选条件。 */
    fun catalog(): SiteCatalog {
        val all = allItems()
        val categories = listOf(SiteCategory(ALL_ID, "全部")) +
            categories().map { SiteCategory(it, it) }
        val filters = categories.associate { c ->
            val items = if (c.id == ALL_ID) all else all.filter { it.typeName == c.id }
            c.id to filterGroups(items)
        }
        return SiteCatalog(categories = categories, filters = filters, fallbackItems = all)
    }

    fun category(type: String, extend: Map<String, String>): List<MediaItem> {
        val base = if (type.isBlank() || type == ALL_ID) allItems() else category(type)
        return base
            .filter { extend["year"].isNullOrBlank() || it.year == extend["year"] }
            .filter { extend["area"].isNullOrBlank() || it.area == extend["area"] }
    }

    private fun filterGroups(items: List<MediaItem>): List<FilterGroup> {
        val years = items.map { it.year }.filter { it.isNotBlank() }.distinct().sortedDescending()
        val areas = items.map { it.area }.filter { it.isNotBlank() }.distinct()
        return listOfNotNull(
            if (years.isEmpty()) null else FilterGroup(
                key = "year",
                name = "年份",
                options = listOf(FilterOption("全部", "")) + years.map { FilterOption(it, it) }
            ),
            if (areas.isEmpty()) null else FilterGroup(
                key = "area",
                name = "地区",
                options = listOf(FilterOption("全部", "")) + areas.map { FilterOption(it, it) }
            )
        )
    }

    private const val ALL_ID = "all"
}
