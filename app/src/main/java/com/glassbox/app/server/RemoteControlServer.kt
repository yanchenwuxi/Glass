package com.glassbox.app.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * 局域网推送 / 远程控制 —— 精简自 TVBox RemoteServer（NanoHTTPD）。
 * 默认不自动启动；由设置页或 ViewModel 按需 start/stop。
 *
 * 路由约定（与常见 TVBox 推送兼容，可继续扩展）：
 *  - GET  /          状态
 *  - POST /action    播放/暂停/下一集等（body: action=play|pause|next|prev）
 */
class RemoteControlServer(
    port: Int = DEFAULT_PORT,
    private val onAction: (action: String, extras: Map<String, String>) -> Unit = { _, _ -> }
) : NanoHTTPD(port) {

    @Volatile
    var running: Boolean = false
        private set

    override fun start() {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            running = true
            Log.i(TAG, "RemoteControlServer started on port $listeningPort")
        } catch (e: IOException) {
            running = false
            Log.e(TAG, "Failed to start RemoteControlServer", e)
            throw e
        }
    }

    override fun stop() {
        super.stop()
        running = false
        Log.i(TAG, "RemoteControlServer stopped")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri?.trimEnd('/') ?: ""
        return when {
            session.method == Method.GET && (uri.isEmpty() || uri == "/") ->
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"ok":true,"app":"GlassBox-Unified","port":$listeningPort}"""
                )

            session.method == Method.POST && uri == "/action" -> {
                val body = HashMap<String, String>()
                return try {
                    session.parseBody(body)
                    val action = body["action"] ?: session.parms["action"] ?: ""
                    onAction(action, body)
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        """{"ok":true,"action":"$action"}"""
                    )
                } catch (e: Exception) {
                    newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "application/json",
                        """{"ok":false,"error":"${e.message}"}"""
                    )
                }
            }

            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "application/json",
                """{"ok":false,"error":"not found"}"""
            )
        }
    }

    companion object {
        const val DEFAULT_PORT = 9978
        private const val TAG = "RemoteControlServer"
    }
}
