package com.hoshino.wenku8reader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
 * 触感震动精确联动应用全局触觉设置 [LocalHapticConfig]。
 */
fun Modifier.bounceClick(
    targetScale: Float = 0.95f,
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
        .pointerInput(onClick, hapticConfig) {
            awaitPointerEventScope {
                while (true) {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    if (hapticConfig.enabled) {
                        HapticFeedback.vibrate(context, hapticConfig.strength)
                    }
                    val up = waitForUpOrCancellation()
                    isPressed = false
                    if (up != null && onClick != null) {
                        onClick()
                    }
                }
            }
        }
}
