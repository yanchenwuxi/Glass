package com.github.catvod.crawler

import android.content.Context

/**
 * TVBox JAR spider 的宿主基类。
 *
 * fish.json 这类配置里 `api` 是 `csp_Xxx`，脚本实现放在配置顶层 `spider` 指向的 jar 里；
 * jar 的 dex 只引用 `com.github.catvod.crawler.Spider` / `SpiderDebug` 两个宿主类
 * （`dexdump` 核对过），所以本工程补上这两个类后就能用 DexClassLoader 直接加载。
 * 方法签名按 TVBox 的公开契约声明，保持 open，供 dex 里的子类覆写。
 */
open class Spider {

    open fun init(context: Context, extend: String?) {
    }

    open fun init(context: Context) {
    }

    open fun homeContent(filter: Boolean): String = ""

    open fun homeVideoContent(): String = ""

    open fun categoryContent(
        tid: String?,
        pg: String?,
        filter: Boolean,
        extend: HashMap<String, String>?
    ): String = ""

    open fun detailContent(ids: List<String>?): String = ""

    open fun searchContent(key: String?, quick: Boolean): String = ""

    open fun searchContent(key: String?, quick: Boolean, pg: String?): String = ""

    open fun playerContent(flag: String?, id: String?, vipFlags: List<String>?): String = ""

    open fun manualVideoCheck(): Boolean = false

    open fun isVideoFormat(url: String?): Boolean = false

    open fun proxy(params: HashMap<String, String>?): Array<Any>? = null

    open fun proxyLocal(params: HashMap<String, String>?): Array<Any>? = null
}
