package com.hoshino.wenku8reader.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.Coil
import com.hoshino.wenku8reader.R
import com.hoshino.wenku8reader.data.HomeSection
import com.hoshino.wenku8reader.ui.common.coverImageRequest
import com.hoshino.wenku8reader.ui.components.ExpressiveEmptyState
import com.hoshino.wenku8reader.ui.components.ExpressiveLoadingIndicator
import com.hoshino.wenku8reader.ui.components.TonalCard

/**
 * 探索页「推荐」页签正文（从 `ExploreScreen.kt` 拆出，见评估报告 2.5-3）。
 * 含区块列表 + 封面预取逻辑；`ExploreScreen.kt` 只保留页面入口与组合。
 */
@Composable
internal fun HomeBody(
    ui: ExploreUiState,
    onOpenBook: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    val listState = rememberLazyListState()
    // 参考 LightNovelReader：滚动时预取下一个区块的封面，进入视口时图片已就绪，
    // 避免「区块组合 + 图片解码」在同一帧爆发导致的卡顿。
    val context = LocalContext.current
    val density = LocalDensity.current
    val imageLoader = remember { Coil.imageLoader(context) }
    LaunchedEffect(ui.sections) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx ->
                // 首项是「今日书库」标题行（index 0），其后为区块
                val sectionIdx = idx - 1
                if (sectionIdx in 0 until ui.sections.lastIndex) {
                    ui.sections.drop(sectionIdx + 1).take(2).forEach { section ->
                        section.books.forEach { b ->
                            b.coverUrl?.let { url ->
                                runCatching {
                                    imageLoader.enqueue(
                                        // 与 [rememberCoverRequest] 共用同一构建器：
                                        // 防盗链 Referer 只出自 Wenku8Hosts.IMAGE_REFERER 一处
                                        coverImageRequest(
                                            context = context,
                                            url = url,
                                            widthPx = with(density) { 104.dp.roundToPx() },
                                            heightPx = with(density) { 146.dp.roundToPx() },
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }

    when {
        ui.homeLoading && ui.sections.isEmpty() ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ExpressiveLoadingIndicator()
            }

        ui.homeError != null && ui.sections.isEmpty() -> ExpressiveEmptyState(
            title = stringResource(
                R.string.home_error,
                ui.homeError.asString(LocalContext.current),
            ),
            icon = Icons.Filled.Refresh,
            shape = MaterialShapes.Boom,
            error = true,
            actionLabel = stringResource(R.string.action_retry),
            onAction = onRefresh,
            modifier = Modifier.fillMaxSize(),
        )

        ui.sections.isEmpty() -> ExpressiveEmptyState(
            title = stringResource(R.string.home_empty),
            shape = MaterialShapes.Cookie9Sided,
            modifier = Modifier.fillMaxSize(),
        )

        else -> LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.home_subtitle),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                }
            }
            items(ui.sections, key = { it.title }, contentType = { "section" }) { section ->
                HomeSectionBlock(section, onOpenBook)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HomeSectionBlock(section: HomeSection, onOpenBook: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                section.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (section.books.any { it.coverUrl != null }) {
            // 每行 4 本：快速滑动时一次性组合的封面数更少，显著降低组合爆发
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items(section.books.take(4), key = { it.id }) { b ->
                    HomeCoverCard(b, onOpenBook)
                }
            }
        } else {
            // 纯文字榜单：带有精细前三名金属奖章的圆角卡片
            TonalCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    section.books.take(10).forEachIndexed { i, b ->
                        val rankColor = when (i) {
                            0 -> Color(0xFFFFB300) // 金色
                            1 -> Color(0xFF78909C) // 银色
                            2 -> Color(0xFF8D6E63) // 铜色
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val rankBg = when (i) {
                            0 -> Color(0xFFFFB300).copy(alpha = 0.16f)
                            1 -> Color(0xFF78909C).copy(alpha = 0.16f)
                            2 -> Color(0xFF8D6E63).copy(alpha = 0.16f)
                            else -> Color.Transparent
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenBook(b.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(rankBg),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${i + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (i < 3) FontWeight.Bold else FontWeight.Medium,
                                    color = rankColor,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                b.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (i < 3) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

