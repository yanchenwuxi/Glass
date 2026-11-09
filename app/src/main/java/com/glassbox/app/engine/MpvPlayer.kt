package com.glassbox.app.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.jdtech.mpv.MPVLib

/**
 * 原生 libmpv 播放内核（`dev.jdtech.mpv:libmpv:0.4.1`，与 BieCuiLe 的 media_kit/MpvSurfaceView
 * 同源，都是 libmpv + Android Surface）。不使用 Flutter 运行时，直接在 Compose 中承载 SurfaceView。
 *
 * 已验证：arm64-v8a so 随 AAR 打包，minSdk 需 ≥26，APK 增加约 23MB。
 */
class MpvPlayer(
    context: Context,
    private val onSnapshot: (PlaybackState) -> Unit
) : MPVLib.EventObserver, SurfaceHolder.Callback, PlaybackEngine {

    override val kernel: PlaybackKernel = PlaybackKernel.MPV

    // 不要给 SurfaceView 设背景色：View 背景画在窗口图层上，会把下面的视频画面整片遮住。
    override val view: SurfaceView = SurfaceView(context).apply {
        holder.addCallback(this@MpvPlayer)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var surfaceReady = false
    private var pendingUrl: String? = null
    private var pendingStartMs: Long = 0
    private var startApplied = false
    private var released = false

    private var positionMs = 0L
    private var durationMs = 0L
    private var paused = true
    private var pausedForCache = true
    private var ended = false
    private var loaded = false
    private var speed = 1f
    private var videoW = 0
    private var videoH = 0
    private var hwdec = true
    private var error: String? = null
    private var frames = 0L
    private var fps = 0f
    private var decoder = ""
    private var aspectMode = VideoAspect.FIT
    private var surfaceW = 0
    private var surfaceH = 0
    private var picture = PictureSettings()
    private var lastSharpen: Float? = null
    private var lastEmitMs = 0L
    private var pendingEmit = false

    /** 把 libmpv 的错误/警告原样打到 logcat：否则「没有画面」只能看到一个超时文案。 */
    private val logObserver = MPVLib.LogObserver { prefix, level, text ->
        if (level in 1..MPVLib.MPV_LOG_LEVEL_WARN) {
            Log.w("GlassBoxPlayer", "MPV [$prefix/$level] $text")
        }
    }

    init {
        MPVLib.create(context.applicationContext)
        // vo=gpu-next（libplacebo 管线）：旧 vo=gpu 下 equalizer 只有 brightness 生效，
        // 换 gpu-next 后亮度/对比度/饱和度/色相/伽马五项都能实时改画面。
        // gpu-next + 纯 mediacodec 在部分机型上「缓冲结束首帧」会 native 闪退；
        // mediacodec-copy 把硬解帧拷回内存再交给 vo，稳定性明显更好。
        MPVLib.setOptionString("vo", "gpu-next")
        MPVLib.setOptionString("hwdec", "mediacodec-copy")
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,vp8,vp9,av1")
        MPVLib.setOptionString("ao", "audiotrack")
        MPVLib.setOptionString("keep-open", "yes")
        MPVLib.setOptionString("idle", "once")
        MPVLib.setOptionString("force-window", "no")
        // 降低首帧前的 demux 冲击；出错时不要直接 abort 进程
        MPVLib.setOptionString("demuxer-max-bytes", "64M")
        MPVLib.setOptionString("demuxer-max-back-bytes", "32M")
        MPVLib.setOptionString("config", "no")
        MPVLib.setOptionString("user-agent", "GlassBox/1.0.8 (libmpv)")
        MPVLib.setOptionString("msg-level", "all=info")
        MPVLib.addLogObserver(logObserver)
        MPVLib.init()
        MPVLib.addObserver(this)
        MPVLib.observeProperty("time-pos", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("duration", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("paused-for-cache", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("eof-reached", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("speed", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("video-params/w", MPVLib.MPV_FORMAT_INT64)
        MPVLib.observeProperty("video-params/h", MPVLib.MPV_FORMAT_INT64)
        // 画面层出帧证据：已渲染帧数 / 输出帧率 / 当前硬解器
        MPVLib.observeProperty("estimated-frame-count", MPVLib.MPV_FORMAT_INT64)
        MPVLib.observeProperty("estimated-vf-fps", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("hwdec-current", MPVLib.MPV_FORMAT_STRING)
    }

    override fun play(url: String, startMs: Long, headers: Map<String, String>, forceHls: Boolean) {
        if (released) return
        error = null
        loaded = false
        ended = false
        pausedForCache = true
        startApplied = false
        pendingStartMs = startMs
        frames = 0
        fps = 0f
        // 有些站点把 m3u8 放在 .png/image/png 的地址上，mpv 会按扩展名/MIME 走内置 image demuxer，
        // 把整个播放列表当成一张 1 秒静态图（表现就是「时长 1 秒、播一下就结束」）。
        // 统一锁定 lavf demuxer，遇到探测确认的 HLS 再显式指定 hls，绕开后缀伪装。
        runCatching { MPVLib.setPropertyString("demuxer", "lavf") }
        runCatching { MPVLib.setPropertyString("demuxer-lavf-format", if (forceHls) "hls" else "") }
        Log.i("GlassBoxPlayer", "MPV 打开 url=$url forceHls=$forceHls headers=$headers")
        if (headers.isNotEmpty()) {
            val fields = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" }
            MPVLib.setPropertyString("http-header-fields", fields)
        } else {
            // 上一集的请求头会粘在 mpv 实例上，必须显式清空，否则换源时带着旧 Referer。
            MPVLib.setPropertyString("http-header-fields", "")
        }
        if (!surfaceReady) {
            pendingUrl = url
            emit()
            return
        }
        pendingUrl = null
        MPVLib.command(arrayOf("loadfile", url))
        emit()
    }

    override fun pause() {
        if (released) return
        MPVLib.setPropertyBoolean("pause", true)
        Log.i("GlassBoxPlayer", "MPV pause=true（来源：${Thread.currentThread().name}）")
    }

    override fun resume() {
        if (released) return
        MPVLib.setPropertyBoolean("pause", false)
        Log.i("GlassBoxPlayer", "MPV pause=false（来源：${Thread.currentThread().name}）")
    }

    override fun togglePause() {
        if (released) return
        // 播完后单纯取消 pause 不会重播，需要先 seek 回 0。
        if (ended) {
            ended = false
            seekTo(0)
            MPVLib.setPropertyBoolean("pause", false)
            emit()
            return
        }
        MPVLib.setPropertyBoolean("pause", !paused)
    }

    override fun seekTo(ms: Long) {
        if (released) return
        val target = ms.coerceAtLeast(0)
        MPVLib.command(arrayOf("seek", String.format(java.util.Locale.US, "%.3f", target / 1000.0), "absolute+exact"))
        positionMs = target
        emit()
    }

    override fun setSpeed(value: Float) {
        if (released) return
        speed = value
        MPVLib.setPropertyDouble("speed", value.toDouble())
        val read = runCatching { MPVLib.getPropertyString("speed") }.getOrNull()
        Log.i("GlassBoxPlayer", "MPV setSpeed=$value read=$read")
        emit()
    }

    override fun setHardwareDecoding(enabled: Boolean) {
        if (released) return
        hwdec = enabled
        // 运行时下发：对下一集生效；当前文件重新加载后也会走新配置。
        MPVLib.setPropertyString("hwdec", if (enabled) "mediacodec-copy" else "no")
        val read = runCatching { MPVLib.getPropertyString("hwdec") }.getOrNull()
        Log.i("GlassBoxPlayer", "MPV setHardwareDecoding=$enabled read=$read")
        emit()
    }

    override fun setLoop(enabled: Boolean) {
        if (released) return
        MPVLib.setPropertyString("loop-file", if (enabled) "inf" else "no")
        val read = runCatching { MPVLib.getPropertyString("loop-file") }.getOrNull()
        Log.i("GlassBoxPlayer", "MPV setLoop=$enabled read=$read")
    }

    override fun setAspect(mode: Int) {
        if (released) return
        aspectMode = mode
        applyAspect()
    }

    /**
     * libmpv 真正的比例控制：
     * - 适应 / 16:9 / 4:3：`video-aspect-override`（-1 表示按视频原始比例）；
     * - 拉伸（填满）：把 override 设成 Surface 的宽高比，mpv 按该比例填满且不变形留黑（画面被拉伸）；
     * - 裁剪（zoom）：`panscan=1.0`，保持原始比例放大到铺满，超出部分被裁掉。
     */
    private fun applyAspect() {
        if (released) return
        val forced = VideoAspect.forcedRatio(aspectMode)
        val override = when {
            aspectMode == VideoAspect.STRETCH -> {
                val w = surfaceW.takeIf { it > 0 } ?: view.width
                val h = surfaceH.takeIf { it > 0 } ?: view.height
                if (w > 0 && h > 0) String.format(java.util.Locale.US, "%.5f", w.toFloat() / h) else "16:9"
            }
            forced != null -> String.format(java.util.Locale.US, "%.5f", forced)
            else -> "-1"
        }
        // 用 command("set") 而不是 setPropertyString：属性是 float 类型，走命令通道
        // 时由 mpv 自己做字符串→float 转换，避免部分版本对字符串属性直接静默拒绝。
        MPVLib.command(arrayOf("set", "video-aspect-override", override))
        MPVLib.command(arrayOf("set", "panscan", if (aspectMode == VideoAspect.CROP) "1.0" else "0.0"))
        val read = runCatching { MPVLib.getPropertyString("video-aspect-override") }.getOrNull()
        Log.i(
            "GlassBoxPlayer",
            "MPV setAspect mode=$aspectMode override=$override read=$read " +
                "surface=${surfaceW}x$surfaceH view=${view.width}x${view.height}"
        )
    }

    override fun pictureCapability(): PictureCapability = PictureCapability(
        supported = true,
        note = "libplacebo 色彩调节（vo=gpu-next）：亮度/对比度/饱和度/色相/伽马（-100~100，0 为原样），退出播放页后保留"
    )

    /**
     * 画面调节：五项走 libmpv 的 equalizer 属性（brightness/contrast/saturation/gamma/hue）。
     * 真机取证：vo=gpu（旧渲染器）下读回成功但只有 brightness 真正改画面；
     * 换到 libplacebo 管线的 vo=gpu-next 后五项都能实时生效。锐化用 mpv 内置 vf=unsharp
     * （这个构建的 libavfilter 是精简版，没有 eq/hue，但 unsharp 是 mpv 自带滤镜）。
     */
    override fun applyPicture(settings: PictureSettings): Boolean {
        if (released) return false
        picture = settings
        val eq = listOf(
            "brightness" to settings.brightness,
            "contrast" to settings.contrast,
            "saturation" to settings.saturation,
            "gamma" to settings.gamma,
            "hue" to settings.hue
        )
        eq.forEach { (name, value) ->
            MPVLib.command(arrayOf("set", name, String.format(java.util.Locale.US, "%.2f", value)))
        }
        applySharpen(settings.sharpen)
        val readback = eq.joinToString(" ") { (name, _) ->
            "$name=" + runCatching { MPVLib.getPropertyString(name) }.getOrNull().orEmpty()
        }
        Log.i("GlassBoxPlayer", "MPV applyPicture $readback sharpen=${settings.sharpen}")
        return true
    }

    /** 只在锐化值变化时重建 vf，避免滑杆拖动时反复重初始化视频链。 */
    private fun applySharpen(percent: Float) {
        if (lastSharpen != null && lastSharpen == percent) return
        lastSharpen = percent
        // unsharp=5:5:<amount>:5:5:0：只锐化亮度平面，色度不动。
        val vf = if (percent <= 0f) "" else
            String.format(java.util.Locale.US, "unsharp=5:5:%.2f:5:5:0", (percent.coerceIn(0f, 100f) / 100f) * 2f)
        MPVLib.setPropertyString("vf", vf)
    }

    override fun addSubtitleFile(path: String): Boolean = runCatching {
        if (released) return false
        MPVLib.command(arrayOf("sub-add", path, "select"))
        true
    }.getOrDefault(false)

    override fun selectAudio(id: Int) {
        MPVLib.setPropertyInt("aid", id)
    }

    override fun selectSubtitle(id: Int) {
        MPVLib.setPropertyInt("sid", id)
    }

    override fun screenshot(path: String): Boolean {
        return runCatching {
            MPVLib.command(arrayOf("screenshot-to-file", path, "video"))
            true
        }.getOrDefault(false)
    }

    override fun currentPositionMs(): Long = positionMs
    override fun currentDurationMs(): Long = durationMs

    override fun disableSubtitle() {
        if (released) return
        MPVLib.setPropertyString("sid", "no")
    }

    override fun disableAudio() {
        if (released) return
        MPVLib.setPropertyString("aid", "no")
    }

    override fun audioTracks(): List<MediaTrack> = readTracks("audio")
    override fun subtitleTracks(): List<MediaTrack> = readTracks("sub")
    override fun currentAudioId(): Int = runCatching { MPVLib.getPropertyInt("aid") ?: 0 }.getOrDefault(0)
    override fun currentSubtitleId(): Int = runCatching { MPVLib.getPropertyInt("sid") ?: 0 }.getOrDefault(0)

    private fun readTracks(kind: String): List<MediaTrack> {
        if (released) return emptyList()
        val count = runCatching { MPVLib.getPropertyInt("track-list/count") ?: 0 }.getOrDefault(0)
        val result = mutableListOf<MediaTrack>()
        for (i in 0 until count) {
            val type = runCatching { MPVLib.getPropertyString("track-list/$i/type") }.getOrNull() ?: continue
            if (type != kind) continue
            val id = runCatching { MPVLib.getPropertyInt("track-list/$i/id") ?: -1 }.getOrDefault(-1)
            val lang = runCatching { MPVLib.getPropertyString("track-list/$i/lang") }.getOrNull().orEmpty()
            val title = runCatching { MPVLib.getPropertyString("track-list/$i/title") }.getOrNull().orEmpty()
            val selected = runCatching { MPVLib.getPropertyBoolean("track-list/$i/selected") ?: false }
                .getOrDefault(false)
            val label = title.ifBlank { lang }.ifBlank {
                if (kind == "audio") "音轨 ${result.size + 1}" else "字幕 ${result.size + 1}"
            }
            result.add(MediaTrack(id, label, selected, kind))
        }
        Log.i("GlassBoxPlayer", "MPV 轨道 kind=$kind count=$count -> ${result.map { "${it.id}:${it.label}" }}")
        return result
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        MPVLib.attachSurface(holder.surface)
        MPVLib.setPropertyString("force-window", "yes")
        val pending = pendingUrl
        if (pending != null) {
            pendingUrl = null
            MPVLib.command(arrayOf("loadfile", pending))
        } else {
            MPVLib.setPropertyString("vo", "gpu-next")
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
        // 拉伸档位依赖 Surface 的宽高比，旋转/切横竖屏后必须重新下发。
        applyAspect()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        runCatching { MPVLib.setPropertyBoolean("pause", true) }
        runCatching { MPVLib.setPropertyString("vo", "null") }
        runCatching { MPVLib.setPropertyString("force-window", "no") }
        runCatching { MPVLib.detachSurface() }
    }

    /** 属性快照：排查「时长只有 1 秒 / 起播即 EOF / 进度归零循环」时，先看这一行。 */
    private fun dumpSnapshot(tag: String) {
        val keys = listOf(
            "duration", "time-pos", "eof-reached", "idle-active", "keep-open", "pause",
            "paused-for-cache", "core-idle", "demuxer", "file-format", "video-format",
            "width", "height", "track-list/count", "estimated-frame-count", "path"
        )
        val props = keys.joinToString(" ") { key ->
            "$key=" + runCatching { MPVLib.getPropertyString(key) }.getOrNull().orEmpty()
        }
        Log.i("GlassBoxPlayer", "MPV 属性[$tag] $props")
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MPV_EVENT_FILE_LOADED -> {
                loaded = true
                error = null
                // 先停在暂停态：立刻 seek + 改 vf + 取消 pause 在部分 SoC 上会在首帧闪退。
                runCatching { MPVLib.setPropertyBoolean("pause", true) }
                runCatching { applyAspect() }
                dumpSnapshot("FILE_LOADED")
                emit()
                // 延后到主线程：等 surface/硬解管道就绪再 seek、画面参数、起播
                mainHandler.postDelayed({
                    if (released) return@postDelayed
                    runCatching {
                        if (!startApplied && pendingStartMs > 0) {
                            startApplied = true
                            seekTo(pendingStartMs)
                        }
                        if (!picture.isDefault) applyPicture(picture)
                        MPVLib.setPropertyBoolean("pause", false)
                    }.onFailure {
                        Log.e("GlassBoxPlayer", "MPV 起播失败：${it.message}", it)
                        error = "起播失败：${it.message}"
                    }
                    dumpSnapshot("FILE_LOADED+start")
                    emit()
                }, 120)
                mainHandler.postDelayed({ if (!released) dumpSnapshot("FILE_LOADED+2s") }, 2000)
            }
            MPVLib.MPV_EVENT_END_FILE -> {
                // 切集时上一文件的 END_FILE 会在新一次 play() 之后才到（loaded 已被重置），
                // 其时机与新文件真正打开失败的 END_FILE 无法区分，任何定时复核都可能抢在
                // FILE_LOADED 前误判，导致正常切集被降级。这里只处理「已加载后的正常结束」，
                // 打开失败统一交给上层「无帧无进度」超时兜底（MPV 20s）。
                if (loaded) ended = true
                emit()
            }
            MPVLib.MPV_EVENT_VIDEO_RECONFIG -> {
                videoW = runCatching { MPVLib.getPropertyInt("video-params/w") ?: 0 }.getOrDefault(0)
                videoH = runCatching { MPVLib.getPropertyInt("video-params/h") ?: 0 }.getOrDefault(0)
                emit()
            }
            else -> Unit
        }
    }

    override fun eventProperty(name: String) = emit()

    override fun eventProperty(name: String, value: Long) {
        when (name) {
            "video-params/w" -> videoW = value.toInt()
            "video-params/h" -> videoH = value.toInt()
            "estimated-frame-count" -> frames = value
        }
        emit()
    }

    override fun eventProperty(name: String, value: Double) {
        when (name) {
            "time-pos" -> positionMs = (value * 1000).toLong().coerceAtLeast(0)
            "duration" -> durationMs = (value * 1000).toLong().coerceAtLeast(0)
            "speed" -> speed = value.toFloat()
            "estimated-vf-fps" -> fps = value.toFloat()
        }
        emit()
    }

    override fun eventProperty(name: String, value: Boolean) {
        when (name) {
            "pause" -> paused = value
            "paused-for-cache" -> pausedForCache = value
            "eof-reached" -> ended = value
        }
        emit()
    }

    override fun eventProperty(name: String, value: String) {
        if (name == "hwdec-current") decoder = value
        emit()
    }

    private fun emit() {
        if (released) return
        // 属性回调极密，全量 post 会把主线程打满；起播阶段再叠加 Compose 重组更容易闪退。
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastEmitMs < 50) {
            if (!pendingEmit) {
                pendingEmit = true
                mainHandler.postDelayed({ pendingEmit = false; emit() }, 50)
            }
            return
        }
        lastEmitMs = now
        val snapshot = PlaybackState(
            kernel = PlaybackKernel.MPV,
            positionMs = positionMs,
            durationMs = durationMs,
            playing = !paused && !ended,
            buffering = pausedForCache && !ended,
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
        mainHandler.post {
            if (!released) onSnapshot(snapshot)
        }
    }

    override fun release() {
        if (released) return
        released = true
        runCatching {
            MPVLib.removeObserver(this)
            MPVLib.removeLogObserver(logObserver)
            MPVLib.setPropertyString("vo", "null")
            MPVLib.detachSurface()
            MPVLib.destroy()
        }
    }
}
