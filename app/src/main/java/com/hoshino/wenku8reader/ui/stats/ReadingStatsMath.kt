package com.hoshino.wenku8reader.ui.stats

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.ceil

/**
 * 阅读统计里的纯计算：秒→分钟取整、连续阅读天数、日均分钟、时间尺度区间计算。
 *
 * 从 `ReadingStatsViewModel` 提为顶层函数以便单测。这几处此前完全无测试，而它们错了
 * 都不会报错、只会"数字看着不对"或引发除零/NaN：
 * - 向上取整写错 → 每天少算/多算一分钟，热力图与汇总长期偏移；
 * - 连续天数从"最近一天"而不是"今天"起算 → 隔一天没读也显示连续（最常见的那种错）；
 * - 日均按日历天而不是活跃天算 → 用户一停更就被稀释成很小的数；
 * - 尺度区间算法缺陷 → "全部"尺度单日记录导致单点 NaN 闪退，或极端时间戳导致成千上万列 OOM。
 */

/** 秒 → 分钟，向上取整（不足 1 分钟按 1 分钟计；非正数为 0）。 */
internal fun ceilMinutes(seconds: Long): Int =
    if (seconds <= 0) 0 else ceil(seconds / 60.0).toInt()

/**
 * 连续阅读天数：**从今天起**向前逐日回溯，直到某天没有记录为止。
 *
 * @param daysWithData 有阅读记录的日期（通常来自聚合 map 的 key 集合）
 * @param today 今天（测试注入固定日期，避免依赖系统时钟）
 */
internal fun streakDays(daysWithData: Set<Long>, today: LocalDate): Int {
    var streak = 0
    var cursor = today
    while (daysWithData.contains(cursor.toEpochDay())) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return streak
}

/** 日均分钟：按"有记录的天数"平均（而非日历天数），无记录返回 0。 */
internal fun avgDailyMinutes(totalSeconds: Long, activeDays: Int): Int =
    if (activeDays > 0) ceilMinutes(totalSeconds / activeDays) else 0

/**
 * 计算所选时间尺度对应的日期闭区间：[start, end]（end 不超过 today）。
 *
 * - WEEK: 本周一到今天
 * - MONTH: 本月1号到今天
 * - YEAR: 本年1月1号到今天
 * - ALL: 最早记录到今天；保底至少展示 52 周（364天）热力底板，上限回溯 2 年（防脏时间戳 OOM）。
 */
internal fun rangeOfScale(
    scale: ReadingScale,
    minEpochDay: Long?,
    today: LocalDate,
): Pair<LocalDate, LocalDate> = when (scale) {
    ReadingScale.WEEK -> today.with(DayOfWeek.MONDAY) to today
    ReadingScale.MONTH -> today.withDayOfMonth(1) to today
    ReadingScale.YEAR -> today.withDayOfYear(1) to today
    ReadingScale.ALL -> {
        val defaultStart = today.minusDays(364)
        val min = minEpochDay?.let { LocalDate.ofEpochDay(it) }
        val start = if (min != null && min.isBefore(defaultStart)) {
            val maxLimit = today.minusYears(2)
            if (min.isBefore(maxLimit)) maxLimit else min
        } else {
            defaultStart
        }
        val safeStart = if (start.isAfter(today)) defaultStart else start
        safeStart to today
    }
}
