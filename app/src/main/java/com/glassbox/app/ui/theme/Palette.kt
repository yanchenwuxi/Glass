package com.glassbox.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 视觉 token 层：色板。
 *
 * 对齐 BieCuiLe 1.1.5 的 iOS 风格令牌（biecuile_spec/02_界面设计规范.md §2.2）：
 * 暖白底 #F2F3EF + 纯白卡片 + 玫红强调 #FF2D55 + iOS 六色语义图标。
 *
 * 深色令牌原版未实测，按文档「设计补全」方向实现：
 * 底 #0E0E10、卡片 #1C1C1E、文字反相、强调色不变。
 *
 * 页面不得直接写死色值，只能通过 [GlassTheme.colors] 取语义色。
 */
@Immutable
data class GlassPalette(
    val dark: Boolean,
    // 背景（新设计为纯色底，三个字段保留以兼容旧调用）
    val bgTop: Color,
    val bgMid: Color,
    val bgBottom: Color,
    val glowMint: Color,
    val glowLilac: Color,
    val glowPeach: Color,
    // 文本
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    // 主色
    val accent: Color,
    val accentDeep: Color,
    val accentSoft: Color,
    val secondaryAccent: Color,
    val danger: Color,
    // 表面层级（卡片/弹层/输入框）
    val surface: Color,
    val surfaceElevated: Color,
    val chipBg: Color,
    val track: Color,
    val glassFill1: Color,
    val glassFill2: Color,
    val glassFill3: Color,
    val glassFill4: Color,
    val glassBorder: Color,
    val glassBorderStrong: Color,
    val topHighlight: Color,
    // 液态玻璃：模糊层上的半透明底色 / 边缘高光 / 边缘暗部 / 表面高光
    val glassTint: Color,
    val glassEdgeLight: Color,
    val glassEdgeDark: Color,
    val glassSheen: Color,
    val scrim: Color,
    val divider: Color,
    val posterPlaceholder: Color,
    val badgeBg: Color,
    // 阴影（已含透明度）
    val shadowAmbient: Color,
    val shadowSpot: Color,
    // 图像/视频叠加层上的语义色（与主题无关，保证海报与播放器上的可读性）
    val onAccent: Color,
    val onImage: Color,
    val onImageSecondary: Color,
    val onImageTertiary: Color,
    val onImageMuted: Color,
    val imageChipBg: Color,
    val imageChipBgSoft: Color,
    val imageChipText: Color,
    val posterFade: Color,
    val posterFadeSoft: Color,
    val posterFadeMid: Color,
    val posterFadeDeep: Color,
    val scoreText: Color,
    val star: Color,
    val playGlyph: Color,
    val playScrim: Color,
    // 弹层
    val dialogScrim: Color,
    // 类型徽标
    val typeJs: Color,
    val typePy: Color,
    val glowLabel: Color,
    // iOS 语义色（功能域一色，移植关键）
    val iconPurple: Color,
    val iconOrange: Color,
    val iconGreen: Color,
    val iconBlue: Color,
    val iconPink: Color,
    val iconViolet: Color,
    val iconBlueAlt: Color
)

/** 浅色令牌：真机采样值（02 §2.2）。 */
val LightPalette = GlassPalette(
    dark = false,
    bgTop = Color(0xFFF2F3EF),
    bgMid = Color(0xFFF2F3EF),
    bgBottom = Color(0xFFF2F3EF),
    glowMint = Color(0x00FFFFFF),
    glowLilac = Color(0x00FFFFFF),
    glowPeach = Color(0x00FFFFFF),
    textPrimary = Color(0xFF1C1C1E),
    textSecondary = Color(0xFF8A8A8E),
    textTertiary = Color(0xFF8A8A8E),
    accent = Color(0xFFFF2D55),
    accentDeep = Color(0xFFE01E45),
    accentSoft = Color(0x1FFF2D55),
    secondaryAccent = Color(0xFF5856D6),
    danger = Color(0xFFFF3B30),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFFFFFFF),
    chipBg = Color(0xFFF2F2F7),
    track = Color(0xFFE5E5EA),
    glassFill1 = Color(0xFFFFFFFF),
    glassFill2 = Color(0xFFFFFFFF),
    glassFill3 = Color(0xFFFFFFFF),
    glassFill4 = Color(0xFFFFFFFF),
    glassBorder = Color(0x0F000000),
    glassBorderStrong = Color(0x1F000000),
    topHighlight = Color(0x66FFFFFF),
    glassTint = Color(0xB8FFFFFF),
    glassEdgeLight = Color(0xE6FFFFFF),
    glassEdgeDark = Color(0x1F000000),
    glassSheen = Color(0x3DFFFFFF),
    scrim = Color(0x66000000),
    divider = Color(0x14000000),
    posterPlaceholder = Color(0xFFE5E5EA),
    badgeBg = Color(0xFFF2F2F7),
    shadowAmbient = Color(0x14000000),
    shadowSpot = Color(0x1A000000),
    onAccent = Color(0xFFFFFFFF),
    onImage = Color(0xFFFFFFFF),
    onImageSecondary = Color(0xFFE5E5EA),
    onImageTertiary = Color(0xFFC7C7CC),
    onImageMuted = Color(0xFFAEAEB2),
    imageChipBg = Color(0xCC000000),
    imageChipBgSoft = Color(0x66000000),
    imageChipText = Color(0xFFFFFFFF),
    posterFade = Color(0xB3000000),
    posterFadeSoft = Color(0x00000000),
    posterFadeMid = Color(0x99000000),
    posterFadeDeep = Color(0xE6000000),
    scoreText = Color(0xFFFFD60A),
    star = Color(0xFFFFD60A),
    playGlyph = Color(0xFFFFFFFF),
    playScrim = Color(0xFFFF2D55),
    dialogScrim = Color(0xFF000000),
    typeJs = Color(0xFF5856D6),
    typePy = Color(0xFFFF9500),
    glowLabel = Color(0xFFFFFFFF),
    iconPurple = Color(0xFF5856D6),
    iconOrange = Color(0xFFFF9500),
    iconGreen = Color(0xFF34C759),
    iconBlue = Color(0xFF007AFF),
    iconPink = Color(0xFFFF2D55),
    iconViolet = Color(0xFFAF52DE),
    iconBlueAlt = Color(0xFF3478F6)
)

