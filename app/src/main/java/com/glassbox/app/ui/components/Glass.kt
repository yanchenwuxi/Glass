package com.glassbox.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import com.glassbox.app.ui.theme.GlassTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * 页面底色。新设计为纯色底（02 §7.3：靠底色差做层次，不堆渐变光晕）。
 * 保留原来的函数签名，避免页面调用点改动。
 */
@Composable
fun AuroraBackground(
    animate: Boolean,
    intensity: Float,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val palette = GlassTheme.colors
    Box(modifier.fillMaxSize().background(palette.bgTop)) {
        Box(Modifier.fillMaxSize(), content = content)
    }
}

/**
 * 卡片容器：纯白（深色 #1C1C1E）实底 + 20dp 圆角，几乎不用阴影。
 * 靠底色差与圆角区分层级，这是原版设计的主要质感来源。
 */
@Composable
fun GlassSurface(
    level: Int,
    modifier: Modifier = Modifier,
    shape: androidx.compose.foundation.shape.RoundedCornerShape = GlassTheme.cardShape,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val palette = GlassTheme.colors
    val fill = when (level) {
        1 -> palette.glassFill1
        2 -> palette.glassFill2
        3 -> palette.glassFill3
        else -> palette.glassFill4
    }
    val shadow = if (level >= 3) GlassTheme.elevation.shadowFor(level) else 0.dp
    Box(
        modifier
            .then(
                if (shadow > 0.dp) Modifier.shadow(
                    elevation = shadow,
                    shape = shape,
                    ambientColor = palette.shadowAmbient,
                    spotColor = palette.shadowSpot
                ) else Modifier
            )
            .clip(shape)
            .background(fill)
            .border(1.dp, palette.glassBorder, shape)
            .padding(contentPadding),
        content = content
    )
}

/**
 * 液态玻璃表面：半透明底色 + 真实背景模糊 + 边缘高光/暗部渐变描边 + 表面斜向高光。
 *
 * 需要真实模糊时把 [hazeState] 传进来，并在滚动内容上挂 `Modifier.hazeSource(hazeState)`；
 * Android 12+ 走 RenderEffect 高斯模糊，低版本自动回退为半透明着色。
 */
@Composable
fun Modifier.liquidGlass(
    shape: Shape = GlassTheme.pillShape,
    hazeState: HazeState? = null,
    blur: Dp = 22.dp,
    tint: Color = GlassTheme.colors.glassTint,
    elevation: Dp = 12.dp,
    borderWidth: Dp = 1.dp
): Modifier {
    val palette = GlassTheme.colors
    // 左上亮、右下暗的渐变描边，模拟玻璃边缘折射。
    val edgeBrush = Brush.linearGradient(
        0f to palette.glassEdgeLight,
        0.35f to palette.glassEdgeLight.copy(alpha = 0.30f),
        0.62f to Color.Transparent,
        1f to palette.glassEdgeDark
    )
    var base = this
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = palette.shadowAmbient,
            spotColor = palette.shadowSpot
        )
        .clip(shape)
    base = if (hazeState != null) {
        base.hazeEffect(hazeState) {
            this.blurRadius = blur
            backgroundColor = tint
            noiseFactor = 0.035f
            fallbackTint = HazeTint(tint)
        }
    } else {
        base.background(tint)
    }
    return base
        .drawBehind {
            drawRect(
                brush = Brush.linearGradient(
                    0f to palette.glassSheen,
                    0.55f to Color.Transparent,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
            )
        }
        .border(BorderStroke(borderWidth, edgeBrush), shape)
}

/**
 * 主按钮：玫红实心全圆。详情页主按钮传 height = 72dp。
 */
@Composable
fun PrimaryCapsule(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp,
    onClick: () -> Unit
) {
    val palette = GlassTheme.colors
    Row(
        modifier
            .heightIn(min = minHeight)
            .clip(GlassTheme.pillShape)
            .background(palette.accent)
            .clickable(enabled = enabled, onClick = onClick)
            .alphaIf(!enabled)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, tint = palette.onAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = palette.onAccent,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

private fun Modifier.alphaIf(on: Boolean): Modifier =
    if (on) this else this.alpha(0.45f)

/**
 * 圆形图标按钮：白底（视频层可传深色底），43dp。
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    level: Int = 1,
    tint: Color? = null,
    bg: Color? = null,
    size: Dp = GlassTheme.dimens.iconButton,
    onClick: () -> Unit
) {
    val palette = GlassTheme.colors
    val fill = bg ?: GlassTheme.fillFor(level)
    Box(
        modifier
            .size(size)
            .clip(GlassTheme.shapes.iconButton(size))
            .background(fill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription,
            tint = tint ?: palette.textPrimary,
            modifier = Modifier.size(size * 0.46f)
        )
    }
}

/**
 * 筛选 chip：高 36dp 全圆；未选白底灰字，选中玫红底白字。
 */
@Composable
fun GlassChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = GlassTheme.colors
    Box(
        modifier
            .heightIn(min = 36.dp)
            .clip(GlassTheme.pillShape)
            .background(if (selected) palette.accent else palette.surface)
            .border(1.dp, if (selected) Color.Transparent else palette.glassBorder, GlassTheme.pillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) palette.onAccent else palette.textSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/**
 * 搜索胶囊：白底 56dp 全圆，左侧蓝色放大镜。
 */
@Composable
fun GlassSearchBar(
    hint: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onValueChange: ((String) -> Unit)? = null,
    value: String = "",
    focusRequester: FocusRequester? = null,
    onSearch: (() -> Unit)? = null
) {
    val palette = GlassTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(GlassTheme.pillShape)
            .background(palette.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(leadingIcon, null, tint = palette.iconBlue, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (onValueChange != null) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = palette.textPrimary, fontSize = 15.sp),
                    cursorBrush = SolidColor(palette.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (focusRequester != null) Modifier.focusRequester(focusRequester)
                            else Modifier
                        ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() })
                )
            }
            if (value.isEmpty()) {
                Text(hint, color = palette.textTertiary, fontSize = 15.sp)
            }
        }
        trailing?.invoke()
    }
}

/**
 * 区块标题：18sp SemiBold + 可选副标题。
 */
@Composable
fun SectionTitle(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    val palette = GlassTheme.colors
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = palette.textPrimary,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    color = palette.textTertiary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        action?.invoke() ?: Icon(
            Icons.Outlined.ChevronRight,
            null,
            tint = palette.textTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 空态三段式：大描边图标 + 粗体一句 + 灰色两行解释（03 §3.3）。
 */
@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null
) {
    val palette = GlassTheme.colors
    Column(
        modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (icon != null) {
            Icon(icon, null, tint = palette.textTertiary, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(22.dp))
        }
        Text(
            title,
            color = palette.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            message,
            color = palette.textSecondary,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center
        )
        if (action != null) {
            Spacer(Modifier.height(22.dp))
            action()
        }
    }
}

@Composable
fun SurfaceDivider(modifier: Modifier = Modifier) {
    val palette = GlassTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.divider)
    )
}

/**
 * 小徽标：浅灰胶囊；accent 时玫红浅底。
 */
@Composable
fun SoftBadge(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    val palette = GlassTheme.colors
    Box(
        modifier
            .clip(GlassTheme.pillShape)
            .background(if (accent) palette.accentSoft else palette.chipBg)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            color = if (accent) palette.accent else palette.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
fun DottedLine(modifier: Modifier = Modifier, color: Color = GlassTheme.colors.divider) {
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
        )
    }
}

fun durationText(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
