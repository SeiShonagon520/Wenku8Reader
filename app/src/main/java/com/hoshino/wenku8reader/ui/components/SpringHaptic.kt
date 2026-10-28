package com.hoshino.wenku8reader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext

/**
 * 物理实体按压回弹与微触感效果修饰符。
 * 按下时平滑缩放至 [targetScale]，松手时以阻尼弹簧迅速回弹到 1.0f。
 * 触感震动精确联动应用全局触觉设置 [LocalHapticConfig]，同时支持单击与长按事件。
 */
fun Modifier.bounceClick(
    targetScale: Float = 0.95f,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
): Modifier = composed {
    var isPressed by remember { mutableStateOf(false) }
    val hapticConfig = LocalHapticConfig.current
    val context = LocalContext.current
    val scale by animateFloatAsState(
        targetValue = if (isPressed) targetScale else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "bounceScale",
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(onClick, onLongClick, hapticConfig) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    if (hapticConfig.enabled) {
                        HapticFeedback.vibrate(context, hapticConfig.strength)
                    }
                    tryAwaitRelease()
                    isPressed = false
                },
                onLongPress = {
                    if (hapticConfig.enabled) {
                        HapticFeedback.vibrate(context, (hapticConfig.strength * 1.4f).toInt().coerceAtMost(100))
                    }
                    onLongClick?.invoke()
                },
                onTap = {
                    onClick?.invoke()
                },
            )
        }
}
