package com.glassbox.app.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地代理净化后的 m3u8（TVBox RemoteServer proxyM3u8 同思路）。
 * 播放器仍从原 CDN 拉 ts；本服务只吐清洗后的播放列表。
 */
class M3u8ProxyServer(port: Int = DEFAULT_PORT) : NanoHTTPD(port) {

    data class Entry(val content: String, val createdAt: Long = System.currentTimeMillis())

    private val store = ConcurrentHashMap<String, Entry>()

    @Volatile
    var running: Boolean = false
        private set

    fun put(content: String): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        store[token] = Entry(content)
        // 简单淘汰：超过 20 条清最旧
        if (store.size > 20) {
            store.entries.sortedBy { it.value.createdAt }.take(store.size - 16).forEach { store.remove(it.key) }
        }
        return token
    }

    fun proxyUrl(token: String): String = "http://127.0.0.1:$listeningPort/m3u8/$token/index.m3u8"

    override fun start() {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            running = true
            Log.i(TAG, "M3u8Proxy started on $listeningPort")
        } catch (e: IOException) {
            running = false
            Log.e(TAG, "M3u8Proxy start failed", e)
            throw e
        }
    }

    override fun stop() {
        super.stop()
        running = false
        store.clear()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        // /m3u8/{token}/index.m3u8
        val parts = uri.trim('/').split('/')
        if (parts.size >= 2 && parts[0] == "m3u8") {
            val token = parts[1]
            val entry = store[token]
                ?: return newFixedLengthResponse(Response.Status.GONE, "text/plain", "expired")
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/vnd.apple.mpegurl",
                entry.content
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "GlassBox M3U8 proxy")
    }

    companion object {
        const val DEFAULT_PORT = 9979
        private const val TAG = "M3u8Proxy"
    }
}
