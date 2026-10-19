package com.hoshino.wenku8reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hoshino.wenku8reader.ui.common.CoverImage

/**
 * 具有拟真实体文库本书脊光影与透视投影的 3D 封面组件。
 */
@Composable
fun BookCover3D(
    url: String?,
    width: Dp,
    height: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 6.dp,
    elevation: Dp = 6.dp,
    crossfade: Boolean = true,
) {
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .size(width, height)
            .shadow(elevation, shape = shape, clip = false)
            .clip(shape),
    ) {
        // 底层书籍封面
        CoverImage(
            url = url,
            width = width,
            height = height,
            contentDescription = contentDescription,
            cornerRadius = cornerRadius,
            crossfade = crossfade,
            modifier = Modifier.fillMaxSize(),
        )

        // 拟真书脊立体折痕高光（左边缘 1~3dp）
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(4.dp)
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.35f),
                            Color.White.copy(alpha = 0.12f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // 书本翻页内边缘微阴影（右边缘收束）
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(3.dp)
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.18f),
                        ),
                    ),
                ),
        )

        // 顶层环境光遮罩（极其轻微的全局泛光）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.White.copy(alpha = 0.08f),
                        0.4f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.15f),
                    ),
                ),
        )
    }
}
