package com.glassbox.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glassbox.app.data.Episode

/**
 * 剧集格：4 列、每格最小高 38dp（02 §7.2）。
 * 用固定行布局而不是 LazyGrid，方便嵌在可滚动页面里。
 */
@Composable
fun EpisodeGrid(
    episodes: List<Episode>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    columns: Int = 4,
    watchedIndices: Set<Int> = emptySet(),
    softSelected: Boolean = false,
    onSelect: (Int) -> Unit
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        episodes.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { colIndex, ep ->
                    val index = rowIndex * columns + colIndex
                    EpisodeChip(
                        text = ep.name.ifBlank { "${index + 1}" },
                        selected = index == selectedIndex,
                        watched = index in watchedIndices,
                        softSelected = softSelected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(index) }
                    )
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
