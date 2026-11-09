package com.glassbox.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * 主题门面：页面唯一的视觉取值入口。
 *
 * - 色板：[colors]（亮/暗由 [GlassBoxTheme] 注入）
 * - 圆角：[shapes]
 * - 间距：[spacing]
 * - 动效：[motion]
 * - 尺寸：[dimens]
 * - 玻璃强度：[elevation]
 *
 * 1.0.x 的 cardShape / panelShape / sheetShape / pillShape 保留为别名，
 * 便于逐步迁移；新代码请直接用 [shapes]。
 */
object GlassTheme {
    val colors: GlassPalette
        @Composable get() = LocalGlassPalette.current

    val shapes: GlassShapes = GlassShapes()
    val spacing: GlassSpacing = GlassSpacing()
    val motion: GlassMotion = GlassMotion()
    val dimens: GlassDimens = GlassDimens()
    val elevation: GlassElevation = GlassElevation()

    val cardShape: RoundedCornerShape get() = shapes.card
    val panelShape: RoundedCornerShape get() = shapes.panel
    val sheetShape: RoundedCornerShape get() = shapes.sheet
    val pillShape: RoundedCornerShape get() = shapes.pill

    @Composable
    fun fillFor(level: Int): Color {
        val c = colors
        return when (level) {
            1 -> c.glassFill1
            2 -> c.glassFill2
            3 -> c.glassFill3
            else -> c.glassFill4
        }
    }

    fun shadowFor(level: Int): Dp = elevation.shadowFor(level)
}

@Composable
fun GlassBoxTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.bgTop,
            surface = palette.bgMid,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            secondary = palette.secondaryAccent,
            error = palette.danger
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.bgTop,
            surface = palette.bgMid,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            secondary = palette.secondaryAccent,
            error = palette.danger
        )
    }
    CompositionLocalProvider(LocalGlassPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = GlassTypography,
            content = content
        )
    }
}
