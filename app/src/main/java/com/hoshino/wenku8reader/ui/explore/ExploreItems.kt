package com.hoshino.wenku8reader.ui.explore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoshino.wenku8reader.data.HomeBook
import com.hoshino.wenku8reader.ui.components.BookCover3D
import com.hoshino.wenku8reader.ui.components.bounceClick

/**
 * 探索页可复用的实体封面卡片：
 * - 拟真实体书脊光影与透视投影（BookCover3D）；
 * - 触感微交互（bounceClick 0.96f）；
 * - 细腻字重与居中两行排版。
 */
@Composable
internal fun HomeCoverCard(b: HomeBook, onOpenBook: (Int) -> Unit) {
    Column(
        Modifier
            .width(104.dp)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .bounceClick(targetScale = 0.96f, onClick = { onOpenBook(b.id) }),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BookCover3D(
            url = b.coverUrl,
            width = 100.dp,
            height = 142.dp,
            contentDescription = b.name,
            cornerRadius = 8.dp,
            elevation = 4.dp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            b.name,
            style = MaterialTheme.typography.labelMedium.copy(
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

