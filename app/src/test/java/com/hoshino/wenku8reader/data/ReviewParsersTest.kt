package com.hoshino.wenku8reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewParsersTest {
    @Test
    fun parseBookReviews_extractsTopicsCountsAndPagination() {
        val html = """
            <td><a href="/book/1191.htm">《国王游戏》</a> 书评列表</td>
            <tr><td class="odd"><a href="/modules/article/reviewshow.php?rid=318998">完结了还更新</a></td>
                <td class="even">2/380</td></tr>
            <tr><td class="odd"><a href="/modules/article/reviewshow.php?rid=318960">十年老肉&amp;完结</a></td>
                <td class="even">0/141</td></tr>
            <div class="pagelink"><em id="pagestats">2/7</em></div>
        """.trimIndent()

        val page = ReviewParsers.parseBookReviews(html, 1191, 2)

        assertNotNull(page)
        assertEquals("国王游戏", page?.bookTitle)
        assertEquals(2, page?.page)
        assertEquals(7, page?.totalPages)
        assertEquals(listOf(318998, 318960), page?.reviews?.map { it.id })
        assertEquals("十年老肉&完结", page?.reviews?.get(1)?.title)
        assertEquals(2, page?.reviews?.first()?.replyCount)
        assertEquals(380, page?.reviews?.first()?.viewCount)
    }

    @Test
    fun parseBookReviews_keepsRecognizedEmptyListAsEmpty() {
        val html = """
            <a href="/book/1191.htm">《国王游戏》</a> 书评列表
            <div class="pagelink"><em id="pagestats">1/1</em></div>
        """.trimIndent()

        val page = ReviewParsers.parseBookReviews(html, 1191, 1)

        assertNotNull(page)
        assertEquals(emptyList<BookReview>(), page?.reviews)
        assertEquals(1, page?.totalPages)
    }

    @Test
    fun parseReviewThread_extractsOriginalAndRepliesAsPlainText() {
        val html = """
            <td><a href="/book/1191.htm">《国王游戏》</a> 书评回复</td>
            <table class="grid"><tr><th><strong>主题：为什么还在更新？</strong></th></tr></table>
            <table class="grid" width="100%"><tr>
              <td><strong><a href="/userpage.php?uid=1">reader&amp;one</a></strong></td>
              <td><div style="width:39%;float:right;text-align:right;">2026-09-07 06:31:53 | <a name="yid1309452">1#</a></div>
                <hr /><div style="width:100%;text-align:left;">最近天天更新 &amp; 很惊讶<br />第二行<script>alert('x')</script><b>粗体</b></div></td>
            </tr></table>
            <table class="grid" width="100%"><tr>
              <td><strong><a href="/userpage.php?uid=2">reply-user</a></strong></td>
              <td><div style="width:39%;float:right;text-align:right;">2026-09-08 03:14:10 | <a name="yid1309560">2#</a></div>
                <hr /><div style="width:100%;text-align:left;">笑死了 &amp;#129316;</div></td>
            </tr></table>
            <div class="pagelink"><em id="pagestats">1/3</em></div>
        """.trimIndent()

        val page = ReviewParsers.parseReviewThread(html, 318998, 1)

        assertNotNull(page)
        assertEquals("国王游戏", page?.bookTitle)
        assertEquals("为什么还在更新？", page?.title)
        assertEquals(3, page?.totalPages)
        assertEquals(2, page?.posts?.size)
        assertEquals("reader&one", page?.posts?.first()?.author)
        assertEquals("2026-09-07 06:31:53", page?.posts?.first()?.postedAt)
        assertEquals(1, page?.posts?.first()?.floor)
        assertEquals("最近天天更新 & 很惊讶\n第二行 粗体", page?.posts?.first()?.body)
        assertEquals("笑死了 ${String(Character.toChars(129316))}", page?.posts?.last()?.body)
    }

    @Test
    fun parsersRejectUnrelatedPages() {
        assertNull(ReviewParsers.parseBookReviews("<html>Cloudflare</html>", 1191, 1))
        assertNull(ReviewParsers.parseReviewThread("<html>Cloudflare</html>", 318998, 1))
    }
}
