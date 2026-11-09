package com.glassbox.app.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 视觉 token 层：圆角。对齐 02 §2.2「圆角」表：
 * 卡片 20dp / 剧集 chip 12dp / 筛选 chip 全圆 / 导航胶囊 27dp / 圆形按钮全圆。
 */
@Immutable
data class GlassShapes(
    val xs: RoundedCornerShape = RoundedCornerShape(8.dp),
    val sm: RoundedCornerShape = RoundedCornerShape(12.dp),
    val md: RoundedCornerShape = RoundedCornerShape(12.dp),
    val lg: RoundedCornerShape = RoundedCornerShape(12.dp),
    val card: RoundedCornerShape = RoundedCornerShape(20.dp),
    val panel: RoundedCornerShape = RoundedCornerShape(20.dp),
    val dialog: RoundedCornerShape = RoundedCornerShape(20.dp),
    val sheet: RoundedCornerShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(50)
) {
    /** 图标按钮为圆形（原版 43dp 圆钮）。 */
    fun iconButton(size: Dp): RoundedCornerShape = RoundedCornerShape(size / 2)
}

/**
 * 视觉 token 层：间距。页面左右边距 16dp（02 §2.2）。
 */
@Immutable
data class GlassSpacing(
    val hair: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 6.dp,
    val md: Dp = 8.dp,
    val lg: Dp = 12.dp,
    val xl: Dp = 12.dp,
    val xxl: Dp = 16.dp,
    val xxxl: Dp = 16.dp,
    val block: Dp = 24.dp,
    val section: Dp = 24.dp,
    val screen: Dp = 16.dp
)

/**
 * 视觉 token 层：动效。
 */
@Immutable
data class GlassMotion(
    val quickMs: Int = 180,
    val normalMs: Int = 260,
    val slowMs: Int = 420,
    val sheetMs: Int = 280,
    val carouselMs: Long = 6200,
    val auroraMs: Int = 26000,
    val progressTickMs: Long = 420,
    val hintMs: Long = 1100,
    val controlsIdleMs: Long = 3200,
    val enterEasing: Easing = FastOutSlowInEasing,
    val exitEasing: Easing = LinearOutSlowInEasing,
    val ambientEasing: Easing = LinearEasing
)

/**
 * 视觉 token 层：尺寸。数值来自 02 §2.4 逐页实测（px/2.75）。
 */
@Immutable
data class GlassDimens(
    val heroHeight: Dp = 436.dp,
    val heroAspect: Float = 0.768f,
    val posterAspect: Float = 2f / 3f,
    val posterWidth: Dp = 118.dp,
    val posterSpacing: Dp = 30.5.dp,
    val continueWidth: Dp = 212.dp,
    val continueHeight: Dp = 120.dp,
    val sheetMaxHeight: Dp = 480.dp,
    val importSheetMaxHeight: Dp = 640.dp,
    val dialogMaxHeight: Dp = 420.dp,
    val fabSize: Dp = 64.dp,
    val iconButton: Dp = 43.dp,
    val navHeight: Dp = 54.dp,
    val navBottomPadding: Dp = 10.dp,
    val homeBottomInset: Dp = 96.dp,
    val mainButtonHeight: Dp = 72.dp,
    val quickActionWidth: Dp = 70.dp,
    val quickActionHeight: Dp = 50.dp,
    val episodeChipHeight: Dp = 38.dp,
    val pageHorizontal: Dp = 16.dp
)

/**
 * 视觉 token 层：层级强度。新设计靠「底色差」分层，阴影仅用于悬浮件。
 */
@Immutable
data class GlassElevation(
    val level1: Dp = 0.dp,
    val level2: Dp = 0.dp,
    val level3: Dp = 8.dp,
    val level4: Dp = 14.dp,
    val cta: Dp = 0.dp,
    val borderWidth: Float = 1f,
    val highlightInset: Float = 18f,
    val highlightWidth: Float = 0f,
    val lightFillFactor: Float = 1f,
    val darkTopFactor: Float = 1f,
    val darkMidFactor: Float = 1f,
    val darkBottomFactor: Float = 1f,
    val lightTopFactor: Float = 1f,
    val lightMidFactor: Float = 1f,
    val lightBottomFactor: Float = 1f
) {
    fun shadowFor(level: Int): Dp = when (level) {
        1 -> level1
        2 -> level2
        3 -> level3
        else -> level4
    }
}
