package com.hoshino.wenku8reader.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import java.time.DayOfWeek

/**
 * 阅读时长趋势图表（基于现代 Vico 2.0 图表库）。
 *
 * 核心特性：
 * 1. 动态自适应尺度：跟随 [ReadingStatsUiState.scale] 联动切片渲染（周/月/年趋势）；
 * 2. 贝塞尔曲线平滑绘制与弹性缩放；
 * 3. 动态时间轴刻度（周一至周日 / 日期号 / 月份联动）；
 * 4. 极端边界防护：对单点数据（点数 < 2）优雅降级为提示占位，彻底杜绝 Vico 2.0 底层 NaN / 除零导致的闪退。
 */
@Composable
fun ReadingTrendChart(
    ui: ReadingStatsUiState,
    modifier: Modifier = Modifier,
) {
    val days = remember(ui.weeks) {
        ui.weeks.flatten().filterNotNull().sortedBy { it.date }
    }

    if (days.isEmpty()) return

    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(days) {
        if (days.size >= 2) {
            runCatching {
                modelProducer.runTransaction {
                    lineSeries {
                        series(days.indices.toList(), days.map { it.minutes })
                    }
                }
            }
        }
    }

    val bottomAxisFormatter = remember(days, ui.scale) {
        CartesianValueFormatter { _, value, _ ->
            val idx = value.toInt()
            if (idx in days.indices) {
                val d = days[idx].date
                when (ui.scale) {
                    ReadingScale.WEEK -> when (d.dayOfWeek) {
                        DayOfWeek.MONDAY -> "一"
                        DayOfWeek.TUESDAY -> "二"
                        DayOfWeek.WEDNESDAY -> "三"
                        DayOfWeek.THURSDAY -> "四"
                        DayOfWeek.FRIDAY -> "五"
                        DayOfWeek.SATURDAY -> "六"
                        DayOfWeek.SUNDAY -> "日"
                    }
                    ReadingScale.MONTH -> "${d.dayOfMonth}"
                    ReadingScale.YEAR, ReadingScale.ALL -> "${d.monthValue}/${d.dayOfMonth}"
                }
            } else {
                ""
            }
        }
    }

    val startAxisFormatter = remember {
        CartesianValueFormatter { _, value, _ ->
            "${value.toInt()}分"
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ShowChart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.padding(start = 8.dp))
                Text(
                    text = "阅读时长走势",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "单位：分钟",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            if (days.size < 2) {
                // 防护：单点/不足 2 天数据时展示优雅占位，杜绝 Vico 2.0 折线图层除零 (0/0=NaN) 导致的底层绘制崩溃
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ShowChart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "趋势走势积累中（至少记录 2 天生成曲线）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val single = days.firstOrNull()
                        if (single != null && single.minutes > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${single.date.monthValue}月${single.date.dayOfMonth}日 已阅读 ${single.minutes} 分钟",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            } else {
                val chart = rememberCartesianChart(
                    rememberLineCartesianLayer(),
                    startAxis = VerticalAxis.rememberStart(
                        valueFormatter = startAxisFormatter,
                    ),
                    bottomAxis = HorizontalAxis.rememberBottom(
                        valueFormatter = bottomAxisFormatter,
                    ),
                )

                CartesianChartHost(
                    chart = chart,
                    modelProducer = modelProducer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp),
                )
            }
        }
    }
}
