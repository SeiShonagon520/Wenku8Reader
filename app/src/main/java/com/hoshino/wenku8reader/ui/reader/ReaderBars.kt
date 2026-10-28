package com.hoshino.wenku8reader.ui.reader

import android.content.Context
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoshino.wenku8reader.R
import java.util.Date
import kotlinx.coroutines.delay

import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/** 状态指示器（电量/时钟）刷新间隔：30 秒足够，且避免过频唤醒。 */
private const val BATTERY_CLOCK_REFRESH_MS = 30_000L

/** 电量读取失败时的兜底百分比。 */
private const val BATTERY_FALLBACK_PERCENT = 100

/**
 * 顶部悬浮药丸胶囊栏（Floating Top Capsule）。
 * 悬浮于状态栏下方，支持 Haze 实时流体毛玻璃透光。
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
    onSearch: () -> Unit = {},
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val shape = CircleShape
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                alpha = if (hazeState != null) 0.68f else 0.94f,
            ),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (hazeState != null) {
                        Modifier.hazeChild(state = hazeState, style = HazeMaterials.thin())
                    } else Modifier
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                IconButton(onClick = onSearch) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = stringResource(R.string.search),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * 底部悬浮控制灵动岛（Floating Bottom Island）。
 * 漂浮于系统手势栏之上，支持 Haze 实时流体毛玻璃磨砂透光。
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
internal fun ReaderBottomBar(
    prevEnabled: Boolean,
    nextEnabled: Boolean,
    autoTurn: Boolean,
    onToggleAutoTurn: () -> Unit,
    onOpenToc: () -> Unit,
    onSettings: () -> Unit,
    onQuickSettings: () -> Unit = {},
    onPrev: () -> Unit,
    onNext: () -> Unit,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val shape = RoundedCornerShape(26.dp)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (hazeState != null) {
                        Modifier.hazeChild(state = hazeState, style = HazeMaterials.thin())
                    } else Modifier
                ),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                alpha = if (hazeState != null) 0.68f else 0.95f,
            ),
            tonalElevation = 8.dp,
            shadowElevation = 10.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 上一章快捷键
                FilledTonalIconButton(
                    onClick = onPrev,
                    enabled = prevEnabled,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.reader_prev_chapter),
                        modifier = Modifier.size(18.dp),
                    )
                }

                // 快速字号与底纸调控面板
                IconButton(onClick = onQuickSettings) {
                    Icon(
                        Icons.Filled.FormatSize,
                        contentDescription = "快速排版",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // 自动翻页
                IconButton(onClick = onToggleAutoTurn) {
                    Icon(
                        if (autoTurn) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.reader_auto_turn),
                        tint = if (autoTurn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }

                // 目录
                IconButton(onClick = onOpenToc) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = stringResource(R.string.reader_toc),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // 更多偏好设置
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.reader_settings),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // 下一章快捷键
                FilledTonalIconButton(
                    onClick = onNext,
                    enabled = nextEnabled,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.reader_next_chapter),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * 沉浸状态微胶囊指示器（屏幕底部的电量/时间/章节名/进度）。
 */
@Composable
internal fun IndicatorBar(
    title: String,
    progressPercent: Int,
    color: Color,
) {
    val context = LocalContext.current
    var batteryPercent by remember { mutableIntStateOf(readBattery(context)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            batteryPercent = readBattery(context)
            now = System.currentTimeMillis()
            delay(BATTERY_CLOCK_REFRESH_MS)
        }
    }
    val timeText = remember(now) { DateFormat.getTimeFormat(context).format(Date(now)) }

    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val batteryIcon = when {
            batteryPercent >= 80 -> Icons.Filled.BatteryFull
            batteryPercent >= 50 -> Icons.Filled.BatteryStd
            batteryPercent >= 20 -> Icons.Filled.Battery4Bar
            else -> Icons.Filled.BatteryAlert
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.06f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                stringResource(R.string.reader_battery, batteryPercent),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontSize = 11.sp,
            )
            Spacer(Modifier.width(2.dp))
            Icon(batteryIcon, contentDescription = null, modifier = Modifier.size(12.dp), tint = color)
            Spacer(Modifier.width(6.dp))
            Text(timeText, style = MaterialTheme.typography.labelSmall, color = color, fontSize = 11.sp)
        }

        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.06f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                stringResource(R.string.reader_progress_percent, progressPercent),
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
            )
        }
    }
}

private fun readBattery(context: Context): Int {
    val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    val capacity = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    return if (capacity in 0..100) capacity else BATTERY_FALLBACK_PERCENT
}
