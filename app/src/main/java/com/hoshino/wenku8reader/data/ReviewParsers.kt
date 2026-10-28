package com.hoshino.wenku8reader.data

/** Parsers for the public, read-only book-review pages served by Wenku8. */
object ReviewParsers {
    private val ROW = Regex("<tr\\b[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)
    private val REVIEW_LINK = Regex(
        "<a\\b[^>]*href=[\"'][^\"']*reviewshow\\.php\\?[^\"']*\\brid=(\\d+)[^\"']*[\"'][^>]*>([\\s\\S]*?)</a>",
        RegexOption.IGNORE_CASE,
    )
    private val PAGE_STATS = Regex(
        "<em\\b[^>]*id=[\"']pagestats[\"'][^>]*>\\s*(\\d+)\\s*/\\s*(\\d+)\\s*</em>",
        RegexOption.IGNORE_CASE,
    )
    private val BOOK_LINK = Regex(
        "<a\\b[^>]*href=[\"'][^\"']*book/(\\d+)\\.htm[^\"']*[\"'][^>]*>([\\s\\S]*?)</a>",
        RegexOption.IGNORE_CASE,
    )
    private val TOPIC_TITLE = Regex(
        "<th\\b[^>]*>\\s*<strong>\\s*主题：([\\s\\S]*?)</strong>",
        RegexOption.IGNORE_CASE,
    )
    private val GRID_TABLE = Regex(
        "<table\\b(?=[^>]*class=[\"'][^\"']*\\bgrid\\b[^\"']*[\"'])[^>]*>([\\s\\S]*?)</table>",
        RegexOption.IGNORE_CASE,
    )
    private val USER_LINK = Regex(
        "<a\\b[^>]*href=[\"'][^\"']*userpage\\.php\\?uid=\\d+[^\"']*[\"'][^>]*>([\\s\\S]*?)</a>",
        RegexOption.IGNORE_CASE,
    )
    private val PAGE_DIV = Regex(
        "<div\\b[^>]*style=[\"'][^\"']*width\\s*:\\s*39%[^\"']*[\"'][^>]*>([\\s\\S]*?)</div>",
        RegexOption.IGNORE_CASE,
    )
    private val UNSAFE_BLOCK = Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
    private val HTML_COMMENT = Regex("<!--[\\s\\S]*?-->")
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&#(x[0-9a-f]+|\\d+);", RegexOption.IGNORE_CASE)
    private val MULTI_NEWLINE = Regex("\\n{3,}")

    /** Returns null when the response is not a recognizable review-list page. */
    fun parseBookReviews(html: String, bookId: Int, requestedPage: Int): BookReviewPage? {
        val marker = html.contains("书评列表") || html.contains("全部书评") ||
            html.contains("reviews.php?aid=$bookId", ignoreCase = true)
        if (!marker) return null

        val bookTitle = BOOK_LINK.find(html)?.groupValues?.get(2)?.let(::plainText).orEmpty()
            .removePrefix("《")
            .removeSuffix("》")
        val (page, totalPages) = parsePageStats(html, requestedPage)
        val reviews = ROW.findAll(html).mapNotNull { row ->
            val link = REVIEW_LINK.find(row.groupValues[1]) ?: return@mapNotNull null
            val id = link.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val title = plainText(link.groupValues[2])
            if (title.isBlank()) return@mapNotNull null
            val counts = Regex("(\\d+)\\s*/\\s*(\\d+)").find(row.groupValues[1])
            BookReview(
                id = id,
                title = title,
                replyCount = counts?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                viewCount = counts?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            )
        }.distinctBy { it.id }.toList()

        return BookReviewPage(bookId, bookTitle, reviews, page, totalPages)
    }

    /** Returns null when the response is not a recognizable review-thread page. */
    fun parseReviewThread(html: String, reviewId: Int, requestedPage: Int): ReviewThreadPage? {
        val title = TOPIC_TITLE.find(html)?.groupValues?.get(1)?.let(::plainText)?.takeIf { it.isNotBlank() }
            ?: return null
        val bookTitle = BOOK_LINK.find(html)?.groupValues?.get(2)?.let(::plainText).orEmpty()
            .removePrefix("《")
            .removeSuffix("》")
        val (page, totalPages) = parsePageStats(html, requestedPage)
        val posts = GRID_TABLE.findAll(html).mapNotNull { table ->
            val body = table.groupValues[1]
            val user = USER_LINK.find(body)?.groupValues?.get(1)?.let(::plainText).orEmpty()
            if (user.isBlank()) return@mapNotNull null

            val pageMeta = PAGE_DIV.find(body)?.groupValues?.get(1).orEmpty()
            val anchor = Regex("name=[\"']yid(\\d+)[\"']", RegexOption.IGNORE_CASE)
                .find(pageMeta)?.groupValues?.get(1)
            val floor = Regex("(\\d+)\\s*#").find(pageMeta)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val postedAt = Regex("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}")
                .find(plainText(pageMeta))?.value.orEmpty()
            val rawPost = body.substringAfter("<hr", "")
                .substringAfter('>')
                .substringBefore("</td>")
            val content = plainText(rawPost)
            if (content.isBlank()) return@mapNotNull null
            ReviewPost(
                id = anchor ?: "${reviewId}_${page}_$floor",
                floor = floor,
                author = user,
                postedAt = postedAt,
                body = content,
            )
        }.distinctBy { it.id }.toList()

        return ReviewThreadPage(reviewId, bookTitle, title, posts, page, totalPages)
    }

    private fun parsePageStats(html: String, requestedPage: Int): Pair<Int, Int> {
        val values = PAGE_STATS.find(html)?.groupValues ?: return requestedPage.coerceAtLeast(1) to 1
        val current = values[1].toIntOrNull()?.coerceAtLeast(1) ?: requestedPage.coerceAtLeast(1)
        val total = values[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
        return current to total
    }

    /** Strip all markup before rendering user-authored posts as inert plain text. */
    private fun plainText(value: String): String {
        val text = value
            .replace(UNSAFE_BLOCK, " ")
            .replace(HTML_COMMENT, " ")
            .replace(Regex("<br\\s*/?>|</p\\s*>|</div\\s*>|</li\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</td\\s*>|</tr\\s*>", RegexOption.IGNORE_CASE), " ")
            .replace(TAG, " ")
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
        return MULTI_NEWLINE.replace(
            ENTITY.replace(text) { match ->
                val token = match.groupValues[1]
                val codePoint = if (token.startsWith("x", true)) {
                    token.drop(1).toIntOrNull(16)
                } else {
                    token.toIntOrNull()
                }
                codePoint?.takeIf(Character::isValidCodePoint)?.let(Character::toChars)?.concatToString()
                    ?: " "
            }.replace(Regex("[\\t\\x0B\\x0C\\r ]+"), " ")
                .replace(Regex(" *\\n *"), "\n")
                .trim(),
            "\n\n",
        )
    }
}
