package com.glassbox.app.engine

import android.util.Base64
import android.util.Log
import com.glassbox.app.core.Net
import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * 海阔视界分享口令解析。
 *
 * 常见形态（公开文档 / 社区约定）：
 * 1. `￥home_rule_url￥https://...json` —— 仓库/规则列表 URL
 * 2. `rule://` + Base64(完整口令文本，含「海阔视界」字样)
 * 3. 整段口令再 Base64 一次
 * 4. 正文里直接夹带 http(s) 链接
 * 5. pasteme / 云剪切板类短链
 *
 * 解码目标：得到可交给 [SourceParser] 的「配置文本」或「可下载 URL」。
 */
object HikerShare {

    data class DecodeResult(
        val kind: Kind,
        val url: String? = null,
        val text: String? = null,
        val title: String? = null,
        val note: String = ""
    ) {
        enum class Kind { URL, TEXT }
    }

    private val yenPair = Pattern.compile("￥([^￥]+)￥\\s*(https?://\\S+|hiker://\\S+|rule://\\S+)")
    private val dollarPair = Pattern.compile("\\$([^$]+)\\$\\s*(https?://\\S+)")
    private val anyUrl = Pattern.compile("(https?://[^\\s\"'<>]+)")
    private val ruleScheme = Pattern.compile("(?i)rule://([A-Za-z0-9_\\-+=/]+)")
    private val cloudCode = Pattern.compile("(?:云[A-Za-z0-9]+|[A-Za-z]{2,6})/([A-Za-z0-9_\\-]{6,})/([A-Za-z0-9_\\-]{6,})")
    private val pasteme = Pattern.compile("(?i)https?://pasteme\\.[^\\s/]+/[^\\s]+")

    /**
     * 解析口令；网络拉取（home_rule_url / pasteme）在 [resolve] 中完成。
     */
    fun parseLocal(raw: String): DecodeResult {
        val input = raw.trim().replace("\uFEFF", "")
        if (input.isBlank()) {
            return DecodeResult(DecodeResult.Kind.TEXT, text = "", note = "口令为空")
        }

        // 1) rule://base64
        ruleScheme.matcher(input).let { m ->
            if (m.find()) {
                val decoded = decodeBase64(m.group(1)!!)
                if (!decoded.isNullOrBlank()) {
                    val nested = parseLocal(decoded)
                    if (nested.url != null || !nested.text.isNullOrBlank()) return nested
                    return DecodeResult(DecodeResult.Kind.TEXT, text = decoded, title = guessTitle(decoded), note = "rule:// Base64")
                }
            }
        }

        // 2) ￥tag￥url 或 $tag$url
        yenPair.matcher(input).let { m ->
            if (m.find()) {
                val tag = m.group(1)!!.trim()
                val body = m.group(2)!!.trim().trimEnd('，', ',', '。', '.')
                if (body.startsWith("rule://", true)) {
                    return parseLocal(body)
                }
                if (body.startsWith("http")) {
                    return DecodeResult(
                        DecodeResult.Kind.URL,
                        url = body,
                        title = tag.ifBlank { null },
                        note = "标记 $tag"
                    )
                }
            }
        }
        dollarPair.matcher(input).let { m ->
            if (m.find()) {
                val tag = m.group(1)!!.trim()
                val body = m.group(2)!!.trim().trimEnd('，', ',', '。', '.')
                if (body.startsWith("http")) {
                    return DecodeResult(DecodeResult.Kind.URL, url = body, title = tag.ifBlank { null }, note = "标记 $tag")
                }
            }
        }

        // 3) pasteme 链接
        pasteme.matcher(input).let { m ->
            if (m.find()) {
                return DecodeResult(DecodeResult.Kind.URL, url = m.group()!!.trimEnd('，', ',', '。', '.'), note = "pasteme")
            }
        }

        // 4) 正文中的 http(s)
        val urls = mutableListOf<String>()
        anyUrl.matcher(input).let { m ->
            while (m.find()) {
                val u = m.group(1)!!.trimEnd('，', ',', '。', '.', ')', ']')
                if (!u.contains("github.com/login") && u.length > 12) urls += u
            }
        }
        if (urls.size == 1) {
            return DecodeResult(DecodeResult.Kind.URL, url = urls[0], title = guessTitle(input), note = "口令内链接")
        }
        if (urls.size > 1) {
            // 多个链接时优先 json 结尾
            val prefer = urls.firstOrNull { it.contains(".json", true) } ?: urls.first()
            return DecodeResult(DecodeResult.Kind.URL, url = prefer, title = guessTitle(input), note = "多链接取优先")
        }

        // 5) 云剪切板短码 → 拼 pasteme（社区常用形态）
        cloudCode.matcher(input).let { m ->
            if (m.find()) {
                val code = m.group(2)!!
                val url = "https://pasteme.tyrantg.com/api/$code"
                return DecodeResult(DecodeResult.Kind.URL, url = url, note = "云口令→pasteme")
            }
        }

        // 6) 整段像 Base64
        if (looksLikeBase64(input)) {
            val decoded = decodeBase64(input)
            if (!decoded.isNullOrBlank() && decoded != input) {
                val nested = parseLocal(decoded)
                if (nested.url != null || (nested.text != null && nested.text.length > 20)) return nested
                return DecodeResult(DecodeResult.Kind.TEXT, text = decoded, title = guessTitle(decoded), note = "整段 Base64")
            }
        }

        // 7) 已是规则/配置正文
        return DecodeResult(
            DecodeResult.Kind.TEXT,
            text = input,
            title = guessTitle(input),
            note = if (input.contains("海阔") || input.contains("hiker", true)) "口令正文" else "纯文本"
        )
    }

