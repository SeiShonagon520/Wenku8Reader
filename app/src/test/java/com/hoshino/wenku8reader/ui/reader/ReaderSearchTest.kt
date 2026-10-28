package com.hoshino.wenku8reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSearchTest {

    @Test
    fun testFindSearchMatches_basic() {
        val chapterText = "这是一个阳光明媚的早晨，爱丽丝推开房门，走向了花园。花园里盛开着许多美丽的花朵。"
        val matches = findSearchMatches("101", "第一章", chapterText, "爱丽丝")
        assertEquals(1, matches.size)
        val m = matches[0]
        assertEquals("101", m.cid)
        assertEquals("第一章", m.chapterTitle)
        assertTrue(m.snippet.contains("爱丽丝"))
        assertTrue(m.fraction > 0f && m.fraction < 1f)
    }

    @Test
    fun testFindSearchMatches_caseInsensitive() {
        val chapterText = "The magic sword was ancient. MAGIC flowed through its blade."
        val matches = findSearchMatches("102", "Chapter 1", chapterText, "magic")
        assertEquals(2, matches.size)
        assertTrue(matches[0].matchStart < matches[1].matchStart)
    }

    @Test
    fun testFindSearchMatches_multipleOccurrences() {
        val chapterText = "苹果 橘子 苹果 香蕉 苹果 葡萄"
        val matches = findSearchMatches("103", "水果篇", chapterText, "苹果")
        assertEquals(3, matches.size)
    }

    @Test
    fun testFindSearchMatches_noMatch() {
        val chapterText = "今天天气很好，阳光洒在街道上。"
        val matches = findSearchMatches("104", "天气", chapterText, "暴风雨")
        assertTrue(matches.isEmpty())
    }

    @Test
    fun testFindSearchMatches_fractionCalculation() {
        val chapterText = "0123456789" // length = 10
        val matches = findSearchMatches("105", "数字", chapterText, "5")
        assertEquals(1, matches.size)
        assertEquals(0.5f, matches[0].fraction, 0.001f)
    }
}
