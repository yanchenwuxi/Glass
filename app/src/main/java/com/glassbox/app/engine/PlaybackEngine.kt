package com.glassbox.app.engine

import android.view.View

/**
 * 播放内核（对齐 BieCuiLe 1.1.5）：默认 MPV，另可选 ExoPlayer / IJK。
 * 顺序即单集播放失败时的自动降级顺序。
 */
enum class PlaybackKernel(val key: String, val label: String, val order: Int) {
    MPV("mpv", "MPV", 0),
    EXO("exo", "ExoPlayer", 1),
    IJK("ijk", "IJK", 2);

    val tag: String
        get() = when (this) {
            MPV -> "libmpv 0.4.1"
            EXO -> "Media3 ExoPlayer 1.4.1"
            IJK -> "IJK 0.0.26"
        }

    companion object {
        fun fromKey(key: String?): PlaybackKernel = entries.firstOrNull { it.key == key } ?: MPV

        /** 降级顺序 MPV → ExoPlayer → IJK */
        fun next(current: PlaybackKernel): PlaybackKernel? =
            entries.firstOrNull { it.order == current.order + 1 }
    }
}

/** 三个内核共用的播放快照；`renderedFrames` / `fps` / `decoder` 用于证明画面层确有帧输出。 */
data class PlaybackState(
    val kernel: PlaybackKernel = PlaybackKernel.MPV,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val ended: Boolean = false,
    val speed: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val error: String? = null,
    val loaded: Boolean = false,
    val hardwareDecoding: Boolean = true,
    val renderedFrames: Long = 0,
    val fps: Float = 0f,
    val decoder: String = ""
)

data class MediaTrack(val id: Int, val label: String, val selected: Boolean, val kind: String)

/**
 * 画面参数。数值语义对齐 libmpv 的 video equalizer：-100..100，0 为原样；
 * [sharpen] 用 0..100 的百分比（0 为关闭），由各内核自行换算到原生单位。
 */
data class PictureSettings(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val gamma: Float = 0f,
    val hue: Float = 0f,
    val sharpen: Float = 0f
) {
    val isDefault: Boolean
        get() = brightness == 0f && contrast == 0f && saturation == 0f &&
            gamma == 0f && hue == 0f && sharpen == 0f
}

/**
 * 内核画面调节能力。不支持的项在面板里禁用并给出 [note]，
 * 避免出现「点了没效果」的空入口。
 */
data class PictureCapability(
    val supported: Boolean,
    val brightness: Boolean = supported,
    val contrast: Boolean = supported,
    val saturation: Boolean = supported,
    val gamma: Boolean = supported,
    val hue: Boolean = supported,
    val sharpen: Boolean = supported,
    val note: String = ""
)

/**
 * 画面比例档位。前 3 档按原版 BieCuiLe 1.1.5 的顺序（适应 → 拉伸 → 裁剪），
 * 另按需求追加 16:9 / 4:3 两个固定比例档。
 */
object VideoAspect {
    const val FIT = 0
    const val STRETCH = 1
    const val CROP = 2
    const val RATIO_16_9 = 3
    const val RATIO_4_3 = 4

    val labels = listOf("适应", "拉伸", "裁剪", "16:9", "4:3")

    fun next(current: Int): Int = (current + 1) % labels.size

    fun label(mode: Int): String = labels.getOrElse(mode) { labels[0] }

    /** 固定比例档的目标宽高比；null 表示按视频原始比例。 */
    fun forcedRatio(mode: Int): Float? = when (mode) {
        RATIO_16_9 -> 16f / 9f
        RATIO_4_3 -> 4f / 3f
        else -> null
    }
}

interface PlaybackEngine {
    val kernel: PlaybackKernel

    /** 交给 Compose AndroidView 的宿主 View（MPV/IJK 为 SurfaceView，ExoPlayer 为 PlayerView）。 */
    val view: View

    /**
     * [forceHls] 由起播前的 MediaProbe 给出：当响应体是 #EXTM3U 但地址后缀/Content-Type
     * 伪装成图片（常见 `.png` + image/png 的防盗链写法）时，必须强制按 HLS 解，
     * 否则 MPV 会走图片 demuxer，把播放列表当成一张 1 秒的静态图。
     */
    fun play(url: String, startMs: Long = 0, headers: Map<String, String> = emptyMap(), forceHls: Boolean = false)

    /** M3U8 去广告热切换：尽量保持进度，默认等同 play。 */
    fun hotSwap(url: String, startMs: Long, headers: Map<String, String> = emptyMap(), forceHls: Boolean = true) {
        play(url, startMs, headers, forceHls)
    }
    fun pause()
    fun resume()
    fun togglePause()
    fun seekTo(ms: Long)
    fun setSpeed(value: Float)
    fun setLoop(enabled: Boolean)
    fun setAspect(mode: Int)
    fun setHardwareDecoding(enabled: Boolean)

    fun audioTracks(): List<MediaTrack>
    fun subtitleTracks(): List<MediaTrack>
    fun selectAudio(id: Int)
    fun selectSubtitle(id: Int)
    fun disableAudio()
    fun disableSubtitle()
    fun currentAudioId(): Int
    fun currentSubtitleId(): Int

    /**
     * 外挂字幕文件（本地路径）。MPV 走 `sub-add`，ExoPlayer 走 SubtitleConfiguration；
     * IJK 没有可用的外挂字幕接口，返回 false 由上层如实提示。
     */
    fun addSubtitleFile(path: String): Boolean = false

    /** libmpv 自带截图；其它内核由 PixelCopy 抓 SurfaceView。 */
    fun screenshot(path: String): Boolean = false

    /** 当前内核的画面调节能力；默认不支持，由各内核覆写。 */
    fun pictureCapability(): PictureCapability =
        PictureCapability(supported = false, note = "当前内核没有画面调节接口")

    /**
     * 下发画面参数，返回是否真正被内核接受。支持的项由内核立即生效；
     * 不支持的项由面板按 [pictureCapability] 置灰，不会出现“点了没效果”。
     */
    fun applyPicture(settings: PictureSettings): Boolean = false

    fun currentPositionMs(): Long
    fun currentDurationMs(): Long
    fun release()
}
