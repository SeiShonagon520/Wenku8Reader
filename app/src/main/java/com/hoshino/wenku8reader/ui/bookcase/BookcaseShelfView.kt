package com.hoshino.wenku8reader.ui.bookcase

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoshino.wenku8reader.ui.components.BookCover3D
import com.hoshino.wenku8reader.ui.components.bounceClick

/**
 * 书架：实体拟真书架视图（Realistic Wooden / Shelf Ledge View）。
 * 模拟书房木质隔板陈列，书籍立于托架之上，自带环境投影与实体质感。
 */
@Composable
fun BookcaseShelfView(
    entries: List<BookcaseEntry>,
    onOpenBook: (Int) -> Unit,
    isBatchMode: Boolean = false,
    selectedBookIds: Set<Int> = emptySet(),
    onToggleSelect: (Int) -> Unit = {},
    onLongClickBook: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(top = 12.dp, bottom = 24.dp),
) {
    // 按每层 3 本书分组
    val chunked = entries.chunked(3)
    val isDark = isSystemInDarkTheme()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        items(chunked) { rowBooks ->
            ShelfRow(
                books = rowBooks,
                onOpenBook = onOpenBook,
                isBatchMode = isBatchMode,
                selectedBookIds = selectedBookIds,
                onToggleSelect = onToggleSelect,
                onLongClickBook = onLongClickBook,
                isDark = isDark,
            )
        }
    }
}

@Composable
private fun ShelfRow(
    books: List<BookcaseEntry>,
    onOpenBook: (Int) -> Unit,
    isBatchMode: Boolean,
    selectedBookIds: Set<Int>,
    onToggleSelect: (Int) -> Unit,
    onLongClickBook: (Int) -> Unit,
    isDark: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 书籍立放展示区
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            books.forEach { book ->
                ShelfBookItem(
                    entry = book,
                    isBatchMode = isBatchMode,
                    isSelected = book.bookId in selectedBookIds,
                    onClick = {
                        if (isBatchMode) onToggleSelect(book.bookId)
                        else onOpenBook(book.bookId)
                    },
                    onLongClick = { onLongClickBook(book.bookId) },
                )
            }
            // 补齐空白占位，保证前排对齐
            repeat(3 - books.size) {
                Spacer(Modifier.width(96.dp))
            }
        }

        // 实体书架隔板与厚度阴影
        ShelfLedge(isDark = isDark)
    }
}

@Composable
private fun ShelfBookItem(
    entry: BookcaseEntry,
    isBatchMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .bounceClick(targetScale = 0.93f, onLongClick = onLongClick, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            BookCover3D(
                url = entry.coverUrl,
                width = 96.dp,
                height = 138.dp,
                contentDescription = entry.title,
                cornerRadius = 6.dp,
                elevation = 8.dp,
            )

            if (isBatchMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else Color.Black.copy(alpha = 0.5f)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }

            BookcaseSourceBadge(
                entry = entry,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            )

            // 书脊底部接触阴影修饰
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .width(88.dp)
                    .height(6.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                        ),
                    ),
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = entry.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun ShelfLedge(isDark: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 隔板上表面高光边缘 (2dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    if (isDark) Color(0xFF4A453F)
                    else Color(0xFFDCC8B0),
                ),
        )

        // 实体隔板主厚度 (10dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(
                    Brush.verticalGradient(
                        if (isDark) {
                            listOf(Color(0xFF2C2824), Color(0xFF201D1A))
                        } else {
                            listOf(Color(0xFFC7B198), Color(0xFFA89279))
                        },
                    ),
                ),
        )

        // 隔板下沿投影深度 (6dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = if (isDark) 0.45f else 0.22f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}
