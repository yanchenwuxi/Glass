package com.glassbox.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassbox.app.ui.theme.GlassTheme

/**
 * 统一底部抽屉：纯白面板、顶部 24dp 圆角、灰色短拖动条。
 * 对应 02 §7.3「弹层」：白卡从底部升起，不使用玻璃模糊。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = GlassTheme.dimens.sheetMaxHeight,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!visible) return
    val palette = GlassTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = palette.surface,
        contentColor = palette.textPrimary,
        scrimColor = palette.scrim,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .background(palette.track, RoundedCornerShape(2.dp))
                )
            }
        },
        modifier = modifier
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding(),
            content = content
        )
    }
}

/**
 * 原版抽屉（对齐 BieCuiLe 1.1.5「切换资源 / 切换平台」）：
 * 仅上方 24dp 圆角、暖白面板、40×4dp 把手、左对齐 20sp Bold 标题、
 * 右侧浅灰圆形动作钮 + 圆形关闭；列表行为 52dp 平铺行 + 16dp 缩进分隔线。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OriginalSheet(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 560.dp,
    /** true 时只允许拖动顶部把手或点遮罩关闭，内容区上下滑动不会关闭面板。 */
    handleOnlyDismiss: Boolean = false,
    actions: List<OriginalSheetAction> = emptyList(),
    content: @Composable ColumnScope.() -> Unit
) {
    if (!visible) return
    val palette = GlassTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var handleDrag by remember { mutableStateOf(0f) }
    // 内容区的上下滑动只滚列表：把子列表消费后剩下的滚动量也吃掉，面板因此不会被拖动。
    // 关闭只走顶部把手（下面的 pointerInput）或点击遮罩（ModalBottomSheet 内建）。
    val blockSheetDrag = remember(handleOnlyDismiss) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = if (handleOnlyDismiss && available.y != 0f) available else Offset.Zero
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        // 原版实测：抽屉外层为暖白 #F2F3EF，列表区才是纯白内嵌卡。
        containerColor = if (palette.dark) palette.surface else palette.bgTop,
        contentColor = palette.textPrimary,
        scrimColor = palette.scrim,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 2.dp)
                    .then(
                        if (handleOnlyDismiss) Modifier.pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = { handleDrag = 0f },
                                onDragCancel = { handleDrag = 0f }
                            ) { change, dragAmount ->
                                change.consume()
                                handleDrag += dragAmount
                                if (handleDrag > 64.dp.toPx()) {
                                    handleDrag = 0f
                                    onDismiss()
                                }
                            }
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(palette.track, RoundedCornerShape(2.dp))
                )
            }
        },
        modifier = modifier
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding()
                .nestedScroll(blockSheetDrag)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(start = 24.dp, end = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    color = palette.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                actions.forEach { action ->
                    OriginalCircleAction(action.icon, action.label, action.onClick, tint = palette.accent)
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.width(40.dp))
                OriginalCircleAction(
                    Icons.Outlined.Close,
                    "关闭",
                    onDismiss,
                    tint = palette.textSecondary,
                    size = 30.dp
                )
            }
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(palette.surfaceElevated)
            ) {
                Column(Modifier.fillMaxWidth()) { content() }
            }
        }
    }
}

/** 抽屉标题右侧的圆形动作钮（原版实测 34dp 圆 + 17dp 图标，关闭钮 30dp）。 */
@Composable
fun OriginalCircleAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color? = null,
    size: Dp = 34.dp
) {
    val palette = GlassTheme.colors
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(palette.chipBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = tint ?: palette.textPrimary, modifier = Modifier.size(17.dp))
    }
}

/** 原版抽屉列表行：51dp 高（原版实测 139px@2.75）、22dp 圆形 logo、17sp 主文案、11sp 副信息。 */
@Composable
fun OriginalSheetRow(
    title: String,
    subtitle: String,
    logoColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    showDivider: Boolean = true,
    enabled: Boolean = true,
    /** 两列网格里的紧凑形态：隐藏副标题，把宽度让给站点名 */
    compact: Boolean = false
) {
    val palette = GlassTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(if (compact) 44.dp else 51.dp)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = if (compact) 10.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!compact) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (enabled) logoColor else palette.track),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title.take(1),
                        color = palette.onAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = when {
                        selected -> palette.accent
                        !enabled -> palette.textSecondary
                        else -> palette.textPrimary
                    },
                    fontSize = if (compact) 13.sp else 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!compact) {
                    Text(
                        "  ┆  $subtitle",
                        color = palette.textSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (selected) {
                Spacer(Modifier.width(if (compact) 4.dp else 8.dp))
                Text(
                    "当前",
                    color = palette.accent,
                    fontSize = if (compact) 9.sp else 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        if (showDivider) {
            // 原版实测：分隔线左端与主文案对齐（卡片内 50dp），右端留 16dp。
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = if (compact) 10.dp else 50.dp, end = if (compact) 10.dp else 16.dp)
                    .height(1.dp)
                    .background(palette.divider)
            )
        }
    }
}

/** 抽屉标题动作模型。 */
data class OriginalSheetAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit
)

/** 抽屉内的小标题行。 */
@Composable
fun SheetHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val palette = GlassTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = palette.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
        trailing?.invoke()
    }
}
