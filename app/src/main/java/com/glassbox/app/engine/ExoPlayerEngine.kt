package com.glassbox.app.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * Media3 ExoPlayer 内核。渲染宿主用 PlayerView（`useController = false`），
 * 因此字幕、比例、PiP 均沿用 Media3 原生链路；控制层仍由 Compose 自绘。
 */
@OptIn(UnstableApi::class)
class ExoPlayerEngine(
    private val context: Context,
    private val onSnapshot: (PlaybackState) -> Unit
) : PlaybackEngine {

    override val kernel: PlaybackKernel = PlaybackKernel.EXO

    private val playerView = PlayerView(context).apply {
        useController = false
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    override val view: View get() = playerView

    /** PlayerView 内部的 AspectRatioFrameLayout：固定 16:9 / 4:3 档位靠它强制宽高比。 */
    private val contentFrame: AspectRatioFrameLayout? =
        playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame)

    private val player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private var released = false

    private var positionMs = 0L
    private var durationMs = 0L
    private var playing = false
    private var buffering = true
    private var ended = false
    private var loaded = false
    private var speed = 1f
    private var videoW = 0
    private var videoH = 0
    private var error: String? = null
    private var frames = 0L
    private var fps = 0f
    private var decoder = ""
    private var hwdec = true
    private var aspectMode = VideoAspect.FIT
    private var currentUrl: String? = null
    private var picture = PictureSettings()

    private val poller = object : Runnable {
        override fun run() {
            if (released) return
            syncFromPlayer()
            // 固定比例档位在视频尺寸/布局变化时会被 PlayerView 重置，轮询里兜底重下发。
            VideoAspect.forcedRatio(aspectMode)?.let { contentFrame?.setAspectRatio(it) }
            handler.postDelayed(this, 400)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
            emit()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            loaded = playbackState == Player.STATE_READY
            buffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_ENDED) ended = true
            emit()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            videoW = videoSize.width
            videoH = videoSize.height
            // AspectRatioFrameLayout 会随视频尺寸重算，固定比例档位要重新下发。
            applyAspect()
            emit()
        }

        override fun onPlayerError(e: PlaybackException) {
            error = "${e.errorCodeName}：${e.message ?: e.cause?.message.orEmpty()}".trim()
            var c: Throwable? = e
            val stack = StringBuilder()
            while (c != null && stack.length < 400) {
                stack.append(" <- ").append(c.javaClass.simpleName).append(": ").append(c.message)
                c = c.cause
            }
            Log.e("GlassBoxPlayer", "EXO 播放失败 url=$currentUrl$stack")
            emit()
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            frames = maxOf(frames, 1L)
            emit()
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            decoder = decoderName
            emit()
        }
    }

    init {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("GlassBox/1.0.8 (ExoPlayer)")
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
        val dataSource = DefaultDataSource.Factory(context, http)
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        // media3 要求：效果管线必须在 prepare() 之前建立一次（空列表即可），
        // 否则播放中再 setVideoEffects 不会生效（画面滑杆会「点了没反应」）。
        runCatching { player.setVideoEffects(emptyList()) }
            .onFailure { Log.e("GlassBoxPlayer", "EXO 初始化效果管线失败：${it.message}") }
        playerView.player = player
        handler.post(poller)
    }

    override fun play(url: String, startMs: Long, headers: Map<String, String>, forceHls: Boolean) {
        if (released) return
        error = null
        loaded = false
        ended = false
        buffering = true
        frames = 0
        fps = 0f
        // Media3 默认会自动选中第一条文本轨，HLS 测试流的内嵌字幕会盖在 Compose 控制层上；
        // 原版（mpv）默认不挂字幕，这里起播时先禁用文本轨，用户在“字幕”面板选择时再打开。
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        currentUrl = url
        // m3u8 的地址常以 ?path=xxx.m3u8 结尾，URI 扩展名推断会失效，
        // Media3 于是按普通流去嗅探，得到 PARSING_CONTAINER_UNSUPPORTED。
        // 这里按地址显式指定 MIME，并把请求头真正接到 HTTP 数据源上。
        val lower = url.lowercase()
        val mime = when {
            forceHls || lower.contains(".m3u8") || lower.contains("mpegurl") -> MimeTypes.APPLICATION_M3U8
            lower.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("GlassBox/1.0.8 (ExoPlayer)")
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
            .setDefaultRequestProperties(headers)
        val dataSource = DefaultDataSource.Factory(context, http)
        val builder = MediaItem.Builder().setUri(url)
        if (mime != null) builder.setMimeType(mime)
        val mediaSource = DefaultMediaSourceFactory(dataSource).createMediaSource(builder.build())
        Log.i("GlassBoxPlayer", "EXO 起播 url=$url mime=${mime ?: "auto"} headers=$headers")
        player.setMediaSource(mediaSource, startMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
        applyAspect()
        emit()
    }

    override fun pause() {
        player.playWhenReady = false
    }

    override fun resume() {
        player.playWhenReady = true
    }

    override fun togglePause() {
        // 播完后直接 play() 不会重播，必须先回到 0（否则按钮变成“点了没反应”）。
        if (ended) {
            ended = false
            positionMs = 0L
            player.seekTo(0)
            player.play()
            emit()
            return
        }
        if (playing) player.pause() else player.play()
    }

    override fun seekTo(ms: Long) {
        player.seekTo(ms.coerceAtLeast(0))
        positionMs = ms.coerceAtLeast(0)
        emit()
    }

    override fun setSpeed(value: Float) {
        speed = value
        player.setPlaybackParameters(PlaybackParameters(value))
        emit()
    }

    override fun setLoop(enabled: Boolean) {
        player.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    override fun setAspect(mode: Int) {
        aspectMode = mode
        applyAspect()
    }

    /**
     * ExoPlayer 真正的比例控制：
     * 适应/拉伸/裁剪分别对应 AspectRatioFrameLayout 的 FIT/FILL/ZOOM；
     * 16:9 / 4:3 用 FIT + `setAspectRatio()` 强制宽高比（视频尺寸变化后重下发）。
     */
    private fun applyAspect() {
        val forced = VideoAspect.forcedRatio(aspectMode)
        if (forced != null) {
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            // PlayerView 会在 onVideoSizeChanged 里用视频原始比例覆盖 contentFrame，
            // 所以固定比例档位必须在它之后再下发一次（post 到下一个消息周期）。
            contentFrame?.post { if (!released && VideoAspect.forcedRatio(aspectMode) != null) contentFrame.setAspectRatio(forced) }
            android.util.Log.i("GlassBoxPlayer", "EXO setAspect mode=$aspectMode fixed=$forced resize=FIT")
            return
        }
        // 不能把 contentFrame 的宽高比设成 0：AspectRatioFrameLayout 在 ratio<=0 且未收到
        // 视频尺寸回调时会退化成「铺满」，实测 FIT 会像 FILL 一样把 16:9 拉满 20:9 屏幕。
        // 这里显式下发音视频原始比例；视频尺寸未知时它会在 onVideoSizeChanged 再下发一次。
        val natural = if (videoW > 0 && videoH > 0) videoW.toFloat() / videoH else 0f
        contentFrame?.setAspectRatio(natural)
        playerView.resizeMode = when (aspectMode) {
            VideoAspect.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            VideoAspect.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        android.util.Log.i(
            "GlassBoxPlayer",
            "EXO setAspect mode=$aspectMode fixed=none resize=${playerView.resizeMode} " +
                "frame=${contentFrame?.width}x${contentFrame?.height} video=${videoW}x$videoH"
        )
    }

    override fun addSubtitleFile(path: String): Boolean {
        val url = currentUrl ?: return false
        val file = File(path)
        if (!file.isFile) return false
        val mime = when (file.extension.lowercase()) {
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            "ttml" -> MimeTypes.APPLICATION_TTML
            "smi", "sami" -> MimeTypes.APPLICATION_SUBRIP
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val wasPlaying = player.playWhenReady
        val position = player.currentPosition.coerceAtLeast(0)
        // 外挂字幕需要重新 prepare；保留当前进度与播放状态。
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        val item = MediaItem.Builder()
            .setUri(url)
            .setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                        .setMimeType(mime)
                        .setLanguage("und")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
            .build()
        player.setMediaItem(item, position)
        player.prepare()
        player.playWhenReady = wasPlaying
        applyAspect()
        return true
    }

    override fun setHardwareDecoding(enabled: Boolean) {
        // Media3 默认走 MediaCodec（硬解），没有软件解码回退开关。
        hwdec = enabled
        emit()
    }

    /**
     * Media3 不能像 mpv 一样直接改解码器色彩，但 `ExoPlayer.setVideoEffects`（media3-effect）
     * 可以在输出前插入 GL 效果链：RgbAdjustment（亮度）、Contrast（对比度）、
     * HslAdjustment（饱和度/色调）。伽玛和锐化没有现成效果，如实禁用。
     */
    override fun pictureCapability(): PictureCapability = PictureCapability(
        supported = true,
        gamma = false,
        sharpen = false,
        note = "ExoPlayer 通过 media3-effect 的 RgbAdjustment / Contrast / HslAdjustment " +
            "实时改亮度、对比度、饱和度、色相；伽马没有对应 GL 效果，已禁用（MPV 完整支持）"
    )

    override fun applyPicture(settings: PictureSettings): Boolean {
        if (released) return false
        picture = settings
        val effects = mutableListOf<Effect>()
        if (settings.brightness != 0f) {
            // 亮度按 RGB 增益近似：-100 → 全黑，0 → 原样，+100 → 2×。
            val scale = (1f + settings.brightness / 100f).coerceIn(0f, 2f)
            effects.add(
                RgbAdjustment.Builder()
                    .setRedScale(scale)
                    .setGreenScale(scale)
                    .setBlueScale(scale)
                    .build()
            )
        }
        if (settings.contrast != 0f) {
            effects.add(Contrast((settings.contrast / 100f).coerceIn(-1f, 1f)))
        }
        if (settings.saturation != 0f || settings.hue != 0f) {
            effects.add(
                HslAdjustment.Builder()
                    .adjustSaturation(settings.saturation.coerceIn(-100f, 100f))
                    // mpv 的 hue 是 -100..100，这里换算成 -180..180 度。
                    .adjustHue((settings.hue * 1.8f).coerceIn(-180f, 180f))
                    .build()
            )
        }
        runCatching { player.setVideoEffects(effects) }
            .onFailure {
                Log.e("GlassBoxPlayer", "EXO setVideoEffects 失败：${it.message}")
                return false
            }
        Log.i(
            "GlassBoxPlayer",
            "EXO applyPicture brightness=${settings.brightness} contrast=${settings.contrast} " +
                "saturation=${settings.saturation} hue=${settings.hue} effects=${effects.size}"
        )
        return true
    }

    override fun audioTracks(): List<MediaTrack> = readTracks(C.TRACK_TYPE_AUDIO, 1000, "audio")

    override fun subtitleTracks(): List<MediaTrack> = readTracks(C.TRACK_TYPE_TEXT, 2000, "sub")

    private fun readTracks(type: Int, base: Int, kind: String): List<MediaTrack> {
        val tracks = runCatching { player.currentTracks }.getOrNull() ?: return emptyList()
        val result = mutableListOf<MediaTrack>()
        var groupIndex = 0
        tracks.groups.forEach { group ->
            if (group.type != type) {
                groupIndex++
                return@forEach
            }
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = format.label?.takeIf { it.isNotBlank() }
                    ?: format.language?.takeIf { it.isNotBlank() }
                    ?: if (kind == "audio") "音轨 ${result.size + 1}" else "字幕 ${result.size + 1}"
                result.add(
                    MediaTrack(base + groupIndex * 100 + i, label, group.isTrackSelected(i), kind)
                )
            }
            groupIndex++
        }
        return result
    }

    private fun overrideTrack(id: Int, type: Int) {
        val base = if (type == C.TRACK_TYPE_AUDIO) 1000 else 2000
        val groupIndex = (id - base) / 100
        val trackIndex = (id - base) % 100
        val group = runCatching { player.currentTracks.groups.filter { it.type == type }[groupIndex] }
            .getOrNull() ?: return
        val params = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
        player.trackSelectionParameters = params
    }

    override fun selectAudio(id: Int) = overrideTrack(id, C.TRACK_TYPE_AUDIO)
    override fun selectSubtitle(id: Int) = overrideTrack(id, C.TRACK_TYPE_TEXT)

    override fun disableAudio() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
    }

    override fun disableSubtitle() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    override fun currentAudioId(): Int = selectedId(1000, C.TRACK_TYPE_AUDIO)
    override fun currentSubtitleId(): Int = selectedId(2000, C.TRACK_TYPE_TEXT)

    private fun selectedId(base: Int, type: Int): Int {
        val groups = runCatching { player.currentTracks.groups }.getOrNull() ?: return 0
        var groupIndex = 0
        groups.forEach { group ->
            if (group.type != type) {
                groupIndex++
                return@forEach
            }
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) return base + groupIndex * 100 + i
            }
            groupIndex++
        }
        return 0
    }

    override fun currentPositionMs(): Long =
        runCatching { player.currentPosition }.getOrDefault(positionMs)

    override fun currentDurationMs(): Long =
        runCatching { player.duration }.getOrDefault(durationMs).coerceAtLeast(0)

    private fun syncFromPlayer() {
        runCatching {
            positionMs = player.currentPosition.coerceAtLeast(0)
            durationMs = player.duration.coerceAtLeast(0)
            playing = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING
            loaded = loaded || player.playbackState == Player.STATE_READY
        }
        emit()
    }

    private fun emit() {
        if (released) return
        val state = PlaybackState(
            kernel = PlaybackKernel.EXO,
            positionMs = positionMs,
            durationMs = durationMs,
            playing = playing,
            buffering = buffering && !ended,
            ended = ended,
            speed = speed,
            videoWidth = videoW,
            videoHeight = videoH,
            error = error,
            loaded = loaded,
            hardwareDecoding = hwdec,
            renderedFrames = frames,
            fps = fps,
            decoder = decoder
        )
        onSnapshot(state)
    }

    override fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        runCatching {
            playerView.player = null
            player.release()
        }
    }
}
