package com.glassbox.app.ui.screens

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.glassbox.app.data.EpisodeGroup
import com.glassbox.app.data.MediaDetail
import com.glassbox.app.data.PicturePreset
import com.glassbox.app.engine.ExoPlayerEngine
import com.glassbox.app.engine.IjkPlayerEngine
import com.glassbox.app.engine.MediaTrack
import com.glassbox.app.engine.MpvPlayer
import com.glassbox.app.engine.PictureCapability
import com.glassbox.app.engine.PictureSettings
import com.glassbox.app.engine.PlaybackEngine
import com.glassbox.app.engine.PlaybackBridge
import com.glassbox.app.engine.PlaybackKernel
import com.glassbox.app.engine.PlaybackSession
import com.glassbox.app.engine.PlaybackState
import com.glassbox.app.engine.parse.M3u8AdCleaner
import com.glassbox.app.engine.dlna.DlnaDevice
import com.glassbox.app.engine.VideoAspect
import com.glassbox.app.ui.MainViewModel
import com.glassbox.app.ui.PlayRequest
import com.glassbox.app.ui.components.*
import com.glassbox.app.ui.theme.GlassTheme
import com.glassbox.app.ui.theme.PlayerTokens
import kotlinx.coroutines.delay

/**
 * 播放器：libmpv 内核（与 BieCuiLe 的 media_kit/MpvSurfaceView 同源）+ 原版控制层。
 *
 * - 竖屏半屏：16:9 视频 + 海报/标题/标签/简介卡 + 5 个圆钮 + 选集；
 * - 横屏全屏：顶栏（内核/硬解/片头/跳过/片尾/小窗/评论）+ 左右竖列 + 底部主控与工具；
 * - 手势：左亮度、右音量、横滑进度、双击暂停；锁定时禁用；
 * - 倍速/字幕/音轨/比例/信息/选集可用；弹幕/画面/投屏给出内核不支持提示。
 */
