package com.hoshino.wenku8reader.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 全局触觉反馈配置快照。 */
data class HapticConfig(val enabled: Boolean = true, val strength: Int = 50)

/** 供任意非 ripple 交互（如 bounceClick、手势翻页）读取当前触觉设置。 */
val LocalHapticConfig = staticCompositionLocalOf { HapticConfig() }

/**
 * 跨机型自适应触觉震动核心。
 */
object HapticFeedback {

    /**
     * 按用户设置的强度（0-100）触发一次触觉反馈。
     *
     * 针对不同 Android 版本与马达硬件进行全级别自适应：
     * 1. Android 11+ (API 30+)：若硬件支持，使用硬件线性马达专属的 [VibrationEffect.Composition.PRIMITIVE_CLICK]
     *    并以 scale 线性缩放力度，获得最清脆纯净的现代线性马达质感；
     * 2. Android 10+ (API 29+)：若设备不支持振幅控制，按力度级别映射至系统预置反馈
     *    （EFFECT_TICK / EFFECT_CLICK / EFFECT_HEAVY_CLICK）；
     * 3. Android 8+ (API 26+)：若支持振幅调节，映射幅度为 1..255；若不支持振幅，
     *    则通过微调时长（10ms..45ms）补偿强弱反馈差异；
     * 4. 显式附加 [VibrationAttributes.USAGE_TOUCH]，确保在 Android 12/13/14+ 系统中
     *    被正确识别为触摸反馈，不受音频流影响。
     */
    fun vibrate(context: Context, strength: Int) {
        if (strength <= 0) return
        val vib = getVibrator(context) ?: return
        if (!vib.hasVibrator()) return

        val clamped = strength.coerceIn(1, 100)
        runCatching {
            // 1. Android 11+ (API 30+) 线性马达硬件 Primitive 线性缩放
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
            ) {
                val scale = (clamped / 100f).coerceIn(0.1f, 1f)
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                    .compose()
                vibrateWithAttributes(vib, effect)
                return
            }

            // 2. Android 10+ (API 29+) 系统预置反馈效果（针对不支持连续振幅调节的线性马达/转子马达）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !vib.hasAmplitudeControl()) {
                val effectId = when {
                    clamped < 35 -> VibrationEffect.EFFECT_TICK
                    clamped < 75 -> VibrationEffect.EFFECT_CLICK
                    else -> VibrationEffect.EFFECT_HEAVY_CLICK
                }
                val effect = VibrationEffect.createPredefined(effectId)
                vibrateWithAttributes(vib, effect)
                return
            }

            // 3. Android 8+ (API 26+) 单次脉冲（支持振幅控制时调节幅度，不支持时按时长补偿）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val hasAmp = vib.hasAmplitudeControl()
                val amplitude = if (hasAmp) {
                    (clamped * 255 / 100).coerceIn(1, 255)
                } else {
                    VibrationEffect.DEFAULT_AMPLITUDE
                }
                val duration = if (hasAmp) {
                    20L
                } else {
                    (10L + (clamped * 35L / 100L)).coerceIn(10L, 45L)
                }
                val effect = VibrationEffect.createOneShot(duration, amplitude)
                vibrateWithAttributes(vib, effect)
                return
            }

            // 4. API 26 以下传统 API
            @Suppress("DEPRECATION")
            vib.vibrate((10L + (clamped * 35L / 100L)).coerceIn(10L, 45L))
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator ?: context.getSystemService(Vibrator::class.java)
        } else {
            context.getSystemService(Vibrator::class.java)
        }
    }

    private fun vibrateWithAttributes(vib: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH)
                .build()
            vib.vibrate(effect, attrs)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val audioAttrs = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .build()
            vib.vibrate(effect, audioAttrs)
        } else {
            vib.vibrate(effect)
        }
    }
}

/**
 * 全局点击振动 Indication（Compose 1.7 IndicationNodeFactory API）：
 * 把默认 ripple 节点作为 delegate 委托（保留视觉水波纹），
 * 并在按下时直接调用系统 [HapticFeedback] 按强度振动。
 */
class HapticIndication(
    private val context: Context,
    private val enabled: () -> Boolean,
    private val strength: () -> Int,          // 0-100
    private val delegate: Indication,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode {
        val rippleNode = (delegate as? IndicationNodeFactory)?.create(interactionSource) as? Modifier.Node
        return HapticIndicationNode(context, enabled, strength, rippleNode, interactionSource)
    }

    override fun equals(other: Any?): Boolean =
        other is HapticIndication && other.delegate == delegate

    override fun hashCode(): Int = delegate.hashCode()
}

private class HapticIndicationNode(
    private val context: Context,
    private val enabled: () -> Boolean,
    private val strength: () -> Int,
    delegateNode: Modifier.Node?,
    private val interactionSource: InteractionSource,
) : DelegatingNode() {

    init {
        delegateNode?.let { delegate(it) } // 委托绘制（默认 ripple），保留水波纹视觉
    }

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                if (enabled() && interaction is PressInteraction.Press) {
                    HapticFeedback.vibrate(context, strength())
                }
            }
        }
    }
}

/**
 * 在应用根部注入：把 [LocalIndication] 替换为 [HapticIndication]，
 * 并向下分发 [LocalHapticConfig]，供所有交互读取。
 */
@Composable
fun HapticScope(
    enabled: Boolean,
    strength: Int,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val delegate = LocalIndication.current
    val enabledState = rememberUpdatedState(enabled)
    val strengthState = rememberUpdatedState(strength)
    val indication = remember(delegate, context) {
        HapticIndication(
            context = context.applicationContext,
            enabled = { enabledState.value },
            strength = { strengthState.value },
            delegate = delegate,
        )
    }
    val hapticConfig = remember(enabled, strength) {
        HapticConfig(enabled = enabled, strength = strength)
    }
    CompositionLocalProvider(
        LocalIndication provides indication,
        LocalHapticConfig provides hapticConfig,
    ) {
        content()
    }
}
