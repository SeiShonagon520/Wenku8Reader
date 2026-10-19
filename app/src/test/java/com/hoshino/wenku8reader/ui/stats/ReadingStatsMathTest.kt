package com.hoshino.wenku8reader.ui.stats

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 阅读统计的纯计算：秒→分钟取整、连续天数、日均分钟。
 * 这些数字错了不会报错、只会"看着不对"，所以用固定日期注入把它们锁住。
 */
class ReadingStatsMathTest {

    private val today = LocalDate.of(2026, 9, 25)

    @Test
    fun `秒转分钟向上取整_不足一分钟按一分钟`() {
        assertEquals(0, ceilMinutes(0))
        assertEquals(0, ceilMinutes(-5))
        assertEquals(1, ceilMinutes(1))
        assertEquals(1, ceilMinutes(59))
        assertEquals(1, ceilMinutes(60))
        assertEquals(2, ceilMinutes(61))
        assertEquals(10, ceilMinutes(600))
    }

    @Test
    fun `连续天数从今天起算`() {
        val days = setOf(
            today.toEpochDay(),
            today.minusDays(1).toEpochDay(),
            today.minusDays(2).toEpochDay(),
        )
        assertEquals(3, streakDays(days, today))
    }

    @Test
    fun `今天没读则连续天数为 0_即使昨天前天都读了`() {
        val days = setOf(
            today.minusDays(1).toEpochDay(),
            today.minusDays(2).toEpochDay(),
        )
        // 若从"最近有记录的一天"起算，这里会错报成 2 天连续
        assertEquals(0, streakDays(days, today))
    }

    @Test
    fun `中间断档只统计到断档前一天`() {
        val days = setOf(
            today.toEpochDay(),
            today.minusDays(1).toEpochDay(),
            // 缺 today-2
            today.minusDays(3).toEpochDay(),
            today.minusDays(4).toEpochDay(),
        )
        assertEquals(2, streakDays(days, today))
    }

    @Test
    fun `没有记录时连续天数为 0`() {
        assertEquals(0, streakDays(emptySet(), today))
    }

    @Test
    fun `日均按活跃天数平均并向上取整`() {
        // 3 天共 600 秒 = 200 秒/天 → 不足 4 分钟按 4 分钟
        assertEquals(4, avgDailyMinutes(totalSeconds = 600, activeDays = 3))
        // 恰好整分
        assertEquals(2, avgDailyMinutes(totalSeconds = 240, activeDays = 2))
        // 无活跃天：避免除零，返回 0
        assertEquals(0, avgDailyMinutes(totalSeconds = 600, activeDays = 0))
    }

    @Test
    fun `全部尺度_仅有今天记录时保底展示最近364天以避免单日单点NaN崩溃`() {
        val (start, end) = rangeOfScale(ReadingScale.ALL, minEpochDay = today.toEpochDay(), today = today)
        assertEquals(today.minusDays(364), start)
        assertEquals(today, end)
    }

    @Test
    fun `全部尺度_空记录时保底展示最近364天`() {
        val (start, end) = rangeOfScale(ReadingScale.ALL, minEpochDay = null, today = today)
        assertEquals(today.minusDays(364), start)
        assertEquals(today, end)
    }

    @Test
    fun `全部尺度_历史记录早于1年时完整展示至历史起点`() {
        val earlyDate = today.minusDays(500)
        val (start, end) = rangeOfScale(ReadingScale.ALL, minEpochDay = earlyDate.toEpochDay(), today = today)
        assertEquals(earlyDate, start)
        assertEquals(today, end)
    }

    @Test
    fun `全部尺度_极端时间戳时上限截断至2年_防止几千列OOM`() {
        val ancientDate = LocalDate.of(1970, 1, 1)
        val (start, end) = rangeOfScale(ReadingScale.ALL, minEpochDay = ancientDate.toEpochDay(), today = today)
        assertEquals(today.minusYears(2), start)
        assertEquals(today, end)
    }

    @Test
    fun `全部尺度_未来异常时间戳时安全回退`() {
        val futureDate = today.plusDays(100)
        val (start, end) = rangeOfScale(ReadingScale.ALL, minEpochDay = futureDate.toEpochDay(), today = today)
        assertEquals(today.minusDays(364), start)
        assertEquals(today, end)
    }

    @Test
    fun `周月年尺度正常取其对应周期起点`() {
        val (wStart, wEnd) = rangeOfScale(ReadingScale.WEEK, null, today)
        assertEquals(today.with(java.time.DayOfWeek.MONDAY), wStart)
        assertEquals(today, wEnd)

        val (mStart, mEnd) = rangeOfScale(ReadingScale.MONTH, null, today)
        assertEquals(today.withDayOfMonth(1), mStart)
        assertEquals(today, mEnd)

        val (yStart, yEnd) = rangeOfScale(ReadingScale.YEAR, null, today)
        assertEquals(today.withDayOfYear(1), yStart)
        assertEquals(today, yEnd)
    }
}