@Composable
fun PlayerScreen(vm: MainViewModel, onBack: () -> Unit) {
    val palette = GlassTheme.colors
    val request by vm.pendingPlay.collectAsState()
    val settings by vm.settings.collectAsState()
    val remoteCmd by vm.remoteCommand.collectAsState()
    val dlnaDevices by vm.dlnaDevices.collectAsState()
    val dlnaBusy by vm.dlnaBusy.collectAsState()
    val dlnaMessage by vm.dlnaMessage.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current

    var snapshot by remember { mutableStateOf(PlaybackState()) }
    // 三内核可切换：默认取设置里的内核；切换/降级时重建引擎实例（AndroidView 也随之重建）。
    var kernel by remember { mutableStateOf(PlaybackKernel.fromKey(settings.playerKernel)) }
    var fallbackChain by remember { mutableStateOf(listOf(PlaybackKernel.fromKey(settings.playerKernel))) }
    // 首次组合即持有非空播放器：AndroidView 的 factory 只执行一次，
    // 若此时引擎为 null，会永久挂载占位 View 导致黑屏。
    val engine = remember(kernel) {
        createEngine(context, kernel) { snapshot = it }
    }
    val engineRef = rememberUpdatedState(engine)
    var fullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableIntStateOf(request?.groupIndex ?: 0) }
    var selectedEpisode by remember { mutableIntStateOf(request?.episodeIndex ?: 0) }
    var page by remember { mutableIntStateOf(0) }
    var showEpisodes by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(false) }
    var showPicture by remember { mutableStateOf(false) }
    var showCast by remember { mutableStateOf(false) }
    var currentPlayUrl by remember { mutableStateOf("") }
    var currentPlayTitle by remember { mutableStateOf("") }
    var showLines by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var brightness by remember { mutableFloatStateOf(0.5f) }
    var volume by remember { mutableFloatStateOf(0.5f) }
    var loop by remember { mutableStateOf(false) }
    var aspectMode by remember { mutableIntStateOf(settings.aspectMode) }
    // 画面参数：与原版一致，退出播放页/重启应用后记忆（持久化在 AppSettings）。
    var picture by remember {
        mutableStateOf(
            PictureSettings(
                brightness = settings.pictureBrightness.toFloat(),
                contrast = settings.pictureContrast.toFloat(),
                saturation = settings.pictureSaturation.toFloat(),
                hue = settings.pictureHue.toFloat(),
                gamma = settings.pictureGamma.toFloat()
            )
        )
    }
    var boostPrevSpeed by remember { mutableFloatStateOf(1f) }
    var localError by remember { mutableStateOf<String?>(null) }
    // 地址层失败（网页/接口/403）不允许触发内核降级，否则会把一个解析错误演成三个内核报错
    var addressFailure by remember { mutableStateOf(false) }
    // 地址解析/探测可能比 25s 还慢（脚本源要串行请求），只有真正把地址交给内核后才允许计「加载超时」，
    // 否则会把「还在解析地址」误报成「线路没有返回画面」，进而无意义地连跳三个内核。
    var playStarted by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var trackRefresh by remember { mutableIntStateOf(0) }

    val detail: MediaDetail? = request?.detail
    val groups: List<EpisodeGroup> = detail?.groups ?: emptyList()
    val group = groups.getOrNull(selectedGroup)
    val episode = group?.episodes?.getOrNull(selectedEpisode)
    val error = snapshot.error ?: localError

    // 局域网远程控制：play/pause/toggle/next/prev/seek/stop
    LaunchedEffect(remoteCmd) {
        val cmd = remoteCmd ?: return@LaunchedEffect
        val eng = engineRef.value
        when (cmd.action.lowercase()) {
            "play", "resume" -> eng.resume()
            "pause" -> eng.pause()
            "toggle", "playpause" -> {
                if (snapshot.playing) eng.pause() else eng.resume()
            }
            "stop" -> {
                eng.pause()
                onBack()
            }
            "next" -> {
                val d = detail ?: return@LaunchedEffect
                val g = d.groups.getOrNull(selectedGroup) ?: return@LaunchedEffect
                if (selectedEpisode + 1 < g.episodes.size) {
                    selectedEpisode += 1
                } else if (selectedGroup + 1 < d.groups.size) {
                    selectedGroup += 1
                    selectedEpisode = 0
                }
            }
            "prev", "previous" -> {
                if (selectedEpisode > 0) {
                    selectedEpisode -= 1
                } else if (selectedGroup > 0) {
                    selectedGroup -= 1
                    val g = detail?.groups?.getOrNull(selectedGroup)
                    selectedEpisode = ((g?.episodes?.size ?: 1) - 1).coerceAtLeast(0)
                }
            }
            "seek" -> {
                val ms = cmd.extras["positionMs"]?.toLongOrNull()
                    ?: cmd.extras["ms"]?.toLongOrNull()
                    ?: return@LaunchedEffect
                eng.seekTo(ms)
            }
        }
        vm.consumeRemoteCommand(cmd)
    }

    // 回写播放状态供 GET /status
    LaunchedEffect(snapshot.playing, snapshot.positionMs, snapshot.durationMs, episode?.name) {
        vm.updatePlaybackStatus(
            playing = snapshot.playing,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            title = detail?.item?.title ?: "",
            episode = episode?.name ?: ""
        )
    }


    if (showCast) {
        DlnaCastSheet(
            devices = dlnaDevices,
            busy = dlnaBusy,
            message = dlnaMessage,
            onDiscover = { vm.discoverDlna() },
            onCast = { device ->
                val u = currentPlayUrl
                if (u.isBlank()) {
                    hint = "当前没有可投屏地址"
                } else {
                    vm.castToDlna(device, u, currentPlayTitle.ifBlank { detail?.item?.title ?: "GlassBox" })
                }
            },
            onPause = { vm.dlnaPause(it) },
            onStop = { vm.dlnaStop(it) },
            onDismiss = { showCast = false }
        )
    }


    LaunchedEffect(kernel) {
        snapshot = PlaybackState()
    }

    // 内核重建（手动切换/自动降级）后，把当前比例档位重新下发到新内核。
    LaunchedEffect(engine, aspectMode) {
        engine.setAspect(aspectMode)
    }

    // 等首帧/loaded 后再下发画面参数，避免与硬解起播抢同一时刻导致闪退。
    LaunchedEffect(engine, snapshot.loaded, snapshot.renderedFrames) {
        if (!picture.isDefault && (snapshot.loaded || snapshot.renderedFrames > 0)) {
            runCatching { engine.applyPicture(picture) }
        }
    }

    // 慢片源的首帧可能晚于超时窗口：一旦真的出帧/走进度，就撤掉误报的超时错误。
    LaunchedEffect(snapshot.renderedFrames, snapshot.positionMs) {
        if (localError != null && (snapshot.renderedFrames > 0 || snapshot.positionMs > 0)) {
            localError = null
        }
    }

    LaunchedEffect(kernel, snapshot.renderedFrames, snapshot.fps, snapshot.decoder) {
        if (snapshot.renderedFrames > 0 || snapshot.fps > 0f) {
            android.util.Log.i(
                "GlassBoxPlayer",
                "${kernel.label} 出帧：frames=${snapshot.renderedFrames} fps=${snapshot.fps} " +
                    "decoder=${snapshot.decoder} size=${snapshot.videoWidth}x${snapshot.videoHeight} " +
                    "pos=${snapshot.positionMs} dur=${snapshot.durationMs} loaded=${snapshot.loaded}"
            )
        }
    }

    DisposableEffect(engine) {
        view.keepScreenOn = true
        onDispose { engine.release() }
    }

    DisposableEffect(Unit) {
        onDispose {
            val active = engineRef.value
            val d = request?.detail
            if (d != null) {
                saveProgress(vm, d, selectedGroup, selectedEpisode, active)
            }
            view.keepScreenOn = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.let { act ->
                act.window.attributes = act.window.attributes.apply { screenBrightness = -1f }
                WindowCompat.getInsetsController(act.window, act.window.decorView).apply {
                    show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    // 单集播放失败自动降级：MPV → ExoPlayer → IJK。每次换集/重试都回到用户偏好的内核。
    LaunchedEffect(selectedGroup, selectedEpisode, reloadKey) {
        val preferred = PlaybackKernel.fromKey(settings.playerKernel)
        fallbackChain = listOf(preferred)
        if (kernel != preferred) kernel = preferred
    }

    LaunchedEffect(engine, error) {
        val message = error ?: return@LaunchedEffect
        // 换内核后，上一内核的快照里可能还留着旧 error；必须等新内核自己 emit 的错误才允许降级，
        // 否则会出现 MPV 错误 → Exo 立刻“继承”同一错误 → IJK 连环降级的误判。
        if (snapshot.kernel != engine.kernel) return@LaunchedEffect
        // 地址层错误直接可读呈现，不再连跳三个内核
        if (addressFailure || message.startsWith("地址解析失败")) return@LaunchedEffect
        val next = PlaybackKernel.next(engine.kernel) ?: return@LaunchedEffect
        if (next in fallbackChain) return@LaunchedEffect
        fallbackChain = fallbackChain + next
        hint = "${engine.kernel.label} 播放失败（$message），自动切换到 ${next.label}"
        android.util.Log.i("GlassBoxPlayer", "${engine.kernel.label} 播放失败（$message）→ 自动降级 ${next.label}")
        localError = null
        kernel = next
    }

    LaunchedEffect(request) {
        val req = request ?: return@LaunchedEffect
        selectedGroup = req.groupIndex
        selectedEpisode = req.episodeIndex
        page = (req.episodeIndex / 20).coerceAtLeast(0)
    }

    LaunchedEffect(engine, detail, selectedGroup, selectedEpisode, reloadKey) {
        val d = detail ?: return@LaunchedEffect
        val ep = d.groups.getOrNull(selectedGroup)?.episodes?.getOrNull(selectedEpisode)
            ?: return@LaunchedEffect
        localError = null
        playStarted = false
        val req = request
        val start = if (req != null && req.groupIndex == selectedGroup && req.episodeIndex == selectedEpisode)
            req.startPositionMs else 0L
        // 脚本源详情只给播放 id（如 vid|nid|0），起播前解析成真实地址；
        // 同时探测地址层是否真的返回媒体流，避免把 HTML/接口塞进内核。
        val prep = vm.preparePlayback(d, selectedGroup, selectedEpisode)
        engine.setSpeed(settings.defaultSpeed)
        engine.setHardwareDecoding(settings.hardwareDecode)
        engine.setLoop(loop)
        if (prep.error != null) {
            addressFailure = true
            localError = prep.error
            android.util.Log.e("GlassBoxPlayer", "起播中止（地址层）：${prep.error}")
            return@LaunchedEffect
        }
        addressFailure = false
        currentPlayUrl = prep.url
        currentPlayTitle = d.item.title + (episode?.name?.let { " · $it" } ?: "")
        val skipMs = if (start <= 0) settings.skipIntroSec.coerceAtLeast(0) * 1000L else 0L
        engine.play(prep.url, start + skipMs, prep.headers, forceHls = prep.isHls)
        if (skipMs > 0) hint = "已跳过片头 ${settings.skipIntroSec}s"
        // TVBox 风格无感去广告：原址先播，后台清洗 m3u8，检出广告再热切换
        if (settings.adFilterEnabled && (prep.isHls || prep.url.contains("m3u8", ignoreCase = true))) {
            val playUrl = prep.url
            val hdrs = prep.headers
            kotlinx.coroutines.delay(400)
            val cleaned = runCatching { M3u8AdCleaner.clean(playUrl, hdrs) }.getOrNull()
            if (cleaned != null && cleaned.adCount > 0 && !cleaned.proxyUrl.isNullOrBlank()) {
                val pos = engine.currentPositionMs().coerceAtLeast(0)
                engine.hotSwap(cleaned.proxyUrl!!, pos, hdrs, forceHls = true)
                currentPlayUrl = cleaned.proxyUrl!!
                hint = cleaned.message
                android.util.Log.i("GlassBoxPlayer", "m3u8 ad clean: ${cleaned.message}")
            }
        }
        playStarted = true
        trackRefresh++
    }

    // 超时只用于兜底：MPV 打开慢 HLS 的首帧时间明显更长（实测 >12s），给 30s；且必须确认既无帧也无进度，
    // 否则会把仍在正常加载的首选内核误判为失败并触发降级。
    val latestSnapshot = rememberUpdatedState(snapshot)
    LaunchedEffect(engine.kernel, episode, snapshot.loaded, playStarted) {
        if (playStarted && episode != null && !snapshot.loaded && !addressFailure) {
            delay(if (engine.kernel == PlaybackKernel.EXO) 12_000 else 25_000)
            val s = latestSnapshot.value
            if (!s.loaded && s.renderedFrames == 0L && s.positionMs == 0L && error == null) {
                localError = "加载超时：线路没有返回画面"
            }
        }
    }

    LaunchedEffect(controlsVisible, snapshot.playing, fullscreen, locked) {
        if (controlsVisible && snapshot.playing && !locked) {
            delay(GlassTheme.motion.controlsIdleMs)
            controlsVisible = false
        }
    }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(GlassTheme.motion.hintMs)
            hint = null
        }
    }

    fun toggleFullscreen(enable: Boolean) {
        fullscreen = enable
        controlsVisible = true
        activity?.let { act ->
            val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
            if (enable) {
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    fun cycleKernelManual() {
        val kernels = PlaybackKernel.entries
        val next = kernels[(kernels.indexOf(engine.kernel) + 1) % kernels.size]
        fallbackChain = listOf(next)
        hint = "切换到 ${next.label}"
        android.util.Log.i("GlassBoxPlayer", "手动切换内核：${engine.kernel.label} → ${next.label}")
        kernel = next
    }

    /** 画面滑杆：状态与内核同时更新，保证拖动即时生效。 */
    fun updatePicture(next: PictureSettings) {
        picture = next
        engine.applyPicture(next)
    }

    /** 滑杆松手/重置时落盘，避免拖动过程频繁写文件。 */
    fun persistPicture(next: PictureSettings) {
        vm.updateSettings {
            it.copy(
                pictureBrightness = next.brightness.toInt(),
                pictureContrast = next.contrast.toInt(),
                pictureSaturation = next.saturation.toInt(),
                pictureHue = next.hue.toInt(),
                pictureGamma = next.gamma.toInt()
            )
        }
    }

    fun enterPip() {
        val act = activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                act.enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                )
            }.onFailure { hint = "当前设备不支持小窗" }
        } else {
            hint = "Android 8.0 以下不支持小窗"
        }
    }

    fun closePlayer() {
        saveProgress(vm, detail, selectedGroup, selectedEpisode, engine)
        onBack()
    }

    fun chooseEpisode(index: Int) {
        if (index == selectedEpisode) return
        saveProgress(vm, detail, selectedGroup, selectedEpisode, engine)
        selectedEpisode = index
        page = (index / 20).coerceAtLeast(0)
        controlsVisible = true
    }

    /**
     * 线路切换按原版行为：保留同名剧集，找不到同名的再回到第 1 集。
     * 之前直接重置到第 0 集，切线路后会跳集，与原版不一致。
     */
    fun switchGroup(index: Int) {
        if (index == selectedGroup) return
        val currentName = episode?.name.orEmpty()
        val target = groups.getOrNull(index)?.episodes.orEmpty()
        val keep = if (currentName.isNotBlank())
            target.indexOfFirst { it.name == currentName } else -1
        saveProgress(vm, detail, selectedGroup, selectedEpisode, engine)
        selectedGroup = index
        selectedEpisode = if (keep >= 0) keep else 0
        page = (selectedEpisode / 20).coerceAtLeast(0)
        controlsVisible = true
    }

    BackHandler {
        if (fullscreen) toggleFullscreen(false) else closePlayer()
    }

    LaunchedEffect(Unit) {
        brightness = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        volume = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    if (request == null || detail == null) {
        Box(Modifier.fillMaxSize().background(palette.bgTop)) {
            EmptyState("没有正在播放的内容", "请从详情页选择一集", icon = Icons.Outlined.PlayCircleOutline) {
                PrimaryCapsule("返回", onClick = onBack)
            }
        }
        return
    }

    // ---------- 后台播放 + MediaSession ----------
    // 原版退到后台继续出声、锁屏/通知栏可控制播放。三个内核都不是 MediaSession 体系内的
    // Player，所以用系统 MediaSession 持有播放状态，并用通知栏动作桥接回当前播放页。
    val sessionRef = remember { arrayOfNulls<PlaybackSession>(1) }
    // 通知栏「上/下一集」要读最新的剧集选择，不能捕获 DisposableEffect 创建时的旧值。
    val sessionNav = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    sessionNav[0] = { forward ->
        val max = (group?.episodes?.size ?: 1) - 1
        if (forward) {
            if (selectedEpisode < max) chooseEpisode(selectedEpisode + 1)
        } else {
            if (selectedEpisode > 0) chooseEpisode(selectedEpisode - 1)
        }
    }
    DisposableEffect(engine) {
        val s = PlaybackSession(
            context = context,
            playAction = { engine.resume() },
            pauseAction = { engine.pause() },
            seekAction = { engine.seekTo(it) },
            nextAction = { sessionNav[0]?.invoke(true) },
            prevAction = { sessionNav[0]?.invoke(false) }
        )
        sessionRef[0] = s
        PlaybackBridge.session = s
        onDispose {
            s.release()
            sessionRef[0] = null
            if (PlaybackBridge.session === s) PlaybackBridge.session = null
        }
    }
    LaunchedEffect(
        sessionRef[0], snapshot.playing, snapshot.positionMs / 1000,
        snapshot.speed, detail.item.title, episode?.name
    ) {
        sessionRef[0]?.update(
            playing = snapshot.playing,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            speed = snapshot.speed,
            title = detail.item.title,
            subtitle = episode?.name.orEmpty()
        )
    }
    // Android 13+ 通知栏需要运行时授权；拒绝也不影响后台出声，只是不显示通知。
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 根容器必须透明：SurfaceView 在窗口图层之下，任何不透明背景（含 windowBackground）都会把画面盖住。
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (!fullscreen) Spacer(Modifier.statusBarsPadding())
            // 播放宿主 Box 在横竖屏下始终处于同一组合槽位，只改尺寸：
            // 避免 SurfaceView 被移除/重新挂载导致 surfaceDestroyed→surfaceCreated（IJK 会崩溃）。
            Box(
                when {
                    // 全屏时给视频宿主铺黑底：比例档位留出的黑边应与原版一致（原版全屏是纯黑）。
                    fullscreen -> Modifier.fillMaxSize().background(Color.Black)
                    else -> Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
                }
            ) {
                EngineSurface(engine, Modifier.fillMaxSize())
                if (!fullscreen) {
                    PortraitVideoControls(
                        snapshot = snapshot,
                        error = error,
                        episodeName = episode?.name.orEmpty(),
                        controlsVisible = controlsVisible,
                        onToggleControls = { controlsVisible = !controlsVisible },
                        onPlayPause = { engine.togglePause() },
                        onSeek = { engine.seekTo(it) },
                        onRetry = { localError = null; reloadKey++ },
                        onBack = { closePlayer() },
                        onLines = { showLines = true },
                        onFullscreen = { toggleFullscreen(true) },
                        onTracks = { showTracks = true },
                        onSpeed = { showSpeed = true },
                        onPicture = { showPicture = true },
                        onHint = { hint = it },
                        onVolumeGesture = { volume = it },
                        onBrightnessGesture = { b ->
                            brightness = b
                            activity?.let { act ->
                                act.window.attributes = act.window.attributes.apply { screenBrightness = b }
                            }
                        },
                        onSpeedBoost = { active ->
                            android.util.Log.i("GlassBoxPlayer", "长按倍速 active=$active prev=$boostPrevSpeed snapshotSpeed=${snapshot.speed}")
                            if (active) {
                                boostPrevSpeed = snapshot.speed
                                engine.setSpeed(2f)
                                hint = "长按 2× 快进"
                            } else {
                                engine.setSpeed(boostPrevSpeed)
                            }
                        },
                        aspectLabel = VideoAspect.label(aspectMode),
                        onCycleAspect = {
                            val next = VideoAspect.next(aspectMode)
                            aspectMode = next
                            engine.setAspect(next)
                            vm.updateSettings { it.copy(aspectMode = next) }
                            hint = "画面比例：${VideoAspect.label(next)}"
                        }
                    )
                }
            }
            if (!fullscreen) {
                PortraitDetail(
                    detail = detail,
                    groupTitle = group?.title.orEmpty(),
                    groups = groups,
                    selectedGroup = selectedGroup,
                    selectedEpisode = selectedEpisode,
                    page = page,
                    onPage = { page = it },
                    onChooseGroup = { index -> switchGroup(index) },
                    onChooseEpisode = { chooseEpisode(it) },
                    onEpisodes = { showEpisodes = true },
                    onHint = { hint = it }
                )
            }
        }
        if (fullscreen) {
            FullscreenOverlay(
                snapshot = snapshot,
                detail = detail,
                episodeName = episode?.name.orEmpty(),
                groupTitle = group?.title.orEmpty(),
                controlsVisible = controlsVisible,
                locked = locked,
                speed = snapshot.speed,
                hardwareDecoding = snapshot.hardwareDecoding,
                loop = loop,
                error = error,
                kernelLabel = engine.kernel.label,
                onCycleKernel = { cycleKernelManual() },
                onToggleControls = {
                    if (!locked) controlsVisible = !controlsVisible
                },
                onToggleLock = {
                    locked = !locked
                    controlsVisible = true
                },
                onSeek = { engine.seekTo(it) },
                onPlayPause = { engine.togglePause() },
                onPrev = { if (selectedEpisode > 0) chooseEpisode(selectedEpisode - 1) },
                onNext = {
                    val max = (group?.episodes?.size ?: 1) - 1
                    if (selectedEpisode < max) chooseEpisode(selectedEpisode + 1)
                },
                onRewind = { engine.seekTo(snapshot.positionMs - 10_000) },
                onForward = { engine.seekTo(snapshot.positionMs + 10_000) },
                onReload = {
                    localError = null
                    reloadKey++
                },
                onClose = { closePlayer() },
                onFullscreen = { toggleFullscreen(false) },
                onPip = { enterPip() },
                onEpisodes = { showEpisodes = true },
                onLines = { showLines = true },
                onSpeed = { showSpeed = true },
                onTracks = { showTracks = true },
                onPicture = { showPicture = true },
                onCast = { showCast = true },
                onInfo = { showInfo = true },
                onToggleLoop = {
                    loop = !loop
                    engine.setLoop(loop)
                    hint = if (loop) "单集循环已开" else "单集循环已关"
                },
                onToggleHardware = {
                    // ExoPlayer 走 MediaCodec，没有软解回退开关；如实提示而不是假装切换。
                    if (engine.kernel == PlaybackKernel.EXO) {
                        hint = "ExoPlayer 固定 MediaCodec 硬解，无软解回退"
                    } else {
                        val next = !snapshot.hardwareDecoding
                        engine.setHardwareDecoding(next)
                        hint = if (next) "硬解已开" else "软解已开"
                    }
                },
                onCycleAspect = {
                    val next = VideoAspect.next(aspectMode)
                    aspectMode = next
                    engine.setAspect(next)
                    // 记忆到设置，退出重进仍保持（原版同样记住比例）。
                    vm.updateSettings { it.copy(aspectMode = next) }
                    hint = "画面比例：${VideoAspect.label(next)}"
                },
                onScreenshot = {
                    val path = java.io.File(
                        context.getExternalFilesDir(null),
                        "shot_${System.currentTimeMillis()}.png"
                    ).absolutePath
                    if (engine.screenshot(path)) {
                        hint = "截图已保存：${java.io.File(path).name}"
                    } else {
                        captureSurface(context, engine.view) { saved ->
                            hint = if (saved != null) "截图已保存：${java.io.File(saved).name}" else "截图失败"
                        }
                    }
                },
                onHint = { hint = it },
                onVolumeGesture = { volume = it },
                onBrightnessGesture = { b ->
                    brightness = b
                    activity?.let { act -> act.window.attributes = act.window.attributes.apply { screenBrightness = b } }
                },
                onSpeedBoost = { active ->
                    android.util.Log.i("GlassBoxPlayer", "长按倍速(竖屏) active=$active prev=$boostPrevSpeed snapshotSpeed=${snapshot.speed}")
                    if (active) {
                        boostPrevSpeed = snapshot.speed
                        engine.setSpeed(2f)
                        hint = "长按 2× 快进"
                    } else {
                        engine.setSpeed(boostPrevSpeed)
                    }
                }
            )
        }

        hint?.let {
            Text(
                it,
                color = PlayerTokens.onVideo,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(PlayerTokens.hintBg, GlassTheme.shapes.pill)
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }
    }

    // ---------- 选集 ----------
    GlassBottomSheet(visible = showEpisodes, onDismiss = { showEpisodes = false }, maxHeight = 560.dp) {
        SheetHeader("选集", "${groups.size} 条线路 · ${group?.episodes?.size ?: 0} 集")
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            groups.forEachIndexed { gIndex, g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassChip(g.title.ifBlank { "线路 ${gIndex + 1}" }, gIndex == selectedGroup) {
                        switchGroup(gIndex)
                    }
                    Spacer(Modifier.width(8.dp))
                    SoftBadge("${g.episodes.size} 集")
                }
                Spacer(Modifier.height(8.dp))
            }
            if (group != null) {
                Spacer(Modifier.height(4.dp))
                EpisodeGrid(
                    episodes = group.episodes.take(120),
                    selectedIndex = selectedEpisode,
                    columns = 4,
                    onSelect = { index ->
                        chooseEpisode(index)
                        showEpisodes = false
                    }
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // ---------- 线路 ----------
    GlassBottomSheet(visible = showLines, onDismiss = { showLines = false }, maxHeight = 420.dp) {
        SheetHeader("切换线路", "优先展示直链线路")
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 16.dp)) {
            groups.forEachIndexed { index, g ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(GlassTheme.shapes.sm)
                        .background(if (index == selectedGroup) palette.accentSoft else palette.surface)
                        .clickable {
                            switchGroup(index)
                            showLines = false
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(g.title.ifBlank { "线路 ${index + 1}" }, color = palette.textPrimary, fontSize = 14.sp)
                        Text(
                            "可播放 ${g.playableCount}/${g.episodes.size}",
                            color = palette.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                    if (index == selectedGroup) SoftBadge("当前", accent = true)
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    // ---------- 倍速 ----------
    GlassBottomSheet(visible = showSpeed, onDismiss = { showSpeed = false }, maxHeight = 320.dp) {
        SheetHeader("播放倍速", "音画不同步时请切回 1×")
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 16.dp)) {
            val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
            speeds.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { s ->
                        Box(Modifier.weight(1f)) {
                            GlassChip(
                                text = if (s == 1f) "1×" else "${s}×",
                                selected = kotlin.math.abs(snapshot.speed - s) < 0.01f,
                                onClick = {
                                    engine.setSpeed(s)
                                    showSpeed = false
                                }
                            )
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // ---------- 字幕 / 音轨（各内核自己的轨道列表） ----------
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ext = uri.lastPathSegment?.substringAfterLast('.', "srt")?.lowercase()
            ?.takeIf { it.length in 2..4 } ?: "srt"
        val target = java.io.File(context.cacheDir, "external_sub_${System.currentTimeMillis()}.$ext")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }.isSuccess
        if (!copied) {
            hint = "字幕文件读取失败"
        } else if (engine.addSubtitleFile(target.absolutePath)) {
            trackRefresh++
            hint = "外挂字幕已加载"
        } else {
            hint = "${engine.kernel.label} 不支持外挂字幕（IJK 无字幕文件接口）"
        }
    }
    val audioTracks = remember(trackRefresh, showTracks, engine) { engine.audioTracks() ?: emptyList<MediaTrack>() }
    val subtitleTracks = remember(trackRefresh, showTracks, engine) { engine.subtitleTracks() ?: emptyList<MediaTrack>() }
    GlassBottomSheet(visible = showTracks, onDismiss = { showTracks = false }, maxHeight = 420.dp) {
        SheetHeader("字幕与音轨", "来自 ${engine.kernel.label} 轨道列表")
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Text("音轨", color = palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (audioTracks.isEmpty()) {
                Text("当前媒体没有可选音轨", color = palette.textSecondary, fontSize = 12.sp)
            } else {
                audioTracks.forEach { track ->
                    TrackRow(track.label, track.selected || engine.currentAudioId() == track.id) {
                        engine.selectAudio(track.id)
                        showTracks = false
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("字幕", color = palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (subtitleTracks.isEmpty()) {
                Text("当前媒体没有内嵌字幕", color = palette.textSecondary, fontSize = 12.sp)
            } else {
                subtitleTracks.forEach { track ->
                    TrackRow(track.label, track.selected || engine.currentSubtitleId() == track.id) {
                        engine.selectSubtitle(track.id)
                        showTracks = false
                    }
                }
                TrackRow("关闭字幕", engine.currentSubtitleId() == 0) {
                    engine.disableSubtitle()
                    showTracks = false
                }
            }
            Spacer(Modifier.height(8.dp))
            TrackRow("加载字幕文件（.srt / .ass / .vtt）", false) {
                // 不复用底部固定的 mime 过滤：各家文件管理器对字幕 mime 标注不一致。
                subtitlePicker.launch(arrayOf("*/*"))
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // ---------- 信息 ----------
    GlassBottomSheet(visible = showInfo, onDismiss = { showInfo = false }, maxHeight = 360.dp) {
        SheetHeader("播放信息", "${engine.kernel.label} · 硬解 ${if (snapshot.hardwareDecoding) "开" else "关"}")
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 16.dp)) {
            InfoRow("内核", engine.kernel.tag)
            InfoRow("解码器", snapshot.decoder.ifBlank { if (snapshot.hardwareDecoding) "mediacodec" else "software" })
            InfoRow("分辨率", if (snapshot.videoWidth > 0) "${snapshot.videoWidth}×${snapshot.videoHeight}" else "等待画面")
            InfoRow(
                "画面输出",
                if (snapshot.renderedFrames > 0 || snapshot.fps > 0f)
                    "已出帧${if (snapshot.fps > 0f) " · ${"%.1f".format(snapshot.fps)} fps" else ""}"
                else "等待首帧"
            )
            InfoRow("倍速", "${snapshot.speed}×")
            InfoRow("线路", group?.title.orEmpty().ifBlank { "线路 ${selectedGroup + 1}" })
            InfoRow("剧集", episode?.name.orEmpty())
            Spacer(Modifier.height(8.dp))
        }
    }

    // ---------- 画面调节 ----------
    // 1:1 还原 BieCuiLe 1.1.5：居中深色卡片，5 个 -100%~+100% 滑杆（默认 +0%），
    // 「+存为预设」「Anime4K 增强」「黑边虚化」与底部的「恢复原始」。
    val pictureCap = engine.pictureCapability()
    if (showPicture) {
        PicturePanel(
            capability = pictureCap,
            picture = picture,
            presets = settings.picturePresets,
            onChange = { updatePicture(it) },
            onCommit = { persistPicture(it) },
            onReset = {
                val reset = PictureSettings()
                updatePicture(reset)
                persistPicture(reset)
                hint = "已恢复原始画面"
            },
            onSavePreset = { name ->
                val preset = PicturePreset(
                    name = name,
                    brightness = picture.brightness.toInt(),
                    contrast = picture.contrast.toInt(),
                    saturation = picture.saturation.toInt(),
                    hue = picture.hue.toInt(),
                    gamma = picture.gamma.toInt()
                )
                val merged = settings.picturePresets.filterNot { it.name == name } + preset
                vm.updateSettings { it.copy(picturePresets = merged) }
                hint = "已保存预设「$name」"
            },
            onApplyPreset = { preset ->
                val next = PictureSettings(
                    brightness = preset.brightness.toFloat(),
                    contrast = preset.contrast.toFloat(),
                    saturation = preset.saturation.toFloat(),
                    hue = preset.hue.toFloat(),
                    gamma = preset.gamma.toFloat()
                )
                updatePicture(next)
                persistPicture(next)
                hint = "已应用预设「${preset.name}」"
            },
            onSwitchMpv = {
                fallbackChain = listOf(PlaybackKernel.MPV)
                kernel = PlaybackKernel.MPV
                showPicture = false
                hint = "已切换到 MPV，可继续调节画面"
            },
            onDismiss = { showPicture = false }
        )
    }
}

@Composable
private fun EngineSurface(engine: PlaybackEngine, modifier: Modifier) {
    // key(engine)：切换内核/降级时让 AndroidView 重新走 factory，挂上新引擎的宿主 View。
    key(engine) {
        AndroidView(
            factory = {
                engine.view.also { v ->
                    v.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { v -> v.keepScreenOn = true },
            modifier = modifier
        )
    }
}

/**
 * 手势层：覆盖在 SurfaceView（AndroidView）之上。真机实测 Compose 命中优先于宿主 View，
 * 手势不会被 SurfaceView 吞掉，也不需要给 SurfaceView 加 onTouchListener。
 *
 * 单击=显隐控制层；双击左/右 30% 区域=±10 秒，中间=暂停/播放；
 * 纵向滑：左半屏亮度、右半屏音量；横向滑：拖动进度（松手后 seek）。
 * 锁定后只保留单击，避免误触。
 */
@Composable
private fun PlayerGestureLayer(
    snapshot: PlaybackState,
    locked: Boolean,
    onToggleControls: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onHint: (String) -> Unit,
    onVolumeGesture: (Float) -> Unit,
    onBrightnessGesture: (Float) -> Unit,
    onSpeedBoost: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 手势期间 snapshot 会持续变化，pointerInput 只按 locked 重启，必须读最新值。
    val latest = rememberUpdatedState(snapshot)
    var mode by remember { mutableIntStateOf(0) }
    var startVolume by remember { mutableFloatStateOf(0f) }
    var startBrightness by remember { mutableFloatStateOf(0f) }
    var startPos by remember { mutableLongStateOf(0L) }
    var totalDrag by remember { mutableFloatStateOf(0f) }
    var dragPosition by remember { mutableLongStateOf(-1L) }

    Box(
        modifier
            .pointerInput(locked) {
                detectTapGestures(
                    onTap = { onToggleControls() },
                    onDoubleTap = { offset ->
                        if (locked) return@detectTapGestures
                        val s = latest.value
                        when {
                            offset.x < size.width * 0.3f -> {
                                onSeek((s.positionMs - 10_000).coerceAtLeast(0L))
                                onHint("回退 10 秒")
                            }
                            offset.x > size.width * 0.7f -> {
                                val max = s.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
                                onSeek((s.positionMs + 10_000).coerceAtMost(max))
                                onHint("快进 10 秒")
                            }
                            else -> {
                                onPlayPause()
                                onHint(if (s.playing) "暂停" else "播放")
                            }
                        }
                    }
                )
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                detectDragGestures(
                    onDragStart = { _ ->
                        mode = 0
                        totalDrag = 0f
                        dragPosition = -1L
                        startPos = latest.value.positionMs
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        startVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        startBrightness = runCatching {
                            // 已有窗口亮度优先；系统自动亮度时回落到系统当前值，避免手势一起手就跳变。
                            val win = context.findActivity()?.window?.attributes?.screenBrightness ?: -1f
                            if (win >= 0f) win else android.provider.Settings.System.getInt(
                                context.contentResolver,
                                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                                128
                            ) / 255f
                        }.getOrDefault(0.5f)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (mode == 0) {
                            mode = if (kotlin.math.abs(dragAmount.x) > kotlin.math.abs(dragAmount.y)) 3
                            else if (change.position.x < size.width / 2f) 2 else 1
                        }
                        totalDrag += if (mode == 3) dragAmount.x else dragAmount.y
                        when (mode) {
                            1 -> {
                                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                val v = (startVolume - totalDrag / size.height).coerceIn(0f, 1f)
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).toInt(), 0)
                                onVolumeGesture(v)
                                onHint("音量 ${(v * 100).toInt()}%")
                            }
                            2 -> {
                                val b = (startBrightness - totalDrag / size.height).coerceIn(0.02f, 1f)
                                onBrightnessGesture(b)
                                onHint("亮度 ${(b * 100).toInt()}%")
                            }
                            3 -> {
                                val delta = (totalDrag / (size.width * 0.82f)) * 100000f
                                val target = (startPos + delta.toLong())
                                    .coerceIn(0L, latest.value.durationMs.coerceAtLeast(0L))
                                dragPosition = target
                                onHint((if (delta > 0) "快进到 " else "回退到 ") + durationText(target))
                            }
                        }
                    },
                    onDragEnd = {
                        if (mode == 3 && dragPosition >= 0) onSeek(dragPosition)
                        dragPosition = -1L
                    }
                )
            }
            .pointerInput(locked) {
                // 长按 2× 快进：抬手/滑动取消后恢复原倍速（原版长按倍速行为）。
                if (locked) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id)
                    android.util.Log.i("GlassBoxPlayer", "长按检测：${if (longPress != null) "触发" else "未触发（被取消/抬手/移动）"}")
                    if (longPress == null) return@awaitEachGesture
                    if (longPress.pressed) {
                        onSpeedBoost(true)
                        waitForUpOrCancellation()
                        onSpeedBoost(false)
                    }
                }
            }
    )
}

/** 原版横屏控制层：顶栏 + 左右竖列 + 底部主控/工具。 */
@Composable
private fun FullscreenOverlay(
    snapshot: PlaybackState,
    detail: MediaDetail,
    episodeName: String,
    groupTitle: String,
    controlsVisible: Boolean,
    locked: Boolean,
    speed: Float,
    hardwareDecoding: Boolean,
    loop: Boolean,
    error: String?,
    kernelLabel: String,
    onCycleKernel: () -> Unit,
    onToggleControls: () -> Unit,
    onToggleLock: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onEpisodes: () -> Unit,
    onLines: () -> Unit,
    onSpeed: () -> Unit,
    onTracks: () -> Unit,
    onPicture: () -> Unit,
    onCast: () -> Unit,
    onInfo: () -> Unit,
    onToggleLoop: () -> Unit,
    onToggleHardware: () -> Unit,
    onCycleAspect: () -> Unit,
    onScreenshot: () -> Unit,
    onHint: (String) -> Unit,
    onVolumeGesture: (Float) -> Unit,
    onBrightnessGesture: (Float) -> Unit,
    onSpeedBoost: (Boolean) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        PlayerGestureLayer(
            snapshot = snapshot,
            locked = locked,
            onToggleControls = onToggleControls,
            onPlayPause = onPlayPause,
            onSeek = onSeek,
            onHint = onHint,
            onVolumeGesture = onVolumeGesture,
            onBrightnessGesture = onBrightnessGesture,
            onSpeedBoost = onSpeedBoost,
            modifier = Modifier.fillMaxSize()
        )

        if (controlsVisible) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(PlayerTokens.topScrim, Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayerIconButton(Icons.Outlined.Close, "关闭播放器", size = 34.dp, onClick = onClose)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (episodeName.isBlank()) detail.item.title else "${detail.item.title}  $episodeName",
                    color = PlayerTokens.onVideo,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TopTextButton(kernelLabel, active = true, onClick = onCycleKernel)
                TopTextButton("硬解", active = hardwareDecoding, onClick = onToggleHardware)
                TopTextButton("片头") { onHint("片头跳过：自动识别未接入（libmpv 剧本事件）") }
                TopTextButton("跳过") { onHint("已跳过 5 秒片头") ; onSeek(5_000) }
                TopTextButton("片尾") { onHint("片尾跳过：剩余 < 60s 时提示") }
                TopTextButton("小窗", onClick = onPip)
                TopTextButton("评论") { onHint("评论浮层未接入") }
            }

            // 左列：锁定 / 听歌识曲占位
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PlayerIconButton(
                    if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    if (locked) "解锁" else "锁定",
                    onClick = onToggleLock
                )
                PlayerIconButton(Icons.Outlined.MusicNote, "听歌识曲") { onHint("听歌识曲未接入") }
            }

            // 右列：竖屏 / 截图
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PlayerIconButton(Icons.Outlined.ScreenRotation, "竖屏", onClick = onFullscreen)
                PlayerIconButton(Icons.Outlined.PhotoCamera, "截图", onClick = onScreenshot)
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, PlayerTokens.bottomScrim, PlayerTokens.bottomScrimSolid)
                        )
                    )
                    .navigationBarsPadding()
                    // 原版横屏底部只有「进度条 + 控制/工具」两行，底边距更紧；这里去掉集数行后
                    // 同步收紧内边距，让两行整体下移填满底部，不再压住画面主体。
                    .padding(horizontal = 18.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(durationText(snapshot.positionMs), color = PlayerTokens.onVideoMuted, fontSize = 11.sp)
                    Spacer(Modifier.width(10.dp))
                    PlayerSeekBar(
                        position = snapshot.positionMs,
                        duration = snapshot.durationMs,
                        modifier = Modifier.weight(1f),
                        onSeek = onSeek
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(durationText(snapshot.durationMs), color = PlayerTokens.onVideoMuted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 原版底部左组：旋转、播放/暂停、循环、上一集、下一集（全部扁平无底板）。
                    PlayerIconButton(Icons.Outlined.ScreenRotation, "竖屏", size = 32.dp, onClick = onFullscreen)
                    Spacer(Modifier.width(12.dp))
                    PlayerIconButton(
                        if (snapshot.playing) Icons.Outlined.Pause else Icons.Filled.PlayArrow,
                        "播放暂停",
                        size = 40.dp,
                        onClick = onPlayPause
                    )
                    Spacer(Modifier.width(12.dp))
                    PlayerIconButton(if (loop) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat, "循环", size = 32.dp, onClick = onToggleLoop)
                    Spacer(Modifier.width(12.dp))
                    PlayerIconButton(Icons.Outlined.SkipPrevious, "上一集", size = 32.dp, onClick = onPrev)
                    Spacer(Modifier.width(12.dp))
                    PlayerIconButton(Icons.Outlined.SkipNext, "下一集", size = 32.dp, onClick = onNext)
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TopTextButton("倍速 ${speed}×", onClick = onSpeed)
                        TopTextButton("字幕", onClick = onTracks)
                        TopTextButton("弹幕") { onHint("弹幕未接入：需弹幕渲染库（DanmakuFlameMaster）+ 源站弹幕接口，两者当前都缺") }
                        TopTextButton("比例") { onCycleAspect() }
                        TopTextButton("画面", onClick = onPicture)
                        TopTextButton("信息", onClick = onInfo)
                        TopTextButton("投屏", onClick = onCast)
                        TopTextButton("选集", onClick = onEpisodes)
                    }
                }
            }
        }

        if (snapshot.buffering && error == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(36.dp),
                color = PlayerTokens.accent,
                strokeWidth = 2.5.dp
            )
        }

        if (error != null) {
            ErrorCard(
                error = error,
                onRetry = { onHint("重新加载…"); onReload() },
                onBack = onClose,
                onLines = onLines
            )
        }
    }
}

/** 竖屏视频区控制：对齐原版 1.1.5 —— 顶部集名 + 功能文字、规格角标、中央播放键、底部时间 + 进度 + 全屏。 */
@Composable
private fun PortraitVideoControls(
    snapshot: PlaybackState,
    error: String?,
    episodeName: String,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onLines: () -> Unit,
    onFullscreen: () -> Unit,
    onTracks: () -> Unit,
    onSpeed: () -> Unit,
    onPicture: () -> Unit,
    onHint: (String) -> Unit,
    onVolumeGesture: (Float) -> Unit,
    onBrightnessGesture: (Float) -> Unit,
    onSpeedBoost: (Boolean) -> Unit,
    aspectLabel: String,
    onCycleAspect: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        // 竖屏同样支持单击/双击/纵向亮度音量/横向进度，和全屏共用一套手势层。
        PlayerGestureLayer(
            snapshot = snapshot,
            locked = false,
            onToggleControls = onToggleControls,
            onPlayPause = onPlayPause,
            onSeek = onSeek,
            onHint = onHint,
            onVolumeGesture = onVolumeGesture,
            onBrightnessGesture = onBrightnessGesture,
            onSpeedBoost = onSpeedBoost,
            modifier = Modifier.fillMaxSize()
        )
        if (controlsVisible) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(PlayerTokens.topScrim, Color.Transparent)))
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayerIconButton(Icons.Outlined.ArrowBack, "返回", size = 34.dp, onClick = onBack)
                    Text(
                        episodeName.ifBlank { "—" },
                        color = PlayerTokens.onVideo,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // 原版顶栏是纯文字功能入口，无图标无胶囊底色。
                    TopTextButton("弹幕", fontSize = 13.sp) { onHint("当前源未提供弹幕") }
                    TopTextButton("字幕", fontSize = 13.sp) { onHint("当前源未提供字幕") }
                    TopTextButton("音轨", fontSize = 13.sp, onClick = onTracks)
                    TopTextButton("倍速", fontSize = 13.sp, onClick = onSpeed)
                    TopTextButton("比例", fontSize = 13.sp, onClick = onCycleAspect)
                    TopTextButton("画面", fontSize = 13.sp, onClick = onPicture)
                }
                // 原版在集名下方显示实际分辨率与清晰度角标。
                if (snapshot.videoWidth > 0 && snapshot.videoHeight > 0) {
                    Text(
                        "${snapshot.videoWidth}×${snapshot.videoHeight} · ${qualityLabel(snapshot.videoWidth, snapshot.videoHeight)}",
                        color = PlayerTokens.onVideoSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .padding(start = 10.dp, top = 2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(PlayerTokens.hintBg)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            // 原版中央只有一颗扁平播放/暂停键，不再放上一集/下一集。
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
                    .clip(CircleShape)
                    // 原版无底色；这里加一层极淡的暗色底，保证白图标在亮画面上也能看见。
                    .background(Color(0x33000000), CircleShape)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (snapshot.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    "播放/暂停",
                    tint = PlayerTokens.onVideo,
                    modifier = Modifier.size(30.dp)
                )
            }
            // 原版底部是一行：当前时间 + 进度条 + 总时长 + 全屏。
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, PlayerTokens.bottomScrim)))
                    .padding(start = 14.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(durationText(snapshot.positionMs), color = PlayerTokens.onVideo, fontSize = 12.sp)
                Spacer(Modifier.width(10.dp))
                PlayerSeekBar(
                    position = snapshot.positionMs,
                    duration = snapshot.durationMs,
                    modifier = Modifier.weight(1f),
                    onSeek = onSeek,
                    progressColor = PlayerTokens.onVideo,
                    trackColor = Color(0x8CFFFFFF),
                    strokeWidth = 8f,
                    thumbRadius = 15f
                )
                Spacer(Modifier.width(10.dp))
                Text(durationText(snapshot.durationMs), color = PlayerTokens.onVideo, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                PlayerIconButton(Icons.Outlined.Fullscreen, "全屏", size = 32.dp, onClick = onFullscreen)
            }
        }
        if (snapshot.buffering && snapshot.error == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(32.dp),
                color = PlayerTokens.accent,
                strokeWidth = 2.5.dp
            )
        }
        if (error != null) {
            ErrorCard(error = error, onRetry = onRetry, onBack = onBack, onLines = onLines)
        }
    }
}

