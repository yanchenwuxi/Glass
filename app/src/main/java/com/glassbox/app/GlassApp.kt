package com.glassbox.app

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.glassbox.app.data.AppStore
import com.glassbox.app.data.db.LibraryRepository
import com.glassbox.app.data.db.SqliteLibraryRepository
import com.glassbox.app.engine.SourceEngine
import com.glassbox.app.engine.live.LiveSourceParser
import com.glassbox.app.engine.live.M3uLiveSourceParser
import com.glassbox.app.engine.parse.JsonParseExecutor
import com.glassbox.app.engine.parse.ParseExecutor
import com.glassbox.app.server.RemoteControlServer

/**
 * GlassBox-Unified Application。
 * 融合模块：SQLite 历史/收藏、M3U 直播解析、JSON 解析链、局域网推送。
 */
class GlassApp : Application() {

    lateinit var store: AppStore
        private set
    lateinit var engine: SourceEngine
        private set
    lateinit var library: LibraryRepository
        private set
    lateinit var liveParser: LiveSourceParser
        private set
    lateinit var parseExecutor: ParseExecutor
        private set

    @Volatile
    var remoteServer: RemoteControlServer? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = AppStore(this)
        engine = SourceEngine()
        library = SqliteLibraryRepository(this)
        liveParser = M3uLiveSourceParser()
        parseExecutor = JsonParseExecutor()
    }

    fun startRemoteControl(port: Int = RemoteControlServer.DEFAULT_PORT, onAction: (String, Map<String, String>) -> Unit = { _, _ -> }) {
        stopRemoteControl()
        val server = RemoteControlServer(port, onAction)
        server.start()
        remoteServer = server
    }

    fun stopRemoteControl() {
        runCatching { remoteServer?.stop() }
        remoteServer = null
    }

    private val pythonLock = Any()

    fun ensurePython() {
        if (!Python.isStarted()) {
            synchronized(pythonLock) {
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
            }
        }
    }

    companion object {
        lateinit var instance: GlassApp
            private set
    }
}
