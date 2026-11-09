package com.glassbox.app.core

/** 已知的源文件类型后缀，仅用于展示名截断。 */
private val SOURCE_SUFFIXES = setOf("json", "js", "py", "txt", "m3u", "m3u8")

/**
 * 统一的展示名：只有当最后一个「.」后面是已知源类型后缀时才截断，
 * 不会误伤 `A.B.C`、`v1.2` 这类正常名字。
 *
 * 仅用于 UI 展示；内部 key、文件名、类型判定仍使用原始名称。
 */
fun displayName(name: String): String {
    val raw = name.trim()
    val dot = raw.lastIndexOf('.')
    if (dot <= 0) return raw
    val ext = raw.substring(dot + 1).lowercase()
    return if (ext in SOURCE_SUFFIXES) raw.substring(0, dot) else raw
}
