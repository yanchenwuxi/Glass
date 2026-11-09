package com.glassbox.app.engine

import android.content.Context
import android.util.Log
import com.glassbox.app.GlassApp
import com.glassbox.app.core.Net
import com.glassbox.app.data.HomeSection
import com.glassbox.app.data.MediaDetail
import com.glassbox.app.data.MediaItem
import com.glassbox.app.data.SectionStyle
import com.glassbox.app.data.SiteCatalog
import com.glassbox.app.data.SiteCategory
import com.glassbox.app.data.SiteDef
import com.google.gson.JsonParser
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * TVBox `csp_` 站点：下载配置顶层 `spider` 指向的 jar，用 DexClassLoader 加载
 * `com.github.catvod.spider.<Name>`，反射调用 TVBox 的 Spider 契约。
 *
 * 已验证该 jar 只依赖 `com.github.catvod.crawler.Spider` / `SpiderDebug` 两个宿主类，
 * 二者已在本工程补齐；返回值是 CMS 风格 JSON，解析沿用 [SpiderJson]。
 */
class JarSpider(private val site: SiteDef) : SiteSpider {

    private val className = "com.github.catvod.spider." + site.api.removePrefix("csp_")

    override suspend fun home(): List<HomeSection> {
        val root = invokeJson("homeContent", true) ?: return emptyList()
        val sections = SpiderJson.sections(root, site)
        if (sections.isEmpty() && root.isJsonObject) {
            val first = SpiderJson.categories(root.asJsonObject.get("class")).firstOrNull()
            if (first != null) {
                val items = category(first.first, 1)
                if (items.isNotEmpty()) {
                    return listOf(HomeSection(first.second, "来自 ${site.name}", items, SectionStyle.HERO))
                }
            }
        }
        return sections
    }

    override suspend fun catalog(): SiteCatalog {
        val root = invokeJson("homeContent", true) ?: return SiteCatalog()
        val obj = root.takeIf { it.isJsonObject }?.asJsonObject ?: return SiteCatalog()
        return SiteCatalog(
            categories = SpiderJson.categories(obj.get("class")).map { SiteCategory(it.first, it.second) },
            filters = SpiderJson.filters(obj.get("filters"))
        )
    }

    override suspend fun category(tid: String, page: Int, extend: Map<String, String>): List<MediaItem> {
        val root = invokeJson("categoryContent", tid, page.toString(), false, HashMap(extend)) ?: return emptyList()
        val list = root.takeIf { it.isJsonObject }?.asJsonObject?.get("list") ?: root
        return SpiderJson.items(list, site)
    }

    override suspend fun search(keyword: String): List<MediaItem> {
        val root = invokeJson("searchContent", keyword, false) ?: return emptyList()
        val list = root.takeIf { it.isJsonObject }?.asJsonObject?.get("list") ?: root
        return SpiderJson.items(list, site)
    }

    override suspend fun detail(id: String): MediaDetail? {
        val root = invokeJson("detailContent", listOf(id)) ?: return null
        return SpiderJson.detail(root, site, id)
    }

    override suspend fun resolvePlay(flag: String, id: String): PlaySource? {
        val raw = invoke("playerContent", flag, id, emptyList<String>()) ?: return null
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val url = SpiderJson.str(obj, "url", "playUrl")
        if (url.isBlank()) {
            Log.i("GlassJar", "playerContent 无 url：$raw")
            return null
        }
        return PlaySource(Net.normalizeUrl(url))
    }

    // ---------- 反射与加载 ----------

    private suspend fun invokeJson(method: String, vararg args: Any?): com.google.gson.JsonElement? {
        val raw = invoke(method, *args) ?: return null
        val trimmed = raw.trim()
        if (trimmed.startsWith("<")) {
            Log.i("GlassJar", "$method 返回非 JSON（${trimmed.length} 字节）：${trimmed.take(60)}")
            return null
        }
        return runCatching { JsonParser.parseString(trimmed) }
            .onFailure { Log.i("GlassJar", "$method JSON 解析失败：${it.message} raw=${trimmed.take(120)}") }
            .getOrNull()
    }

    private suspend fun invoke(method: String, vararg args: Any?): String? = withContext(Dispatchers.IO) {
        val instance = instance() ?: return@withContext null
        try {
            val target = findMethod(instance.javaClass, method, args) ?: run {
                Log.i("GlassJar", "缺少方法 $method：${instance.javaClass.name}")
                return@withContext null
            }
            withTimeout(25_000) { target.invoke(instance, *args) as? String }
                ?.also { Log.i("GlassJar", "$method 返回 ${it.length} 字节") }
        } catch (t: Throwable) {
            val cause = unwrap(t)
            Log.i("GlassJar", "$method 调用失败：${cause.message}\n${Log.getStackTraceString(cause)}")
            throw IllegalStateException("JAR 站点调用失败：${cause.message ?: cause.javaClass.name}", cause)
        }
    }