/** 竖屏半屏信息区（对齐原版 1.1.5）：海报 + 标题/标签/评分/简介 + 圆形操作 + 选集。 */
@Composable
private fun PortraitDetail(
    detail: MediaDetail,
    groupTitle: String,
    groups: List<EpisodeGroup>,
    selectedGroup: Int,
    selectedEpisode: Int,
    page: Int,
    onPage: (Int) -> Unit,
    onChooseGroup: (Int) -> Unit,
    onChooseEpisode: (Int) -> Unit,
    onEpisodes: () -> Unit,
    onHint: (String) -> Unit
) {
    val palette = GlassTheme.colors
    val group = groups.getOrNull(selectedGroup)
    val episodes = group?.episodes ?: emptyList()
    val pageSize = 20
    val pages = (episodes.size + pageSize - 1) / pageSize
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bgTop)
            .verticalScroll(rememberScrollState())
            .padding(start = 13.dp, end = 16.dp, top = 14.dp)
    ) {
        Row {
            Box {
                NetworkPoster(
                    detail.item.poster,
                    Modifier
                        .width(100.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                )
                // 原版把「上滑收起」做成海报底部的独立灰色胶囊。
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .clip(GlassTheme.shapes.pill)
                        .background(Color(0x66000000))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowUp,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("上滑收起", color = Color.White, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    detail.item.title,
                    color = palette.textPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(detail.item.remarks, detail.item.year, detail.item.area, detail.item.typeName)
                        .filter { it.isNotBlank() }
                        .take(4)
                        .forEach { TagChip(it) }
                }
                val score = detail.item.score.trim()
                if (score.isNotBlank()) {
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF3BB346)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("豆", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(5.dp))
                        Text(
                            score,
                            color = palette.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    detail.description,
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            VideoQuickAction(Icons.Outlined.Search, "搜索") { onHint("搜索请在媒体页使用") }
            VideoQuickAction(Icons.Outlined.Download, "下载") { onHint("下载未接入") }
            VideoQuickAction(Icons.Outlined.FavoriteBorder, "收藏") { onHint("收藏请在详情页操作") }
            VideoQuickAction(Icons.Outlined.Apps, "选集", onClick = onEpisodes)
            VideoQuickAction(Icons.Outlined.ChatBubbleOutline, "评论") { onHint("评论未接入") }
        }
        Spacer(Modifier.height(18.dp))
        // 原版选集标题后面紧跟线路胶囊，不再把线路推到右侧。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("选集", color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .height(26.dp)
                    .clip(GlassTheme.shapes.pill)
                    .background(palette.chipBg)
                    .clickable {
                        if (groups.size > 1) onChooseGroup((selectedGroup + 1) % groups.size)
                        else onHint("当前只有一条线路")
                    }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(palette.accent))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        groupTitle.ifBlank { "线路 ${selectedGroup + 1}" },
                        color = palette.textPrimary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        "切换线路",
                        tint = palette.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        if (pages > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pages) { index ->
                    val start = index * pageSize + 1
                    val end = minOf((index + 1) * pageSize, episodes.size)
                    GlassChip("$start-$end", index == page) { onPage(index) }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        val pageEpisodes = episodes.drop(page * pageSize).take(pageSize)
        if (pageEpisodes.isEmpty()) {
            Text("当前线路没有剧集", color = palette.textSecondary, fontSize = 12.sp)
        } else {
            EpisodeGrid(
                episodes = pageEpisodes,
                selectedIndex = selectedEpisode - page * pageSize,
                columns = 4,
                softSelected = true,
                onSelect = { local -> onChooseEpisode(page * pageSize + local) }
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun TagChip(text: String) {
    val palette = GlassTheme.colors
    Text(
        text,
        color = palette.textSecondary,
        fontSize = 10.sp,
        modifier = Modifier
            .clip(GlassTheme.shapes.pill)
            .background(palette.chipBg)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
private fun VideoQuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val palette = GlassTheme.colors
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(palette.chipBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = palette.textPrimary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun TopTextButton(
    label: String,
    active: Boolean = false,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
    onClick: () -> Unit
) {
    // 原版顶栏与底部工具均为纯文字，无胶囊底色。
    Text(
        label,
        color = PlayerTokens.onVideoSecondary,
        fontSize = fontSize,
        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

/**
 * 画面调节的一行：标题 + 滑杆 + 当前数值（与原版一致的带符号百分比）。
 * 不支持的项置灰（滑杆无法拖动、数值显示「—」），避免出现“点了没效果”。
 */
@Composable
private fun PictureSliderRow(
    label: String,
    value: Float,
    enabled: Boolean,
    range: ClosedFloatingPointRange<Float> = -100f..100f,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit = {}
) {
    val palette = GlassTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (enabled) palette.textPrimary else palette.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.width(46.dp)
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = palette.accent,
                inactiveTrackColor = Color(0xFF29282C),
                disabledThumbColor = palette.textSecondary.copy(alpha = 0.35f),
                disabledActiveTrackColor = palette.textSecondary.copy(alpha = 0.22f),
                disabledInactiveTrackColor = Color(0xFF232227)
            ),
            modifier = Modifier.weight(1f)
        )
        Text(
            if (enabled) formatPicturePercent(value) else "—",
            color = if (enabled) palette.textPrimary else palette.textSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp)
        )
    }
}

/** 原版数值形态：+0% / -35% / +100%（始终带符号）。 */
private fun formatPicturePercent(value: Float): String {
    val rounded = value.toInt()
    return if (rounded >= 0) "+$rounded%" else "$rounded%"
}

/**
 * BieCuiLe 1.1.5 画面面板 1:1 复刻：居中深色卡片、5 个滑杆、存为预设、
 * Anime4K 增强 / 黑边虚化、底部说明与「恢复原始」。
 */
@Composable
private fun PicturePanel(
    capability: PictureCapability,
    picture: PictureSettings,
    presets: List<PicturePreset>,
    onChange: (PictureSettings) -> Unit,
    onCommit: (PictureSettings) -> Unit,
    onReset: () -> Unit,
    onSavePreset: (String) -> Unit,
    onApplyPreset: (PicturePreset) -> Unit,
    onSwitchMpv: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = GlassTheme.colors
    var presetDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 400.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF141416))
                .heightIn(max = 560.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "画面调节",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "关闭",
                    tint = palette.textSecondary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .padding(6.dp)
                        .size(20.dp)
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                PictureSliderRow("亮度", picture.brightness, capability.brightness,
                    onChange = { onChange(picture.copy(brightness = it)) },
                    onFinished = { onCommit(picture) })
                PictureSliderRow("对比度", picture.contrast, capability.contrast,
                    onChange = { onChange(picture.copy(contrast = it)) },
                    onFinished = { onCommit(picture) })
                PictureSliderRow("饱和度", picture.saturation, capability.saturation,
                    onChange = { onChange(picture.copy(saturation = it)) },
                    onFinished = { onCommit(picture) })
                PictureSliderRow("色相", picture.hue, capability.hue,
                    onChange = { onChange(picture.copy(hue = it)) },
                    onFinished = { onCommit(picture) })
                PictureSliderRow("伽马", picture.gamma, capability.gamma,
                    onChange = { onChange(picture.copy(gamma = it)) },
                    onFinished = { onCommit(picture) })

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF3A373A))
                            .clickable {
                                presetName = ""
                                presetDialog = true
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text("+存为预设", color = Color.White, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (!capability.supported) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.accentSoft)
                                .clickable(onClick = onSwitchMpv)
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text("切换到 MPV", color = palette.accent, fontSize = 13.sp)
                        }
                    }
                }

                if (presets.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { preset ->
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF262529))
                                    .clickable { onApplyPreset(preset) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(preset.name, color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                PictureToggleRow(
                    title = "Anime4K 增强",
                    desc = "Anime4KCNNx2 着色器，仅 MPV 内核。当前构建未内置着色器，已禁用。",
                    available = false
                )
                PictureToggleRow(
                    title = "黑边虚化",
                    desc = "画面和屏幕比例不同时用模糊画面填满黑边（MPV/IJK）。当前构建未接入，已禁用。",
                    available = false
                )

                if (capability.note.isNotBlank() && (!capability.supported || !capability.gamma)) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        capability.note,
                        color = palette.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "0% 是原始画面；这里的亮度和屏幕亮度分开",
                        color = palette.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "恢复原始",
                        color = palette.accent,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onReset)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    if (presetDialog) {
        AlertDialog(
            onDismissRequest = { presetDialog = false },
            title = { Text("保存为预设") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("预设名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                Text(
                    "确定",
                    color = palette.accent,
                    modifier = Modifier
                        .clickable {
                            val name = presetName.trim()
                            if (name.isNotEmpty()) {
                                onSavePreset(name)
                                presetDialog = false
                            }
                        }
                        .padding(8.dp)
                )
            },
            dismissButton = {
                Text(
                    "取消",
                    color = palette.textSecondary,
                    modifier = Modifier
                        .clickable { presetDialog = false }
                        .padding(8.dp)
                )
            }
        )
    }
}

/** 原版中的两个增强开关；未接入的项明确标注，不做可点但无效果的开关。 */
@Composable
private fun PictureToggleRow(title: String, desc: String, available: Boolean) {
    val palette = GlassTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (available) palette.textPrimary else palette.textSecondary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(desc, color = palette.textSecondary, fontSize = 10.5.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (available) {
            var checked by remember { mutableStateOf(false) }
            Switch(
                checked = checked,
                onCheckedChange = { checked = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = palette.accent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFF3A373A)
                )
            )
        } else {
            Text(
                "未接入",
                color = palette.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF262529))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun ErrorCard(error: String, onRetry: () -> Unit, onBack: () -> Unit, onLines: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xE6141416))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = PlayerTokens.accent, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(12.dp))
            Text("播放失败", color = PlayerTokens.onVideo, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "没有获取到播放地址或线路不可用\n$error",
                color = PlayerTokens.onVideoTertiary,
                fontSize = 11.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .clip(GlassTheme.shapes.pill)
                        .background(PlayerTokens.accent)
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                ) {
                    Text("重试", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Box(
                    Modifier
                        .clip(GlassTheme.shapes.pill)
                        .background(Color(0x33FFFFFF))
                        .clickable(onClick = onLines)
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                ) {
                    Text("换线路", color = Color.White, fontSize = 14.sp)
                }
                Box(
                    Modifier
                        .clip(GlassTheme.shapes.pill)
                        .background(Color(0x33FFFFFF))
                        .clickable(onClick = onBack)
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                ) {
                    Text("返回", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val palette = GlassTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = palette.textSecondary, fontSize = 13.sp, modifier = Modifier.width(70.dp))
        Text(value, color = palette.textPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlayerIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    size: androidx.compose.ui.unit.Dp = 36.dp,
    onClick: () -> Unit
) {
    // 原版控制层图标是纯白扁平图标，没有圆形底板。
    Box(
        Modifier
            .size(size)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = PlayerTokens.onVideoSecondary, modifier = Modifier.size(size * 0.62f))
    }
}

@Composable
private fun PlayerSeekBar(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit,
    progressColor: Color = PlayerTokens.accent,
    trackColor: Color = PlayerTokens.seekTrack,
    strokeWidth: Float = 3f,
    thumbRadius: Float = 5.5f
) {
    var dragValue by remember { mutableLongStateOf(-1L) }
    val shown = if (dragValue >= 0) dragValue else position
    val ratio = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Canvas(
        modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(duration) {
                detectTapGestures(
                    onTap = { offset ->
                        if (duration > 0) onSeek((offset.x / size.width * duration).toLong())
                    }
                )
            }
            .pointerInput(duration) {
                detectDragGestures(
                    onDragStart = { dragValue = position },
                    onDrag = { change, _ ->
                        change.consume()
                        if (duration > 0) {
                            dragValue = (change.position.x / size.width * duration).toLong().coerceIn(0, duration)
                        }
                    },
                    onDragEnd = {
                        if (dragValue >= 0) onSeek(dragValue)
                        dragValue = -1
                    }
                )
            }
    ) {
        val centerY = size.height / 2
        drawLine(trackColor, Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = strokeWidth)
        drawLine(
            Brush.horizontalGradient(listOf(progressColor, progressColor)),
            Offset(0f, centerY),
            Offset(size.width * ratio, centerY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawCircle(PlayerTokens.seekThumb, radius = thumbRadius, center = Offset(size.width * ratio, centerY))
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = GlassTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(GlassTheme.shapes.sm)
            .background(if (selected) palette.accentSoft else palette.chipBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (selected) palette.accent else palette.textPrimary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (selected) SoftBadge("当前", accent = true)
    }
    Spacer(Modifier.height(6.dp))
}

private fun qualityLabel(width: Int, height: Int): String = when {
    width >= 3400 || height >= 1900 -> "4K"
    width >= 2400 || height >= 1300 -> "2K"
    width >= 1700 || height >= 900 -> "1080P"
    else -> "720P"
}

private fun durationText(ms: Long): String {
    val total = (ms.coerceAtLeast(0)) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun captureSurface(context: Context, root: View, onDone: (String?) -> Unit) {
    val view = root as? SurfaceView ?: findSurfaceView(root)
    val width = view?.width ?: 0
    val height = view?.height ?: 0
    if (view == null || width <= 0 || height <= 0) {
        onDone(null)
        return
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    PixelCopy.request(view, bitmap, { result ->
        if (result == PixelCopy.SUCCESS) {
            val file = java.io.File(context.getExternalFilesDir(null), "shot_${System.currentTimeMillis()}.png")
            val ok = runCatching {
                java.io.FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }.isSuccess
            bitmap.recycle()
            onDone(if (ok) file.absolutePath else null)
        } else {
            bitmap.recycle()
            onDone(null)
        }
    }, Handler(Looper.getMainLooper()))
}

private fun findSurfaceView(root: View): SurfaceView? {
    if (root is SurfaceView) return root
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            findSurfaceView(root.getChildAt(i))?.let { return it }
        }
    }
    return null
}

private fun createEngine(
    context: Context,
    kernel: PlaybackKernel,
    onSnapshot: (PlaybackState) -> Unit
): PlaybackEngine = when (kernel) {
    PlaybackKernel.MPV -> MpvPlayer(context, onSnapshot)
    PlaybackKernel.EXO -> ExoPlayerEngine(context, onSnapshot)
    PlaybackKernel.IJK -> IjkPlayerEngine(context, onSnapshot)
}

private fun saveProgress(vm: MainViewModel, detail: MediaDetail?, group: Int, episode: Int, player: PlaybackEngine) {
    val d = detail ?: return
    val req = PlayRequest(d, group, episode, 0)
    vm.saveProgressAsync(
        req,
        player.currentPositionMs().coerceAtLeast(0),
        player.currentDurationMs().coerceAtLeast(0)
    )
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun DlnaCastSheet(
    devices: List<DlnaDevice>,
    busy: Boolean,
    message: String?,
    onDiscover: () -> Unit,
    onCast: (DlnaDevice) -> Unit,
    onPause: (DlnaDevice) -> Unit,
    onStop: (DlnaDevice) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = GlassTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
            .clickable { onDismiss() }
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(palette.bgMid)
                .clickable(enabled = false) {}
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            Text("DLNA 投屏", color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.accent)
                    .clickable(enabled = !busy, onClick = onDiscover)
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(if (busy) "搜索中…" else "搜索设备", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
            }
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(message ?: "", color = palette.textSecondary, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            if (devices.isEmpty()) {
                Text("同一 Wi‑Fi 下打开电视/盒子投屏后点击搜索", color = palette.textTertiary, fontSize = 13.sp)
            } else {
                devices.forEach { device ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text(device.name, color = palette.textPrimary, fontWeight = FontWeight.Medium)
                        if (device.manufacturer.isNotBlank() || device.model.isNotBlank()) {
                            Text(
                                listOf(device.manufacturer, device.model).filter { it.isNotBlank() }.joinToString(" · "),
                                color = palette.textTertiary,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "投屏",
                                color = palette.accent,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onCast(device) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                            Text(
                                "暂停",
                                color = palette.textSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onPause(device) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                            Text(
                                "停止",
                                color = palette.textSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onStop(device) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "关闭",
                color = palette.textSecondary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable { onDismiss() }
                    .padding(8.dp)
            )
        }
    }
}
