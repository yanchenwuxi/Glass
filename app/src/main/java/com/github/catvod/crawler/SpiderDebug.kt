package com.github.catvod.crawler

import android.util.Log

/** JAR spider 里用到的日志宿主类；dex 只调用 log(String) / log(Throwable)。 */
object SpiderDebug {
    @JvmStatic
    fun log(message: String?) {
        Log.d("GlassJar", message ?: "")
    }

    @JvmStatic
    fun log(throwable: Throwable?) {
        Log.d("GlassJar", Log.getStackTraceString(throwable))
    }
}
