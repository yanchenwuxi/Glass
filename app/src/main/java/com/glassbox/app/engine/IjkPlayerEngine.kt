package com.glassbox.app.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.misc.ITrackInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IJK 内核（`tv.danmaku.ijk.media:ijkplayer-java:0.8.8` + `ijkplayer-arm64:0.8.8`）。
 *
 * 说明：0.8.8 是 bilibili 官方最后一个发行版，社区已不再维护；工程内没有引入 GSY 等
 * 二次封装（其内部同时打包 ExoPlayer/IJK，会与本工程的 Media3 链路重复）。详见 README 支持矩阵。
 */
class IjkPlayerEngine(
    context: Context,
    private val onSnapshot: (PlaybackState) -> Unit
) : PlaybackEngine, SurfaceHolder.Callback {

    override val kernel: PlaybackKernel = PlaybackKernel.IJK

    private val surfaceView: SurfaceView = SurfaceView(context).apply {
        holder.addCallback(this@IjkPlayerEngine)
    }

    /**
     * IJK 0.x 的 Java/native 层都没有画面比例接口（已用 javap + so 字符串确认），
     * 这里用宿主层补偿：SurfaceView 默认把缓冲拉伸铺满容器（等价「拉伸」），
     * 其余档位通过对 SurfaceView 做等比缩放 + 容器裁剪来实现（不重建 Surface，避免 IJK 崩溃）。
     */
    private val host: FrameLayout = FrameLayout(context).apply {
        clipChildren = true
        clipToPadding = true
        addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyAspect() }
    }

    override val view: View get() = host

    private val player = IjkMediaPlayer()
    private val handler = Handler(Looper.getMainLooper())
    private var released = false
    private var surfaceReady = false

    private var pendingUrl: String? = null
    private var pendingStart = 0L
    private var pendingHeaders: Map<String, String> = emptyMap()
    private var pendingForceHls = false

    private var positionMs = 0L
    private var durationMs = 0L
    private var playing = false
    private var buffering = true
    private var ended = false
    private var loaded = false
    /** 是否已 prepare 过；切换横竖屏会让 SurfaceView 重建，此时只重挂 surface，不重启播放。 */
    private var prepared = false
    private var speed = 1f
    private var videoW = 0
    private var videoH = 0
    private var error: String? = null
    private var frames = 0L
    private var fps = 0f
    private var decoder = ""
    private var hwdec = true
    private var loop = false
    private var selectedAudio = -1
    private var selectedSub = -1
    private var aspectMode = VideoAspect.FIT

    private val poller = object : Runnable {
        override fun run() {
            if (released) return
            syncFromPlayer()
            handler.postDelayed(this, 400)
        }
    }

    init {
        loadNativeOnce()
        player.setOnPreparedListener { mp ->
            loaded = true
            prepared = true
            error = null
            buffering = false
            durationMs = mp.duration.coerceAtLeast(0)
            videoW = mp.videoWidth
            videoH = mp.videoHeight
            if (speed != 1f) runCatching { player.setSpeed(speed) }
            if (pendingStart > 0) mp.seekTo(pendingStart)
            mp.start()
            emit()
        }
        player.setOnCompletionListener {
            ended = true
            playing = false
            emit()
        }
        player.setOnErrorListener { _, what, extra ->
            error = "IJK 错误码 $what/$extra"
            loaded = false
            buffering = false
            emit()
            true
        }
        player.setOnInfoListener(object : IMediaPlayer.OnInfoListener {
            override fun onInfo(mp: IMediaPlayer?, what: Int, extra: Int): Boolean {
                when (what) {
                    IMediaPlayer.MEDIA_INFO_BUFFERING_START -> buffering = true
                    IMediaPlayer.MEDIA_INFO_BUFFERING_END -> buffering = false
                }
                emit()
                return false
            }
        })
        player.setOnVideoSizeChangedListener { mp, width, height, _, _ ->
            videoW = width
            videoH = height
            durationMs = mp.duration.coerceAtLeast(0)
            // 视频尺寸决定了「适应/裁剪」的目标比例，尺寸确定后立即重算宿主层缩放。
            applyAspect()
            emit()
        }
        handler.post(poller)
    }

    override fun play(url: String, startMs: Long, headers: Map<String, String>, forceHls: Boolean) {
        if (released) return
        val hadSession = prepared || loaded
        error = null
        loaded = false
        prepared = false
        ended = false
        buffering = true
        frames = 0
        fps = 0f
        pendingUrl = url
        pendingStart = startMs.coerceAtLeast(0)
        pendingHeaders = headers
        pendingForceHls = forceHls
        if (!surfaceReady) {
            emit()
            return
        }
        if (hadSession) {
            // 切集：先 stop() 停住 ff_read，等旧流缓冲释放后再 reset + 新源 prepare。
            // 直接 reset 正在播放的实例会让 ff_read 线程 SIGBUS（真机复现 BUS_ADRALN）。
            runCatching {
                player.setDisplay(null)
                player.stop()
            }
            emit()
            handler.postDelayed({
                if (!released && surfaceReady && pendingUrl != null) {
                    runCatching { player.setDisplay(surfaceView.holder) }
                    startPrepare()
                }
            }, 450)
            return
        }
        startPrepare()
    }

    private fun startPrepare() {
        val url = pendingUrl ?: return
        runCatching {
            player.reset()
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", if (hwdec) 1L else 0L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0L)
            // 防盗链站点会把 m3u8 放在 .png/image/png 地址上，ffmpeg 会按后缀猜成图片；
            // 探测确认是 #EXTM3U 时显式指定 hls 解复用器，并放开 http/tls 协议。
            if (pendingForceHls) {
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "iformat", "hls")
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "http,https,tcp,tls,crypto")
            }
            player.setLooping(loop)
            player.setDataSource(url, pendingHeaders.ifEmpty { null })
            player.setDisplay(surfaceView.holder)
            player.prepareAsync()
        }.onFailure {
            error = "IJK 初始化失败：${it.message}"
            emit()
        }
    }

    override fun pause() {
        runCatching { player.pause() }
        playing = false
        emit()
    }

    override fun resume() {
        runCatching { player.start() }
        playing = true
        emit()
    }

    override fun togglePause() {
        // IJK 播完后 start() 无效，需要先 seek 回 0 再起播。
        if (ended) {
            ended = false
            seekTo(0)
            resume()
            return
        }
        if (playing) pause() else resume()
    }

    override fun seekTo(ms: Long) {
        runCatching { player.seekTo(ms.coerceAtLeast(0)) }
        positionMs = ms.coerceAtLeast(0)
        emit()
    }

    override fun setSpeed(value: Float) {
        speed = value
        // native 层在 prepare 之前调用 setSpeed 会解引用空指针（SIGSEGV），只在 loaded 后下发；
        // 起播时的倍速由 onPrepared 回调补发。
        if (loaded) runCatching { player.setSpeed(value) }
        emit()
    }

    override fun setLoop(enabled: Boolean) {
        loop = enabled
        runCatching { player.setLooping(enabled) }
    }

    override fun setAspect(mode: Int) {
        aspectMode = mode
        applyAspect()
    }

    /**
     * 宿主层比例控制（IJK 0.0.16 没有原生 ratio 接口，且实测 SurfaceView 的 scaleX/scaleY
     * 不会作用于视频 surface 图层）。因此改为调整 SurfaceView 在宿主 FrameLayout 中的尺寸：
     * - 适应 / 16:9 / 4:3：按目标比例内接于容器（IJK 自身也保持视频比例，不裁切）；
     * - 裁剪：按目标比例放大到铺满容器，超出部分由宿主 clipChildren 裁掉；
     * - 拉伸：IJK 内部始终按视频比例渲染，无原生拉伸能力，本档与「适应」一致（如实记录在 README）。
     */
    private fun applyAspect() {
        val cw = host.width
        val ch = host.height
        if (cw <= 0 || ch <= 0) return
        val containerRatio = cw.toFloat() / ch
        val videoRatio = if (videoW > 0 && videoH > 0) videoW.toFloat() / videoH else 0f
        val target = VideoAspect.forcedRatio(aspectMode) ?: videoRatio.takeIf { it > 0f } ?: return
        var w: Int
        var h: Int
        val crop = aspectMode == VideoAspect.CROP
        if (crop) {
            // 铺满容器：取能覆盖容器的较小尺寸，多出的一边交给宿主裁剪。
            if (target < containerRatio) {
                w = cw
                h = (cw / target).toInt()
            } else {
                h = ch
                w = (ch * target).toInt()
            }
        } else {
            // 内接容器：按宽度或高度中更紧的一边收敛，留黑边。
            if (target > containerRatio) {
                w = cw
                h = (cw / target).toInt()
            } else {
                h = ch
                w = (ch * target).toInt()
            }
        }
        val lp = surfaceView.layoutParams as? FrameLayout.LayoutParams
        if (lp != null && lp.width == w && lp.height == h) return
        surfaceView.layoutParams = FrameLayout.LayoutParams(w, h, android.view.Gravity.CENTER)
    }

    override fun setHardwareDecoding(enabled: Boolean) {
        hwdec = enabled
        emit()
    }

    /**
     * IJK 0.0.16 的 ffmpeg 里没有编译 eq/color 视频滤镜（已在 libijkffmpeg.so 中核对：
     * 无 brightness/contrast/saturation/eq/unsharp 等滤镜符号），`setOption` 也没有画面参数类别；
     * 画面直接输出到 SurfaceView，宿主层 ColorFilter 不会作用到 surface 图层。
     * 因此这里如实标注不支持，由面板引导用户切到 MPV，而不是做一个无效果的滑杆。
     */
    override fun pictureCapability(): PictureCapability = PictureCapability(
        supported = false,
        note = "IJK 内核的 ffmpeg 未编译 eq/color 画面滤镜，SurfaceView 也无法叠加 ColorFilter；" +
            "画面调节请切换 MPV 内核（本面板不提供无效果的空滑杆）"
    )

    override fun audioTracks(): List<MediaTrack> = readTracks(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO, "audio")

    override fun subtitleTracks(): List<MediaTrack> =
        readTracks(ITrackInfo.MEDIA_TRACK_TYPE_SUBTITLE, "sub") +
            readTracks(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT, "sub")

    private fun readTracks(type: Int, kind: String): List<MediaTrack> {
        val info = runCatching { player.trackInfo }.getOrNull() ?: return emptyList()
        val result = mutableListOf<MediaTrack>()
        info.forEachIndexed { index, track ->
            if (track.trackType != type) return@forEachIndexed
            val label = track.infoInline?.takeIf { it.isNotBlank() }
                ?: track.language?.takeIf { it.isNotBlank() }
                ?: if (kind == "audio") "音轨 ${result.size + 1}" else "字幕 ${result.size + 1}"
            val selected = if (kind == "audio") index == selectedAudio else index == selectedSub
            result.add(MediaTrack(index, label, selected, kind))
        }
        return result
    }

    override fun selectAudio(id: Int) {
        runCatching {
            if (selectedAudio >= 0) player.deselectTrack(selectedAudio)
            player.selectTrack(id)
            selectedAudio = id
        }
        emit()
    }

    override fun selectSubtitle(id: Int) {
        runCatching {
            if (selectedSub >= 0) player.deselectTrack(selectedSub)
            player.selectTrack(id)
            selectedSub = id
        }
        emit()
    }

    override fun disableAudio() {
        runCatching { if (selectedAudio >= 0) player.deselectTrack(selectedAudio) }
        selectedAudio = -1
        emit()
    }

    override fun disableSubtitle() {
        runCatching { if (selectedSub >= 0) player.deselectTrack(selectedSub) }
        selectedSub = -1
        emit()
    }

    override fun currentAudioId(): Int = selectedAudio
    override fun currentSubtitleId(): Int = selectedSub

    override fun currentPositionMs(): Long =
        runCatching { player.currentPosition }.getOrDefault(positionMs).coerceAtLeast(0)

    override fun currentDurationMs(): Long =
        runCatching { player.duration }.getOrDefault(durationMs).coerceAtLeast(0)

    private fun syncFromPlayer() {
        runCatching {
            positionMs = player.currentPosition.coerceAtLeast(0)
            durationMs = player.duration.coerceAtLeast(0)
            playing = player.isPlaying
            fps = player.videoOutputFramesPerSecond
            if (fps > 0f) frames = maxOf(frames, 1L)
            decoder = when (player.videoDecoder) {
                IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC -> "mediacodec"
                IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC -> "ffmpeg"
                else -> decoder
            }
        }
        emit()
    }

    private fun emit() {
        if (released) return
        onSnapshot(
            PlaybackState(
                kernel = PlaybackKernel.IJK,
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
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        runCatching { player.setDisplay(holder) }
        if (!prepared && pendingUrl != null) {
            // 与 setDisplay 同一帧内立刻 prepare 会让部分 IJK so 在 ff_read 线程崩溃，延后一拍更稳。
            handler.postDelayed({
                if (!released && !prepared && pendingUrl != null && surfaceReady) startPrepare()
            }, 250)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        applyAspect()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        runCatching { player.setDisplay(null) }
    }

    override fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        runCatching {
            player.setDisplay(null)
            player.reset()
            player.release()
        }
    }

    companion object {
        private val nativeLoaded = AtomicBoolean(false)

        /** IJK 的 so 需要显式 profileBegin，且全进程只初始化一次。 */
        fun loadNativeOnce() {
            if (!nativeLoaded.compareAndSet(false, true)) return
            runCatching {
                IjkMediaPlayer.loadLibrariesOnce(null)
                IjkMediaPlayer.native_profileBegin("libijkplayer.so")
            }
        }
    }
}
