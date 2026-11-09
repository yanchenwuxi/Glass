package com.glassbox.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassbox.app.ui.theme.GlassTheme
import dev.chrisbanes.haze.HazeState

/**
 * 底部悬浮胶囊导航（02 §7.5）：54dp 高、27dp 圆角、5 项，液态玻璃质感。
 * 选中项为 34dp 玫红圆 + 白图标 + 玫红文字。
 */
@Composable
fun GlassBottomNav(
    currentRoute: String,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    onSelect: (String) -> Unit
) {
    val palette = GlassTheme.colors
    val items = listOf(
        NavItem("home", "媒体", Icons.Outlined.Home),
        NavItem("library", "片库", Icons.Outlined.FavoriteBorder),
        NavItem("sources", "资源", Icons.Outlined.VideoLibrary),
        NavItem("settings", "设置", Icons.Outlined.Settings)
    )
    Row(
        modifier
            .fillMaxWidth()
            .height(GlassTheme.dimens.navHeight)
            .liquidGlass(
                shape = GlassTheme.shapes.pill,
                hazeState = hazeState,
                blur = 24.dp,
                elevation = 14.dp
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            val selected = currentRoute == item.route
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onSelect(item.route) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (selected) palette.accent else androidx.compose.ui.graphics.Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        item.icon,
                        item.label,
                        tint = if (selected) palette.onAccent else palette.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    item.label,
                    color = if (selected) palette.accent else palette.textPrimary,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

private data class NavItem(val route: String, val label: String, val icon: ImageVector)

/**
 * 搜索悬浮按钮：64dp 白色圆（对齐原版右下独立圆形 FAB），与底部导航同行。
 */
@Composable
fun GlassSearchFab(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    onClick: () -> Unit
) {
    GlassCircleFab(
        icon = Icons.Filled.Search,
        label = "搜索",
        modifier = modifier,
        hazeState = hazeState,
        onClick = onClick
    )
}

/**
 * 筛选悬浮按钮：与搜索按钮同款白色圆，位置在搜索按钮正上方；
 * 图标为原版 2×2 四格（四个圆角方块），不是 Material Tune。
 */
@Composable
fun GlassFilterFab(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    onClick: () -> Unit
) {
    val palette = GlassTheme.colors
    Box(
        modifier
            .size(GlassTheme.dimens.fabSize)
            .liquidGlass(
                shape = CircleShape,
                hazeState = hazeState,
                blur = 20.dp,
                elevation = 12.dp
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(26.dp)) {
            val gap = 3.dp.toPx()
            val cell = (size.minDimension - gap) / 2f
            val radius = CornerRadius(2.5.dp.toPx())
            val stroke = 1.5.dp.toPx()
            for (col in 0..1) {
                for (row in 0..1) {
                    drawRoundRect(
                        color = palette.textPrimary,
                        topLeft = Offset(col * (cell + gap), row * (cell + gap)),
                        size = Size(cell, cell),
                        cornerRadius = radius,
                        style = Stroke(width = stroke)
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassCircleFab(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    onClick: () -> Unit
) {
    val palette = GlassTheme.colors
    Box(
        modifier
            .size(GlassTheme.dimens.fabSize)
            .liquidGlass(
                shape = CircleShape,
                hazeState = hazeState,
                blur = 20.dp,
                elevation = 12.dp
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = palette.textPrimary, modifier = Modifier.size(26.dp))
    }
}
