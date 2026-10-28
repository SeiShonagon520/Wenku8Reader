package com.hoshino.wenku8reader.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class LocalBookImporterTest {

    @Test
    fun testIsLocalBook() {
        assertTrue(LocalBookImporter.isLocalBook(-1))
        assertTrue(LocalBookImporter.isLocalBook(-100))
        assertFalse(LocalBookImporter.isLocalBook(1))
        assertFalse(LocalBookImporter.isLocalBook(0))
        assertFalse(LocalBookImporter.isLocalBook(3456))
    }

    @Test
    fun testNormalizeZipPath() {
        assertEquals("OEBPS/text/chap1.xhtml", LocalBookImporter.normalizeZipPath("OEBPS/text/chap1.xhtml"))
        assertEquals("OEBPS/chap1.xhtml", LocalBookImporter.normalizeZipPath("OEBPS/text/../chap1.xhtml"))
        assertEquals("chap1.xhtml", LocalBookImporter.normalizeZipPath("OEBPS/../chap1.xhtml"))
        assertEquals("a/b/c", LocalBookImporter.normalizeZipPath("./a/./b/c"))
    }

    @Test
    fun testExtractAttr() {
        val tag = """<item id="item1" href="chapter1.html" media-type="application/xhtml+xml"/>"""
        assertEquals("item1", LocalBookImporter.extractAttr(tag, "id"))
        assertEquals("chapter1.html", LocalBookImporter.extractAttr(tag, "href"))
        assertEquals("application/xhtml+xml", LocalBookImporter.extractAttr(tag, "media-type"))
        assertEquals(null, LocalBookImporter.extractAttr(tag, "fallback"))
    }

    @Test
    fun testDecodeTextUtf8Bom() {
        val original = "轻小说测试文本 UTF-8"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + original.toByteArray(Charsets.UTF_8)
        val decoded = LocalBookImporter.decodeText(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun testDecodeTextUtf8Plain() {
        val original = "普通的纯UTF-8中文内容，没有BOM"
        val bytes = original.toByteArray(Charsets.UTF_8)
        val decoded = LocalBookImporter.decodeText(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun testDecodeTextGbk() {
        val original = "GBK编码的经典小说正文内容"
        val gbkCharset = Charset.forName("GBK")
        val bytes = original.toByteArray(gbkCharset)
        val decoded = LocalBookImporter.decodeText(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun testSplitTxtChapters_withHeaders() {
        val raw = """
            序章 命运的邂逅
            在平静的城镇上空，忽然出现了一道光芒。
            这是命运转折的时刻。

            第一章 异世界的召唤
            睁开双眼，四周是完全陌生的石壁。
            身旁躺着一把闪耀的铁剑。

            第二章 迷宫探索
            少年拿起铁剑，迈步走向未知的深处。
            
            终章 回归
            终于成功拯救了世界。
        """.trimIndent()

        val chapters = LocalBookImporter.splitTxtChapters(raw, "测试小说")
        assertEquals(4, chapters.size)
        assertEquals("序章 命运的邂逅", chapters[0].first)
        assertEquals("第一章 异世界的召唤", chapters[1].first)
        assertEquals("第二章 迷宫探索", chapters[2].first)
        assertEquals("终章 回归", chapters[3].first)

        assertTrue(chapters[1].second.contains("睁开双眼"))
        assertTrue(chapters[2].second.contains("少年拿起铁剑"))
    }

    @Test
    fun testSplitTxtChapters_fallbackChunking() {
        // 无章节标题的长文本
        val sb = StringBuilder()
        repeat(1500) {
            sb.append("这是一段普通的正文描述文本。")
        }
        val raw = sb.toString()
        val chapters = LocalBookImporter.splitTxtChapters(raw, "短篇小说")
        assertTrue(chapters.size > 1)
        assertTrue(chapters[0].first.startsWith("第 1 部分"))
        assertTrue(chapters[1].first.startsWith("第 2 部分"))
    }
}