    /** 反射调用会把真实异常包一层 InvocationTargetException，日志里必须展开到根因。 */
    private fun unwrap(t: Throwable): Throwable {
        var cur: Throwable = t
        while (true) {
            cur = when {
                cur is java.lang.reflect.InvocationTargetException && cur.targetException != null -> cur.targetException!!
                cur is java.util.concurrent.ExecutionException && cur.cause != null -> cur.cause!!
                else -> return cur
            }
        }
    }

    private fun findMethod(cls: Class<*>, name: String, args: Array<out Any?>): java.lang.reflect.Method? =
        cls.methods.firstOrNull { m ->
            if (m.name != name || m.parameterCount != args.size) return@firstOrNull false
            m.parameterTypes.indices.all { i ->
                val p = m.parameterTypes[i]
                when {
                    args[i] == null -> !p.isPrimitive
                    p == Boolean::class.javaPrimitiveType || p == java.lang.Boolean::class.java ->
                        args[i] is Boolean
                    p == Int::class.javaPrimitiveType || p == java.lang.Integer::class.java ->
                        args[i] is Int
                    p == String::class.java || p == java.lang.String::class.java ->
                        args[i] is String
                    p == List::class.java || p == java.util.List::class.java ->
                        args[i] is List<*>
                    p == HashMap::class.java || p == java.util.HashMap::class.java ->
                        args[i] is Map<*, *>
                    else -> p.isAssignableFrom(args[i]!!.javaClass)
                }
            }
        }

    /**
     * 原宿主在 Application 启动时调用 `InitOrigin.d(context, bridgeUrl)` 注册桥接，
     * FishGuard 的 so 与加密 ext 解密都依赖这一步；缺它时部分站点的 init 会抛
     * 「地址格式错误」「bridge URL is invalid」。
     */
    private fun initHostBridge(loader: ClassLoader, context: Context) {
        if (bridgedLoaders.contains(loader)) return
        runCatching {
            val init = loader.loadClass("com.github.catvod.spider.InitOrigin")
            init.getMethod("d", Context::class.java, String::class.java)
                .invoke(null, context, HOST_BRIDGE_URL)
            bridgedLoaders.add(loader)
            Log.i("GlassJar", "宿主桥接已注册：$HOST_BRIDGE_URL")
        }.onFailure {
            Log.i("GlassJar", "宿主桥接注册失败：${unwrap(it).message}")
        }
    }

