package com.glassbox.app.engine

import com.glassbox.app.core.Net
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * drpy 选择器子集（原版由 cheerio 提供，QuickJS 侧无法离线跑 cheerio，这里用 jsoup 等价实现）：
 * - `&&`：逐级向下查找；
 * - `--`：取「后续兄弟」；
 * - `:eq(n)` / `:lt(n)` / `:gt(n)`（n 可为负，`-1` 表示倒数第一个）；
 * - 逗号并列、空格后代、`#id` / `.class` / 标签。
 * 字段：`Text` 取文本、`Html` 取内部 HTML、其余按属性名取值（href/src 自动补全为绝对地址）。
 */
object HtmlDom {

    private val ATTRS = setOf(
        "href", "src", "title", "alt", "value", "content", "poster", "datetime", "id", "class"
    )

    fun document(html: String, base: String = ""): Document {
        val doc = Jsoup.parse(html)
        if (base.isNotBlank()) doc.setBaseUri(Net.normalizeUrl(base))
        return doc
    }

    fun pdfh(html: String, spec: String, base: String = ""): String {
        val segments = spec.split(";")
        var nodeSel = segments.firstOrNull().orEmpty()
        var field = segments.getOrNull(1)?.trim().orEmpty()
        if (field.isBlank()) {
            val chain = nodeSel.split("&&").map { it.trim() }.filter { it.isNotEmpty() }
            val last = chain.lastOrNull().orEmpty()
            if (last == "Text" || last == "Html" || isAttr(last)) {
                field = last
                nodeSel = chain.dropLast(1).joinToString("&&")
            }
        }
        val el = select(document(html, base), nodeSel).firstOrNull() ?: return ""
        return if (field.isBlank()) el.text().trim() else value(el, field, base)
    }

    fun pdfa(html: String, spec: String, base: String = ""): List<String> {
        val nodeSel = spec.split(";").firstOrNull().orEmpty()
        return select(document(html, base), nodeSel).map { it.outerHtml() }
    }

    fun select(root: Element, selector: String): List<Element> {
        if (selector.isBlank()) return emptyList()
        if (',' in selector) {
            return selector.split(',').flatMap { select(root, it.trim()) }.distinct()
        }
        var current = listOf(root)
        for (part in selector.split("&&")) {
            val p = part.trim()
            if (p.isEmpty() || p == "Text" || p == "Html") continue
            val next = mutableListOf<Element>()
            for (e in current) next += one(e, p)
            current = next
            if (current.isEmpty()) return emptyList()
        }
        return current
    }

    private fun one(scope: Element, part: String): List<Element> {
        if ("--" in part) {
            var list = one(scope, part.substringBefore("--").trim())
            for (seg in part.substringAfter("--").split("--")) {
                val s = seg.trim()
                if (s.isEmpty()) continue
                list = list.mapNotNull { e ->
                    var sib = e.nextElementSibling()
                    while (sib != null && !matches(sib, s)) sib = sib.nextElementSibling()
                    sib
                }
            }
            return list
        }
        // `.a:eq(0) li` 这类「带索引的后代选择」需要按空格分段逐级处理，
        // 否则索引会作用在最终结果上。
        if (' ' in part && hasIndex(part)) {
            var cur = listOf(scope)
            for (seg in part.split(' ')) {
                val s = seg.trim()
                if (s.isEmpty()) continue
                cur = cur.flatMap { one(it, s) }
                if (cur.isEmpty()) return emptyList()
            }
            return cur
        }
        val (clean, ops) = extractOps(part)
        val base = if (clean.isBlank()) scope.children().toList()
        else runCatching { scope.select(clean).toList() }.getOrDefault(emptyList())
        return applyOps(base, ops)
    }

    private fun hasIndex(part: String): Boolean = part.contains(":eq(") || part.contains(":lt(") || part.contains(":gt(")

    private fun extractOps(part: String): Pair<String, List<Pair<String, Int>>> {
        val ops = mutableListOf<Pair<String, Int>>()
        val re = Regex(":(eq|lt|gt)\\((-?\\d+)\\)")
        re.findAll(part).forEach { m -> ops += m.groupValues[1] to m.groupValues[2].toInt() }
        return part.replace(re, "").trim() to ops
    }

    private fun applyOps(list: List<Element>, ops: List<Pair<String, Int>>): List<Element> {
        var cur = list
        for ((op, n) in ops) {
            cur = when (op) {
                "eq" -> listOfNotNull(cur.getOrNull(if (n < 0) cur.size + n else n))
                "lt" -> cur.take(if (n < 0) cur.size + n else n)
                "gt" -> cur.drop((if (n < 0) cur.size + n else n) + 1)
                else -> cur
            }
        }
        return cur
    }

    private fun matches(el: Element, sel: String): Boolean {
        if (sel.isBlank()) return false
        val s = sel.trim()
        return when {
            s.startsWith(".") -> el.hasClass(s.substring(1))
            s.startsWith("#") -> el.id() == s.substring(1)
            else -> el.tagName().equals(s, true)
        }
    }

    /** 按字段描述从节点取值，支持 `a&&title` 这类「先下钻再取属性」。 */
    fun value(el: Element, field: String, base: String = ""): String {
        val chain = field.split("&&").map { it.trim() }.filter { it.isNotEmpty() }
        if (chain.isEmpty()) return el.text().trim()
        val last = chain.last()
        var node = el
        if (chain.size > 1 && last != "Text" && last != "Html") {
            node = select(el, chain.dropLast(1).joinToString("&&")).firstOrNull() ?: return ""
        }
        return when (last) {
            "Text" -> node.text().trim()
            "Html" -> node.html()
            else -> attr(node, last, base)
        }
    }

    private fun attr(el: Element, name: String, base: String): String {
        val raw = el.attr(name).trim()
        if (raw.isBlank()) return ""
        return if ((name == "href" || name == "src") && raw != "#") abs(base, raw) else raw
    }

    private fun isAttr(token: String): Boolean =
        token.startsWith("data-") || token in ATTRS

    private fun abs(base: String, url: String): String = when {
        base.isBlank() || url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> Net.joinUrl(base, url)
    }
}