    /**
     * 若结果是 URL，则下载正文；pasteme API 可能返回 JSON 包装。
     */
    suspend fun resolve(raw: String): DecodeResult {
        val local = parseLocal(raw)
        if (local.kind == DecodeResult.Kind.TEXT) return local
        val url = local.url ?: return local
        val body = runCatching { Net.get(url, retries = 1) }.getOrElse {
            Log.w("HikerShare", "fetch fail $url: ${it.message}")
            return DecodeResult(DecodeResult.Kind.URL, url = url, title = local.title, note = "拉取失败：${it.message}")
        }
        val unwrapped = unwrapPasteme(body) ?: body.trim()
        // 下载结果仍可能是口令或再套一层
        if (unwrapped.contains("￥") || unwrapped.startsWith("rule://") || looksLikeBase64(unwrapped.take(80))) {
            val again = parseLocal(unwrapped)
            if (again.kind == DecodeResult.Kind.URL && again.url != null && again.url != url) {
                val second = runCatching { Net.get(again.url, retries = 1) }.getOrNull()
                if (!second.isNullOrBlank()) {
                    return DecodeResult(
                        DecodeResult.Kind.TEXT,
                        text = unwrapPasteme(second) ?: second,
                        title = local.title ?: again.title ?: guessTitle(second),
                        note = "二级展开"
                    )
                }
            }
            if (again.kind == DecodeResult.Kind.TEXT && !again.text.isNullOrBlank()) {
                return again.copy(title = again.title ?: local.title)
            }
        }
        return DecodeResult(
            DecodeResult.Kind.TEXT,
            text = unwrapped,
            title = local.title ?: guessTitle(unwrapped),
            note = local.note.ifBlank { "已下载" }
        )
    }

    private fun unwrapPasteme(body: String): String? {
        val t = body.trim()
        if (!t.startsWith("{")) return null
        return runCatching {
            val o = com.google.gson.JsonParser.parseString(t).asJsonObject
            sequenceOf("content", "data", "text", "paste", "message")
                .mapNotNull { key -> o.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
                .firstOrNull { it.isNotBlank() }
        }.getOrNull()
    }

    private fun guessTitle(text: String): String? {
        val patterns = listOf(
            Pattern.compile("\"title\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("【([^】]{1,40})】"),
            Pattern.compile("《([^》]{1,40})》")
        )
        for (p in patterns) {
            val m = p.matcher(text)
            if (m.find()) return m.group(1)
        }
        if (text.contains("海阔视界")) return "海阔规则"
        return null
    }

    private fun looksLikeBase64(s: String): Boolean {
        val t = s.trim().replace("\\s".toRegex(), "")
        if (t.length < 16 || t.length % 4 == 1) return false
        return t.matches(Regex("^[A-Za-z0-9_\\-+/=]+$"))
    }

    private fun decodeBase64(s: String): String? {
        val cleaned = s.trim().replace("\\s".toRegex(), "").replace('-', '+').replace('_', '/')
        val pad = when (cleaned.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        return runCatching {
            val bytes = Base64.decode(cleaned + pad, Base64.DEFAULT)
            String(bytes, Charset.forName("UTF-8"))
        }.getOrNull()?.takeIf { it.any { c -> c.code in 32..126 || c.isIdeographic() || c == '\n' || c == '\r' || c == '\t' } }
    }

    private fun Char.isIdeographic(): Boolean = Character.isIdeographic(this.code)
}