/** 深色令牌：设计补全（原版未实测），与浅色同结构反相。 */
val DarkPalette = GlassPalette(
    dark = true,
    bgTop = Color(0xFF0E0E10),
    bgMid = Color(0xFF0E0E10),
    bgBottom = Color(0xFF0E0E10),
    glowMint = Color(0x00FFFFFF),
    glowLilac = Color(0x00FFFFFF),
    glowPeach = Color(0x00FFFFFF),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFFAEAEB2),
    textTertiary = Color(0xFF8A8A8E),
    accent = Color(0xFFFF2D55),
    accentDeep = Color(0xFFE01E45),
    accentSoft = Color(0x33FF2D55),
    secondaryAccent = Color(0xFF5856D6),
    danger = Color(0xFFFF453A),
    surface = Color(0xFF1C1C1E),
    surfaceElevated = Color(0xFF242426),
    chipBg = Color(0xFF2C2C2E),
    track = Color(0xFF3A3A3C),
    glassFill1 = Color(0xFF1C1C1E),
    glassFill2 = Color(0xFF1C1C1E),
    glassFill3 = Color(0xFF242426),
    glassFill4 = Color(0xFF2C2C2E),
    glassBorder = Color(0x1FFFFFFF),
    glassBorderStrong = Color(0x33FFFFFF),
    topHighlight = Color(0x4DFFFFFF),
    glassTint = Color(0xB81C1C1E),
    glassEdgeLight = Color(0x80FFFFFF),
    glassEdgeDark = Color(0x66000000),
    glassSheen = Color(0x26FFFFFF),
    scrim = Color(0x99000000),
    divider = Color(0x33FFFFFF),
    posterPlaceholder = Color(0xFF2C2C2E),
    badgeBg = Color(0xFF2C2C2E),
    shadowAmbient = Color(0x66000000),
    shadowSpot = Color(0x80000000),
    onAccent = Color(0xFFFFFFFF),
    onImage = Color(0xFFFFFFFF),
    onImageSecondary = Color(0xFFE5E5EA),
    onImageTertiary = Color(0xFFC7C7CC),
    onImageMuted = Color(0xFFAEAEB2),
    imageChipBg = Color(0xCC000000),
    imageChipBgSoft = Color(0x66000000),
    imageChipText = Color(0xFFFFFFFF),
    posterFade = Color(0xB3000000),
    posterFadeSoft = Color(0x00000000),
    posterFadeMid = Color(0x99000000),
    posterFadeDeep = Color(0xE6000000),
    scoreText = Color(0xFFFFD60A),
    star = Color(0xFFFFD60A),
    playGlyph = Color(0xFFFFFFFF),
    playScrim = Color(0xFFFF2D55),
    dialogScrim = Color(0xFF000000),
    typeJs = Color(0xFF5856D6),
    typePy = Color(0xFFFF9500),
    glowLabel = Color(0xFFFFFFFF),
    iconPurple = Color(0xFF5856D6),
    iconOrange = Color(0xFFFF9500),
    iconGreen = Color(0xFF34C759),
    iconBlue = Color(0xFF007AFF),
    iconPink = Color(0xFFFF2D55),
    iconViolet = Color(0xFFAF52DE),
    iconBlueAlt = Color(0xFF3478F6)
)

val LocalGlassPalette = staticCompositionLocalOf { LightPalette }

/**
 * 播放器是「视频层」，固定为深色，不随应用亮暗主题变化。
 * 全部播放器内的色值都从这里取。
 */
object PlayerTokens {
    val backdrop = Color(0xFF000000)
    val topScrim = Color(0xB3060A0E)
    val bottomScrim = Color(0xCC05080C)
    val bottomScrimSolid = Color(0xED05080C)
    val onVideo = Color(0xFFFFFFFF)
    val onVideoSecondary = Color(0xCCFFFFFF)
    val onVideoTertiary = Color(0x99FFFFFF)
    val onVideoMuted = Color(0xB3FFFFFF)
    val onVideoSoft = Color(0xAAFFFFFF)
    val onVideoFaint = Color(0x88FFFFFF)
    val controlBg = Color(0x4DFFFFFF)
    val controlBgStrong = Color(0x66FFFFFF)
    val hintBg = Color(0x99000000)
    val seekTrack = Color(0x33FFFFFF)
    val seekThumb = Color(0xFFFFFFFF)
    val accent = Color(0xFFFF2D55)
}
