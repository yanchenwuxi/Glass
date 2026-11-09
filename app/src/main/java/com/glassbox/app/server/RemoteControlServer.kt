package com.glassbox.app.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * 局域网推送 / 远程控制 —— 精简自 TVBox RemoteServer（NanoHTTPD）。
 *
 * 路由：
 *  - GET  /          服务状态
 *  - GET  /status    播放状态 JSON（由 statusProvider 提供）
 *  - POST /action    body/query: action=play|pause|toggle|next|prev|seek|stop
 *                    seek 可带 positionMs=
 */
class RemoteControlServer(
    port: Int = DEFAULT_PORT,
    private val onAction: (action: String, extras: Map<String, String>) -> Unit = { _, _ -> },
    private val statusProvider: () -> String = { """{"ok":true,"playing":false}""" }
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
        val uri = session.uri?.substringBefore('?')?.trimEnd('/') ?: ""
        return when {
            session.method == Method.GET && (uri.isEmpty() || uri == "/") ->
                jsonOk(
                    """{"ok":true,"app":"GlassBox-Unified","port":$listeningPort,""" +
                        """"endpoints":["/","/status","/action"]}"""
                )

            session.method == Method.GET && uri == "/status" ->
                jsonOk(statusProvider())

            session.method == Method.POST && uri == "/action" -> {
                val body = HashMap<String, String>()
                return try {
                    session.parseBody(body)
                    val merged = LinkedHashMap<String, String>()
                    session.parms?.forEach { (k, v) -> if (v != null) merged[k] = v }
                    body.forEach { (k, v) -> merged[k] = v }
                    val action = (merged["action"] ?: "").trim().lowercase()
                    if (action.isEmpty()) {
                        jsonError(400, "missing action")
                    } else {
                        onAction(action, merged)
                        jsonOk("""{"ok":true,"action":"$action"}""")
                    }
                } catch (e: Exception) {
                    jsonError(500, e.message ?: "error")
                }
            }

            // 兼容 GET /action?action=pause
            session.method == Method.GET && uri == "/action" -> {
                val action = (session.parms["action"] ?: "").trim().lowercase()
                if (action.isEmpty()) jsonError(400, "missing action")
                else {
                    val extras = session.parms?.mapValues { it.value ?: "" } ?: emptyMap()
                    onAction(action, extras)
                    jsonOk("""{"ok":true,"action":"$action"}""")
                }
            }

            else -> jsonError(404, "not found")
        }
    }

    private fun jsonOk(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", body)

    private fun jsonError(code: Int, msg: String): Response {
        val status = when (code) {
            400 -> Response.Status.BAD_REQUEST
            404 -> Response.Status.NOT_FOUND
            else -> Response.Status.INTERNAL_ERROR
        }
        return newFixedLengthResponse(status, "application/json", """{"ok":false,"error":"$msg"}""")
    }

    companion object {
        const val DEFAULT_PORT = 9978
        private const val TAG = "RemoteControlServer"
    }
}
