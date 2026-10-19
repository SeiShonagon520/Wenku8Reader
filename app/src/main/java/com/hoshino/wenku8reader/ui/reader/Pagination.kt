package com.hoshino.wenku8reader.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.hoshino.wenku8reader.data.ChapterContent

/** A single page in side-swipe (page-turn) mode. */
sealed interface ReaderPage {
    data class Text(val text: String) : ReaderPage
    data class Image(val url: String) : ReaderPage
}

/**
 * 使用 Compose 官方 TextMeasurer 精确测量文字折行与实际像素高度进行分页。
 *
 * 核心优势：
 * 1. 彻底淘汰基于“字符数 * 0.95”的粗暴猜测，100% 遵循中日文排版规范（避头尾法则、标点禁止换行等）；
 * 2. 测量结果与 Compose Text 实际渲染的断行和高度完全一致，彻底杜绝底部文字溢出裁切或整行被挤飞；
 * 3. 遇到内嵌插图标记 `[[IMG:url]]` 时立即分页并插入单页插图，实现图文原位精准穿插。
 */
fun paginateChapter(
    textMeasurer: TextMeasurer,
    chapter: ChapterContent,
    maxWidthPx: Int,
    maxHeightPx: Int,
    fontSizeSp: Int,
    lineSpacing: Float,
    fontFamily: FontFamily = FontFamily.Default,
    fontWeight: FontWeight = FontWeight.Normal,
): List<ReaderPage> {
    if (maxWidthPx <= 0 || maxHeightPx <= 0) return emptyList()

    val textStyle = TextStyle(
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * lineSpacing).sp,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
    )
    val titleStyle = TextStyle(
        fontSize = (fontSizeSp + 4).sp,
        lineHeight = ((fontSizeSp + 4) * lineSpacing).sp,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
    )

    val pages = mutableListOf<ReaderPage>()
    val currentPageLines = mutableListOf<String>()
    var currentHeightPx = 0

    fun flushCurrentPage() {
        val content = currentPageLines.joinToString("\n").trim('\n')
        if (content.isNotEmpty()) {
            pages.add(ReaderPage.Text(content))
        }
        currentPageLines.clear()
        currentHeightPx = 0
    }

    // 1. 若有章节标题，首先测量并排入首页
    if (chapter.title.isNotBlank()) {
        val titleResult = textMeasurer.measure(
            text = AnnotatedString(chapter.title),
            style = titleStyle,
            constraints = Constraints(maxWidth = maxWidthPx),
        )
        for (i in 0 until titleResult.lineCount) {
            val start = titleResult.getLineStart(i)
            val end = titleResult.getLineEnd(i)
            val lineStr = chapter.title.substring(start, end).trimEnd('\n')
            currentPageLines.add(lineStr)
        }
        currentPageLines.add("")
        val titleBottom = titleResult.getLineBottom(titleResult.lineCount - 1).toInt()
        val extraSpacing = (fontSizeSp * lineSpacing).toInt()
        currentHeightPx += titleBottom + extraSpacing
    }

    // 2. 逐段测量正文
    val paragraphs = splitReaderParagraphs(chapter.text)
    val placedImages = mutableSetOf<String>()

    for (paragraph in paragraphs) {
        if (paragraph.startsWith("[[IMG:") && paragraph.endsWith("]]")) {
            // 内嵌插图标记：先提交前面已有的文字，再生成独立单页插图
            flushCurrentPage()
            val url = paragraph.removePrefix("[[IMG:").removeSuffix("]]")
            placedImages.add(url)
            pages.add(ReaderPage.Image(url))
            continue
        }

        // 普通正文段落：通过 TextMeasurer 计算真实行折叠
        val result = textMeasurer.measure(
            text = AnnotatedString(paragraph),
            style = textStyle,
            constraints = Constraints(maxWidth = maxWidthPx),
        )
        val lineCount = result.lineCount
        for (i in 0 until lineCount) {
            val lineStart = result.getLineStart(i)
            val lineEnd = result.getLineEnd(i)
            val lineStr = paragraph.substring(lineStart, lineEnd).trimEnd('\n')
            val lineTop = result.getLineTop(i).toInt()
            val lineBottom = result.getLineBottom(i).toInt()
            val lineH = (lineBottom - lineTop).coerceAtLeast(1)

            // 若本行加入后超出页面最大可用高度，则另起新页
            if (currentHeightPx + lineH > maxHeightPx && currentPageLines.isNotEmpty()) {
                flushCurrentPage()
            }

            currentPageLines.add(lineStr)
            currentHeightPx += lineH
        }

        val paragraphGap = (fontSizeSp * 0.3f).toInt()
        if (currentHeightPx + paragraphGap <= maxHeightPx) {
            currentHeightPx += paragraphGap
        }
    }

    flushCurrentPage()

    // 3. 兜底：处理未内嵌在正文中的独立插图（如纯插图卷首彩页）
    for (url in chapter.images) {
        if (url !in placedImages) {
            pages.add(ReaderPage.Image(url))
        }
    }

    if (pages.isEmpty()) {
        pages.add(ReaderPage.Text(""))
    }

    return pages
}

/** 兼容旧调用的无 TextMeasurer 重载（保留原始估算兜底，供无 UI 环境的纯 JVM 单测使用） */
fun paginateChapter(
    density: Density,
    chapter: ChapterContent,
    maxWidthPx: Int,
    maxHeightPx: Int,
    fontSizeSp: Int,
    lineSpacing: Float,
): List<ReaderPage> {
    if (maxWidthPx <= 0 || maxHeightPx <= 0) return emptyList()

    val fontSizePx = with(density) { fontSizeSp.sp.toPx() }
    val lineHeightPx = with(density) { (fontSizeSp * lineSpacing).sp.toPx() }

    val charsPerLine = (maxWidthPx.toFloat() / fontSizePx * 0.95f).toInt().coerceAtLeast(1)
    val linesPerPage = (maxHeightPx.toFloat() / lineHeightPx).toInt().coerceAtLeast(1)

    val text = buildString {
        if (chapter.title.isNotBlank()) {
            append(chapter.title)
            append('\n')
        }
        append(chapter.text)
    }

    val pages = mutableListOf<ReaderPage>()
    if (text.isNotBlank()) {
        var start = 0
        var line = 0
        var lineChars = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                line++
                lineChars = 0
                i++
                if (line >= linesPerPage) {
                    val pageText = text.substring(start, i).trim('\n')
                    if (pageText.isNotEmpty()) pages.add(ReaderPage.Text(pageText))
                    start = i
                    line = 0
                }
            } else {
                lineChars++
                if (lineChars >= charsPerLine) {
                    line++
                    lineChars = 0
                    if (line >= linesPerPage) {
                        val pageText = text.substring(start, i + 1).trim('\n')
                        if (pageText.isNotEmpty()) pages.add(ReaderPage.Text(pageText))
                        start = i + 1
                        line = 0
                    }
                }
                i++
            }
        }
        val tail = text.substring(start).trim('\n')
        if (tail.isNotEmpty()) pages.add(ReaderPage.Text(tail))
    }
    if (pages.isEmpty() && chapter.images.isEmpty()) pages.add(ReaderPage.Text(""))
    chapter.images.forEach { url -> pages.add(ReaderPage.Image(url)) }
    return pages
}
