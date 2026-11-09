package com.glassbox.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glassbox.app.data.MediaItem
import com.glassbox.app.data.SourceType
import com.glassbox.app.data.WatchRecord
import com.glassbox.app.ui.theme.GlassTheme
import androidx.compose.ui.platform.LocalContext

@Composable
fun NetworkPoster(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val palette = GlassTheme.colors
    Box(modifier.background(palette.posterPlaceholder)) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 2:3 封面卡（02 §7.2：横向列表卡 118dp 宽、圆角 12、间距 16）。
 */
@Composable
fun PosterCard(
    item: MediaItem,
    modifier: Modifier = Modifier,
    width: Dp = GlassTheme.dimens.posterWidth,
    onClick: () -> Unit
) {
    val palette = GlassTheme.colors
    Column(
        modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(GlassTheme.dimens.posterAspect)
                .clip(RoundedCornerShape(12.dp))
        ) {
            NetworkPoster(item.poster, Modifier.fillMaxSize())
            if (item.remarks.isNotBlank()) {
                Text(
                    item.remarks,
                    color = palette.imageChipText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(GlassTheme.pillShape)
                        .background(palette.imageChipBg)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.title,
            color = palette.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            listOf(item.year, item.typeName).filter { it.isNotBlank() }.joinToString(" · "),
            color = palette.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 首页三列网格卡：2:3 封面 + 海报下方标题（最多三行）。
 */
@Composable
fun GridPosterCard(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = GlassTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
        ) {
            NetworkPoster(item.poster, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            color = palette.textPrimary,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 首页顶部大图：全宽出血、4:5.2，底部渐变接底色，标题压在图上（02 §7.1）。
 */
@Composable
fun HeroCard(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = GlassTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(GlassTheme.dimens.heroAspect)
            .clickable(onClick = onClick)
    ) {
        NetworkPoster(item.backdrop.ifBlank { item.poster }, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.35f to Color.Transparent,
                        0.66f to palette.bgTop.copy(alpha = 0.55f),
                        0.86f to palette.bgTop.copy(alpha = 0.92f),
                        1f to palette.bgTop
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SoftBadge(item.typeName.ifBlank { "影视" })
                if (item.score.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.Star, null, tint = palette.star, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(item.score, color = palette.star, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                item.title,
                color = palette.textPrimary,
                fontSize = 26.sp,
                lineHeight = 33.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                listOf(item.year, item.area, item.remarks).filter { it.isNotBlank() }.joinToString(" · "),
                color = palette.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 继续观看卡：16:9 缩略图 + 底部进度，播放按钮玫红圆。
 */
@Composable
fun ContinueCard(
    record: WatchRecord,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = GlassTheme.colors
    val progress = if (record.durationMs > 0) {
        (record.positionMs.toFloat() / record.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f
    Column(
        modifier
            .width(GlassTheme.dimens.continueWidth)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
        ) {
            NetworkPoster(record.poster, Modifier.fillMaxSize())
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(42.dp)
                    .clip(GlassTheme.shapes.pill)
                    .background(palette.playScrim),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = palette.playGlyph, modifier = Modifier.size(22.dp))
            }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(palette.imageChipBgSoft)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .background(palette.accent)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            record.title,
            color = palette.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            record.episodeName.ifBlank { "上次观看" },
            color = palette.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 剧集格：最小 79×38、圆角 12；选中玫红底白字（02 §7.2）。
 */
@Composable
fun EpisodeChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    watched: Boolean = false,
    softSelected: Boolean = false,
    onClick: () -> Unit
) {
    val palette = GlassTheme.colors
    Box(
        modifier
            .heightIn(min = GlassTheme.dimens.episodeChipHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    selected && softSelected -> palette.accentSoft
                    selected -> palette.accent
                    else -> palette.chipBg
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = when {
                selected && softSelected -> palette.accent
                selected -> palette.onAccent
                watched -> palette.accent
                else -> palette.textSecondary
            },
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 源类型文字徽标：JS/PY/内置按语义色。
 */
@Composable
fun TypeBadge(type: SourceType, modifier: Modifier = Modifier) {
    val palette = GlassTheme.colors
    val color = when (type) {
        SourceType.JS -> palette.typeJs
        SourceType.PY -> palette.typePy
        SourceType.JSON -> palette.iconBlue
    }
    Text(
        type.label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
    )
}

@Composable
fun MetaLine(vararg parts: String, modifier: Modifier = Modifier) {
    Text(
        parts.filter { it.isNotBlank() }.joinToString(" · "),
        color = GlassTheme.colors.textSecondary,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}
