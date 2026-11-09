package com.glassbox.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassbox.app.ui.theme.GlassTheme

/** 加载态：18dp 玫红进度圈 + 一行灰字（03 §3.3）。 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    text: String = "正在加载…"
) {
    val palette = GlassTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = palette.accent,
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = palette.textSecondary, fontSize = 13.sp)
    }
}

/** 错误态：白卡 + 图标 + 标题 + 说明 + 「重试」。 */
@Composable
fun ErrorNotice(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "加载失败",
    icon: ImageVector = Icons.Outlined.CloudOff,
    onRetry: (() -> Unit)? = null
) {
    val palette = GlassTheme.colors
    GlassSurface(
        level = 1,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, null, tint = palette.danger, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                color = palette.textSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center
            )
            if (onRetry != null) {
                Spacer(Modifier.height(16.dp))
                PrimaryCapsule(text = "重试", minHeight = 40.dp, onClick = onRetry)
            }
        }
    }
}

/** 小状态胶囊：用于头部提示（如「仅显示可播放线路」）。 */
@Composable
fun StatePill(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    val palette = GlassTheme.colors
    Box(
        modifier
            .clip(GlassTheme.shapes.pill)
            .background(if (accent) palette.accentSoft else palette.chipBg)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text,
            color = if (accent) palette.accent else palette.textSecondary,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

/**
 * 三态容器：内容 / 加载 / 错误（空态由调用方传 empty 时再显示）。
 */
@Composable
fun TriStateView(
    loading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    loadingText: String = "正在加载…",
    emptyTitle: String? = null,
    emptyMessage: String = "",
    emptyIcon: ImageVector? = null,
    onRetry: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Box(modifier.fillMaxWidth()) {
        when {
            loading -> LoadingState(text = loadingText)
            error != null -> ErrorNotice(message = error, onRetry = onRetry)
            emptyTitle != null -> EmptyState(title = emptyTitle, message = emptyMessage, icon = emptyIcon)
            else -> content()
        }
    }
}