    private suspend fun instance(): Any? {
        val jarUrl = site.jarUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "配置没有声明 spider jar 地址（api=${site.api}，jar=${site.jarRaw ?: "空"}）"
            )
        val key = "$jarUrl|$className|${site.ext.orEmpty()}"
        cache[key]?.let { return it }
        val context = GlassApp.instance
        val jar = when {
            jarUrl.startsWith("http://") || jarUrl.startsWith("https://") ->
                downloadJar(context, jarUrl) ?: throw IllegalStateException(
                    "JAR 下载失败（原始 jar=${site.jarRaw ?: "空"}，解析地址=$jarUrl）"
                )
            else -> File(jarUrl.removePrefix("file://")).takeIf { it.isFile }
                ?: throw IllegalStateException(
                    "JAR 地址非法：原始 jar=${site.jarRaw ?: "空"}，解析结果=$jarUrl（既不是 http(s) 也不是存在的本地文件）"
                )
        }
        val libDir = runCatching { extractNative(jar) }.getOrNull()
        // Android 8+ 禁止从「可写」路径加载 dex：把 jar 拷到 codeCache 并标记只读
        val safeJar = prepareReadonlyJar(context, jar)
        val optDir = File(context.codeCacheDir, "jar_opt").apply { mkdirs() }
        val loader = DexClassLoader(
            safeJar.absolutePath,
            optDir.absolutePath,
            libDir?.absolutePath,
            context.classLoader
        )
        val cls = try {
            loader.loadClass(className)
        } catch (t: Throwable) {
            val cause = unwrap(t)
            Log.i("GlassJar", "类加载失败 $className：${Log.getStackTraceString(cause)}")
            throw IllegalStateException(
                "JAR 类加载失败：$className（原始 jar=${site.jarRaw ?: "顶层 spider"}，解析地址=$jarUrl）：${cause.message}",
                cause
            )
        }
        // Fish 系列 jar 的加密 ext 依赖宿主先注册桥接（InitOrigin.d），
        // 否则 FishCrypto 解不出站点地址，表现为「地址格式错误 / bridge URL is invalid」。
        initHostBridge(loader, context)
        val obj = try {
            cls.getDeclaredConstructor().newInstance()
        } catch (t: Throwable) {
            val cause = unwrap(t)
            Log.i("GlassJar", "实例化失败 $className：${Log.getStackTraceString(cause)}")
            throw IllegalStateException(
                "JAR 实例化失败：$className（原始 jar=${site.jarRaw ?: "顶层 spider"}）：" +
                    "${cause.message ?: cause.javaClass.name}（缺宿主支撑类时会出现 NoClassDefFoundError）",
                cause
            )
        }
        try {
            cls.getMethod("init", Context::class.java, String::class.java).invoke(obj, context, site.ext.orEmpty())
        } catch (t: Throwable) {
            val cause = unwrap(t)
            Log.i("GlassJar", "init 失败 $className：${cause.message}\n${Log.getStackTraceString(cause)}")
            throw IllegalStateException(
                "JAR 初始化失败：${cause.message ?: cause.javaClass.name}" +
                    "（api=${site.api}，jar=${site.jarRaw ?: "顶层 spider"}，解析地址=$jarUrl）",
                cause
            )
        }
        Log.i("GlassJar", "已加载 $className（jar=${jar.length()} 字节）")
        cache[key] = obj
        return obj
    }

    private suspend fun downloadJar(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "jars").apply { mkdirs() }
        val file = File(dir, md5(url) + ".jar")
        if (file.isFile && file.length() > 0) {
            lockJar(file)
            return@withContext file
        }
        runCatching {
            val request = Request.Builder().url(Net.normalizeUrl(url)).header("User-Agent", Net.UA).build()
            Net.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.bytes()?.let { bytes ->
                    val tmp = File(dir, file.name + ".part")
                    tmp.writeBytes(bytes)
                    if (file.exists()) file.delete()
                    tmp.renameTo(file)
                }
            }
            lockJar(file)
            file
        }.onFailure { Log.i("GlassJar", "JAR 下载失败 $url：${it.message}") }.getOrNull()
    }

    /**
     * 将 jar 复制到 codeCache 并 setReadOnly，规避
     * "Writable dex file ... is not allowed"。
     */
    private fun prepareReadonlyJar(context: Context, src: File): File {
        val dir = File(context.codeCacheDir, "jars").apply { mkdirs() }
        val dst = File(dir, src.name)
        if (!dst.isFile || dst.length() != src.length() || dst.lastModified() < src.lastModified()) {
            // 目标可能是只读，先允许写再覆盖
            if (dst.exists()) {
                dst.setWritable(true)
                dst.delete()
            }
            src.copyTo(dst, overwrite = true)
        }
        lockJar(dst)
        return dst
    }

    private fun lockJar(file: File) {
        runCatching {
            file.setReadable(true, false)
            file.setWritable(false, false)
            file.setReadOnly()
        }
    }

    /** jar 里 assets 下的 .so 需要解出来放到 DexClassLoader 的 native 目录。 */
    private fun extractNative(jar: File): File? {
        val dir = File(jar.parentFile, jar.nameWithoutExtension + "_lib").apply { mkdirs() }
        var any = false
        runCatching {
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith(".so") && is64BitElf(zip, it) }
                    .forEach { entry ->
                        val out = File(dir, entry.name.substringAfterLast('/'))
                        if (!out.exists()) zip.getInputStream(entry).use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                        any = true
                        runCatching { System.load(out.absolutePath) }
                            .onFailure { Log.i("GlassJar", "加载 ${out.name} 失败：${it.message}") }
                    }
            }
        }
        return if (any) dir else null
    }

    /** 设备是 64 位，jar 里同时带 v7(32 位)/v8(64 位) 两个 .so，只解压并加载 64 位那个。 */
    private fun is64BitElf(zip: ZipFile, entry: java.util.zip.ZipEntry): Boolean = runCatching {
        zip.getInputStream(entry).use { input ->
            val head = ByteArray(6)
            val n = input.read(head)
            n >= 6 && head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte() && head[4] == 2.toByte()
        }
    }.getOrDefault(false)

    private fun md5(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** 原宿主的本地桥接地址；FishGuard 仅要求它是合法 http URL。 */
        private const val HOST_BRIDGE_URL = "http://127.0.0.1:9978/proxy"
        private val cache = mutableMapOf<String, Any>()
        /** DexClassLoader 各自持有独立的 InitOrigin 静态状态，桥接需按 loader 注册。 */
        private val bridgedLoaders = java.util.Collections.newSetFromMap(
            java.util.WeakHashMap<ClassLoader, Boolean>()
        )
    }
}
