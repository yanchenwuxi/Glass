package com.glassbox.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log

/**
 * 系统媒体会话 + 通知栏/锁屏控制（对应原版「后台播放 + MediaSession」）。
 *
 * 直接用系统 `MediaSession` 与 `Notification.MediaStyle`：libmpv / ExoPlayer / IJK 三个内核
 * 都不是 MediaSession 体系内的 Player，引入 media3-session 或 androidx.media 也接不上它们。
 * 通知栏按钮通过一个静态桥 [PlaybackBridge] 转发回当前播放页，进程在后台时仍然有效。
 */
class PlaybackSession(
    private val context: Context,
    private val playAction: () -> Unit,
    private val pauseAction: () -> Unit,
    private val seekAction: (Long) -> Unit,
    private val nextAction: () -> Unit,
    private val prevAction: () -> Unit,
) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val session = MediaSession(context, "GlassBoxPlayer").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                Log.i("GlassBoxPlayer", "MediaSession onPlay")
                playAction()
            }

            override fun onPause() {
                Log.i("GlassBoxPlayer", "MediaSession onPause")
                pauseAction()
            }

            override fun onSeekTo(pos: Long) {
                Log.i("GlassBoxPlayer", "MediaSession onSeekTo=$pos")
                seekAction(pos)
            }

            override fun onSkipToNext() {
                Log.i("GlassBoxPlayer", "MediaSession onSkipToNext")
                nextAction()
            }

            override fun onSkipToPrevious() {
                Log.i("GlassBoxPlayer", "MediaSession onSkipToPrevious")
                prevAction()
            }
        })
        isActive = true
    }

    private var title: String = ""
    private var subtitle: String = ""
    private var playing = false
    private var hasNotification = false

    init {
        ensureChannel()
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "GlassBox")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "准备播放")
                .build()
        )
    }

    fun update(
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        title: String,
        subtitle: String
    ) {
        this.playing = playing
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, positionMs, if (playing) speed else 1f)
                .build()
        )
        if (title.isNotBlank() && (title != this.title || subtitle != this.subtitle)) {
            this.title = title
            this.subtitle = subtitle
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                    .build()
            )
        }
        if (playing) postNotification() else if (hasNotification) postNotification()
    }

    private fun action(intentAction: String, requestCode: Int): PendingIntent {
        val intent = Intent(intentAction).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun postNotification() {
        val toggle = if (playing) {
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_media_pause),
                "暂停",
                action(ACTION_PAUSE, 1)
            ).build()
        } else {
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_media_play),
                "播放",
                action(ACTION_PLAY, 2)
            ).build()
        }
        val notification: Notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title.ifBlank { "GlassBox" })
            .setContentText(subtitle)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(toggle)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_media_previous),
                    "上一集",
                    action(ACTION_PREV, 3)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_media_next),
                    "下一集",
                    action(ACTION_NEXT, 4)
                ).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
        runCatching {
            manager.notify(NOTIFICATION_ID, notification)
            hasNotification = true
        }.onFailure { Log.i("GlassBoxPlayer", "MediaSession 通知发送失败：${it.message}") }
    }

    // 通知栏 PendingIntent 走 BroadcastReceiver，需要把请求转发回构造时传入的播放回调。
    fun requestPlay() {
        Log.i("GlassBoxPlayer", "通知栏 ACTION_PLAY")
        playAction()
    }

    fun requestPause() {
        Log.i("GlassBoxPlayer", "通知栏 ACTION_PAUSE")
        pauseAction()
    }

    fun requestSkipNext() {
        Log.i("GlassBoxPlayer", "通知栏 ACTION_NEXT")
        nextAction()
    }

    fun requestSkipPrev() {
        Log.i("GlassBoxPlayer", "通知栏 ACTION_PREV")
        prevAction()
    }

    fun release() {
        runCatching { manager.cancel(NOTIFICATION_ID) }
        runCatching { session.isActive = false; session.release() }
        hasNotification = false
        Log.i("GlassBoxPlayer", "MediaSession 已释放")
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "播放控制", NotificationManager.IMPORTANCE_LOW).apply {
                description = "播放页通知栏与锁屏控制"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "glassbox_playback"
        const val NOTIFICATION_ID = 0x0B0C

        const val ACTION_PLAY = "com.glassbox.app.ACTION_PLAY"
        const val ACTION_PAUSE = "com.glassbox.app.ACTION_PAUSE"
        const val ACTION_NEXT = "com.glassbox.app.ACTION_NEXT"
        const val ACTION_PREV = "com.glassbox.app.ACTION_PREV"
    }
}

/** 通知栏按钮 → 当前播放页的静态桥；进程被系统回收前都有效。 */
object PlaybackBridge {
    var session: PlaybackSession? = null
}

/** 接收通知栏/锁屏的媒体按钮。 */
class PlaybackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = PlaybackBridge.session ?: return
        when (intent.action) {
            PlaybackSession.ACTION_PLAY -> s.requestPlay()
            PlaybackSession.ACTION_PAUSE -> s.requestPause()
            PlaybackSession.ACTION_NEXT -> s.requestSkipNext()
            PlaybackSession.ACTION_PREV -> s.requestSkipPrev()
        }
    }
}
