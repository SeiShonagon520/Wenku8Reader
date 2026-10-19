package com.hoshino.wenku8reader.ui.bookcase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoshino.wenku8reader.ui.components.BookCover3D
import com.hoshino.wenku8reader.ui.components.bounceClick

/**
 * 书架：紧凑小画廊网格视图（Compact Grid）。
 * 专为收藏 100+ 本书的大容量书架打造，自适应 4 列高密度紧凑排版，
 * 极致保留封面图片美术视觉与加载体验的同时，一屏展示更多书籍，找书极速便捷。
 */
@Composable
fun BookcaseCompactGridView(
    entries: List<BookcaseEntry>,
    onOpenBook: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 76.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(entries, key = { it.bookId }) { entry ->
            BookcaseCompactCard(
                entry = entry,
                onClick = { onOpenBook(entry.bookId) },
            )
        }
    }
}

@Composable
private fun BookcaseCompactCard(
    entry: BookcaseEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .bounceClick(targetScale = 0.95f, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.71f),
        ) {
            BookCover3D(
                url = entry.coverUrl,
                width = 80.dp,
                height = 113.dp,
                contentDescription = entry.title,
                cornerRadius = 6.dp,
                elevation = 4.dp,
                modifier = Modifier.fillMaxSize(),
            )

            // 阅读进度角标
            if (entry.progressTotal > 0) {
                val percent = (entry.progress * 100).toInt()
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = if (percent >= 100) "完" else "$percent%",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = entry.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 15.sp,
        )
    }
}

/**
 * 书架：海报画廊网格视图（Poster Grid）。
 * 突出动漫轻小说封面美术风格，自适应 3 列排版。
 */
@Composable
fun BookcaseGridView(
    entries: List<BookcaseEntry>,
    onOpenBook: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 105.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(entries, key = { it.bookId }) { entry ->
            BookcaseGridCard(
                entry = entry,
                onClick = { onOpenBook(entry.bookId) },
            )
        }
    }
}

@Composable
private fun BookcaseGridCard(
    entry: BookcaseEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .bounceClick(targetScale = 0.94f, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.71f), // 约 1:1.41 实体文库本黄金比例
        ) {
            BookCover3D(
                url = entry.coverUrl,
                width = 130.dp,
                height = 184.dp,
                contentDescription = entry.title,
                cornerRadius = 8.dp,
                elevation = 6.dp,
                modifier = Modifier.fillMaxSize(),
            )

            // 阅读进度胶囊标签
            if (entry.progressTotal > 0) {
                val percent = (entry.progress * 100).toInt()
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.68f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (percent >= 100) "已读完" else "$percent%",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp,
        )

        if (entry.author.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
