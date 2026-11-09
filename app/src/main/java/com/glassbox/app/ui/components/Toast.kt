package com.glassbox.app.ui.components

import android.content.Context
import android.widget.Toast

/**
 * 全局 Toast 出口。
 *
 * 页面不再直接依赖 `android.widget.Toast`；新设计规范下发后，
 * 只需把这里替换为玻璃浮层（例如基于 `GlassSurface` 的自绘 Toast），
 * 所有调用点无需改动。
 */
fun showToast(context: Context, message: String, long: Boolean = false) {
    Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}
