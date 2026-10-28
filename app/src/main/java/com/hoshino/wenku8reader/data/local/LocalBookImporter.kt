package com.hoshino.wenku8reader.data.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.hoshino.wenku8reader.data.BookInfo
import com.hoshino.wenku8reader.data.Chapter
import com.hoshino.wenku8reader.data.ChapterContent
import com.hoshino.wenku8reader.data.Volume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

/**
 * 本地外部书籍（TXT / EPUB）导入与读取解析器。
 *
 * 导入后的书籍存储在 `context.filesDir/local_books/{bookId}/` 下：
 * - `meta.json`: [BookInfo] 元数据快照；
 * - `toc.json`: [List<Volume>] 目录结构；
 * - `chapters/{cid}.json`: 对应章节正文及内嵌插图；
 * - `cover.png` / `cover.jpg`: 封面图（若存在）；
 * - `images/`: 本地插图资源。
 *
 * 本地书籍分配负数 ID（-1, -2, -3...），与文库8线上正数 ID 彻底隔离，绝不冲突。
 */
object LocalBookImporter {

    private const val LOCAL_BOOKS_DIR = "local_books"

    fun isLocalBook(bookId: Int): Boolean = bookId < 0

    fun getLocalBookDir(context: Context, bookId: Int): File =
        File(context.filesDir, "$LOCAL_BOOKS_DIR/$bookId")

    /**
     * 导入外部书籍文件（TXT 或 EPUB）。
     */
    suspend fun importFile(
        context: Context,
        uri: Uri,
        libraryStore: LibraryStore,
        targetShelf: String = "默认",
    ): Result<BookInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = queryFileName(context, uri) ?: "未知书籍.txt"
            val isEpub = fileName.endsWith(".epub", ignoreCase = true)
            val baseTitle = fileName.substringBeforeLast(".")

            val bookId = libraryStore.nextLocalBookId()
            val bookDir = getLocalBookDir(context, bookId)
            if (bookDir.exists()) bookDir.deleteRecursively()
            bookDir.mkdirs()
            val chaptersDir = File(bookDir, "chapters").apply { mkdirs() }
            val imagesDir = File(bookDir, "images").apply { mkdirs() }

            val parsedBook = if (isEpub) {
                parseEpub(context, uri, baseTitle, bookDir, chaptersDir, imagesDir, bookId)
            } else {
                parseTxt(context, uri, baseTitle, bookDir, chaptersDir, bookId)
            }

            val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
            val bookInfo = BookInfo(
                id = bookId,
                title = parsedBook.title.ifBlank { baseTitle },
                author = parsedBook.author.ifBlank { "本地导入" },
                status = "本地",
                lastUpdate = today,
                wordCount = "${parsedBook.totalChars}字",
                description = parsedBook.description.ifBlank { "本地导入文件：$fileName" },
                coverUrl = parsedBook.coverFile?.absolutePath?.let { "file://$it" },
                groupId = 0,
                tags = listOf("本地导入", if (isEpub) "EPUB" else "TXT"),
            )

            // 1. 持久化元数据
            saveMeta(bookDir, bookInfo)
            saveToc(bookDir, parsedBook.volumes)

            // 2. 加入本地数据库书架
            libraryStore.add(bookInfo, shelf = targetShelf)

            bookInfo
        }
    }

    fun getBookInfo(context: Context, bookId: Int): BookInfo? {
        val file = File(getLocalBookDir(context, bookId), "meta.json")
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            BookInfo(
                id = json.getInt("id"),
                title = json.optString("title"),
                author = json.optString("author"),
                status = json.optString("status"),
                lastUpdate = json.optString("lastUpdate"),
                wordCount = json.optString("wordCount"),
                description = json.optString("description"),
                coverUrl = json.optString("coverUrl").takeIf { it.isNotBlank() },
                groupId = json.optInt("groupId", 0),
                tags = json.optJSONArray("tags")?.let { arr ->
                    List(arr.length()) { arr.getString(it) }
                } ?: emptyList(),
            )
        }.getOrNull()
    }

    fun getLocalVolumes(context: Context, bookId: Int): List<Volume> {
        val file = File(getLocalBookDir(context, bookId), "toc.json")
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                val vObj = arr.getJSONObject(i)
                val vName = vObj.getString("name")
                val cArr = vObj.getJSONArray("chapters")
                val chapters = List(cArr.length()) { j ->
                    val cObj = cArr.getJSONObject(j)
                    Chapter(cid = cObj.getString("cid"), name = cObj.getString("name"))
                }
                Volume(name = vName, chapters = chapters)
            }
        }.getOrDefault(emptyList())
    }

    fun getLocalChapterContent(context: Context, bookId: Int, cid: String): ChapterContent? {
        val file = File(getLocalBookDir(context, bookId), "chapters/$cid.json")
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val imagesArr = json.optJSONArray("images")
            val images = if (imagesArr != null) {
                List(imagesArr.length()) { imagesArr.getString(it) }
            } else emptyList()
            ChapterContent(
                title = json.optString("title"),
                text = json.optString("text"),
                images = images,
            )
        }.getOrNull()
    }

    fun deleteLocalBook(context: Context, bookId: Int) {
        val dir = getLocalBookDir(context, bookId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- //
    // TXT 解析
    // ---------------------------------------------------------------- //

    private data class ParsedResult(
        val title: String,
        val author: String = "",
        val description: String = "",
        val coverFile: File? = null,
        val volumes: List<Volume>,
        val totalChars: Int,
    )

    private fun parseTxt(
        context: Context,
        uri: Uri,
        fallbackTitle: String,
        bookDir: File,
        chaptersDir: File,
        bookId: Int,
    ): ParsedResult {
        val bytes = readAllBytes(context, uri)
        val text = decodeText(bytes)

        val rawChapters = splitTxtChapters(text, fallbackTitle)
        val chapters = mutableListOf<Chapter>()
        var cidSeq = 1
        for ((title, body) in rawChapters) {
            val cid = (cidSeq++).toString()
            saveChapter(chaptersDir, cid, title, body)
            chapters.add(Chapter(cid, title))
        }

        val volumes = listOf(Volume(name = "正文", chapters = chapters))
        return ParsedResult(
            title = fallbackTitle,
            volumes = volumes,
            totalChars = text.length,
        )
    }

    internal fun splitTxtChapters(
        text: String,
        fallbackTitle: String,
    ): List<Pair<String, String>> {
        // 识别轻小说常见目录分章正则
        val chapterRegex = Pattern.compile(
            """(?m)^[\s\u3000]*(第[0-9一二三四五六七八九十百千万]+[卷章回节集幕篇话部]|Chapter\s*[0-9]+|序章|终章|尾声|后记|楔子|番外|Prologue|Epilogue)[\s\u3000]*([^\r\n]*)"""
        )
        val matcher = chapterRegex.matcher(text)

        val matchIndices = mutableListOf<Pair<Int, String>>()
        while (matcher.find()) {
            val matchedTitle = matcher.group().trim()
            matchIndices.add(matcher.start() to matchedTitle)
        }

        val result = mutableListOf<Pair<String, String>>()

        if (matchIndices.size >= 2) {
            // 前言/序文（首章前的文本）
            val firstStart = matchIndices.first().first
            if (firstStart > 50) {
                val introText = text.substring(0, firstStart).trim()
                if (introText.isNotBlank()) {
                    result.add("前言" to introText)
                }
            }

            for (i in matchIndices.indices) {
                val start = matchIndices[i].first
                val title = matchIndices[i].second
                val end = if (i + 1 < matchIndices.size) matchIndices[i + 1].first else text.length
                val body = text.substring(start, end).trim()
                result.add(title to body)
            }
        } else {
            // 未匹配到分章，按约 6000 字符均匀分段
            val chunkSize = 6000
            val len = text.length
            var offset = 0
            var partIdx = 1
            while (offset < len) {
                var nextOffset = (offset + chunkSize).coerceAtMost(len)
                // 尽量在换行符切断
                if (nextOffset < len) {
                    val newline = text.indexOf('\n', nextOffset)
                    if (newline in nextOffset..(nextOffset + 500)) {
                        nextOffset = newline + 1
                    }
                }
                val body = text.substring(offset, nextOffset).trim()
                val title = if (len <= chunkSize) fallbackTitle else "第 $partIdx 部分"
                result.add(title to body)
                offset = nextOffset
                partIdx++
            }
        }
        return result
    }

    // ---------------------------------------------------------------- //
    // EPUB 解析
    // ---------------------------------------------------------------- //

    private fun parseEpub(
        context: Context,
        uri: Uri,
        fallbackTitle: String,
        bookDir: File,
        chaptersDir: File,
        imagesDir: File,
        bookId: Int,
    ): ParsedResult {
        val entryMap = mutableMapOf<String, ByteArray>()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val bos = ByteArrayOutputStream()
                        zis.copyTo(bos)
                        entryMap[entry.name] = bos.toByteArray()
                    }
                    entry = zis.nextEntry
                }
            }
        } ?: throw IllegalStateException("无法读取 EPUB 文件")

        // 1. 寻找 container.xml 确定 opf 路径
        val containerBytes = entryMap["META-INF/container.xml"]
            ?: throw IllegalStateException("未找到 META-INF/container.xml")
        val containerXml = String(containerBytes, Charsets.UTF_8)
        val opfPathMatcher = Pattern.compile("""full-path\s*=\s*["']([^"']+)["']""").matcher(containerXml)
        val opfPath = if (opfPathMatcher.find()) opfPathMatcher.group(1) else "content.opf"
        val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

        val opfBytes = entryMap[opfPath] ?: throw IllegalStateException("未找到 $opfPath")
        val opfXml = String(opfBytes, Charsets.UTF_8)

        // 2. 解析元数据
        val titleMatcher = Pattern.compile("""<dc:title[^>]*>([^<]+)</dc:title>""").matcher(opfXml)
        val title = if (titleMatcher.find()) titleMatcher.group(1)?.trim() ?: fallbackTitle else fallbackTitle

        val authorMatcher = Pattern.compile("""<dc:creator[^>]*>([^<]+)</dc:creator>""").matcher(opfXml)
        val author = if (authorMatcher.find()) authorMatcher.group(1)?.trim() ?: "未知作者" else "未知作者"

        val descMatcher = Pattern.compile("""<dc:description[^>]*>([^<]+)</dc:description>""").matcher(opfXml)
        val description = if (descMatcher.find()) descMatcher.group(1)?.trim() ?: "" else ""

        // 3. 解析 manifest (id -> href)
        val manifestMap = mutableMapOf<String, String>() // id -> href
        val itemMatcher = Pattern.compile("""<item\s+[^>]*>""").matcher(opfXml)
        var coverHref: String? = null
        while (itemMatcher.find()) {
            val itemTag = itemMatcher.group()
            val id = extractAttr(itemTag, "id")
            val href = extractAttr(itemTag, "href")
            val mediaType = extractAttr(itemTag, "media-type")
            val properties = extractAttr(itemTag, "properties")
            if (id != null && href != null) {
                manifestMap[id] = href
                if (properties?.contains("cover-image") == true || id.contains("cover", ignoreCase = true)) {
                    coverHref = href
                }
            }
        }

        // 提取封面图片
        var coverFile: File? = null
        if (coverHref != null) {
            val fullCoverPath = normalizeZipPath(opfDir + coverHref)
            entryMap[fullCoverPath]?.let { imgBytes ->
                val ext = if (coverHref.endsWith(".png", ignoreCase = true)) ".png" else ".jpg"
                val f = File(bookDir, "cover$ext")
                f.writeBytes(imgBytes)
                coverFile = f
            }
        }

        // 4. 解析 spine 确定章节阅读次序
        val spineItems = mutableListOf<String>()
        val itemrefMatcher = Pattern.compile("""<itemref\s+[^>]*idref\s*=\s*["']([^"']+)["'][^>]*>""").matcher(opfXml)
        while (itemrefMatcher.find()) {
            val idref = itemrefMatcher.group(1) ?: continue
            spineItems.add(idref)
        }

        val chapters = mutableListOf<Chapter>()
        var totalChars = 0
        var cidSeq = 1

        for (idref in spineItems) {
            val relHref = manifestMap[idref] ?: continue
            val fullHref = normalizeZipPath(opfDir + relHref)
            val chapterBytes = entryMap[fullHref] ?: continue
            val rawHtml = String(chapterBytes, Charsets.UTF_8)

            val parsedChapter = htmlToCleanText(rawHtml, opfDir, entryMap, imagesDir)
            if (parsedChapter.text.isBlank() && parsedChapter.images.isEmpty()) continue

            val chapterTitle = extractChapterTitle(rawHtml).ifBlank { "第 $cidSeq 章" }
            val cid = (cidSeq++).toString()
            saveChapterContent(chaptersDir, cid, chapterTitle, parsedChapter.text, parsedChapter.images)
            chapters.add(Chapter(cid, chapterTitle))
            totalChars += parsedChapter.text.length
        }

        val volumes = listOf(Volume(name = "正文", chapters = chapters))
        return ParsedResult(
            title = title,
            author = author,
            description = description,
            coverFile = coverFile,
            volumes = volumes,
            totalChars = totalChars,
        )
    }

    private data class CleanHtml(val text: String, val images: List<String>)

    private fun htmlToCleanText(
        html: String,
        baseDir: String,
        entryMap: Map<String, ByteArray>,
        imagesDir: File,
    ): CleanHtml {
        val images = mutableListOf<String>()

        // 提取并保存 <img> 插图
        val imgMatcher = Pattern.compile("""<img\s+[^>]*src\s*=\s*["']([^"']+)["'][^>]*>""", Pattern.CASE_INSENSITIVE).matcher(html)
        while (imgMatcher.find()) {
            val src = imgMatcher.group(1) ?: continue
            val fullImgPath = normalizeZipPath(baseDir + src)
            val imgBytes = entryMap[fullImgPath]
            if (imgBytes != null) {
                val imgName = File(src).name
                val outFile = File(imagesDir, imgName)
                if (!outFile.exists()) {
                    outFile.writeBytes(imgBytes)
                }
                images.add("file://${outFile.absolutePath}")
            }
        }

        // 转换 HTML 为纯文本段落
        var text = html
            .replace(Regex("""(?i)<style[^>]*>.*?</style>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(?i)<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)</p>"""), "\n\n")
            .replace(Regex("""(?i)</div>"""), "\n")
            .replace(Regex("""(?i)<h[1-6][^>]*>"""), "\n\n")
            .replace(Regex("""(?i)</h[1-6]>"""), "\n\n")
            .replace(Regex("""<[^>]+>"""), "") // 去除其余所有 HTML 标签

        // 反转义常用 HTML 实体
        text = text
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")

        // 规范化空行
        val cleanParagraphs = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        return CleanHtml(cleanParagraphs, images)
    }

    private fun extractChapterTitle(html: String): String {
        val hMatcher = Pattern.compile("""<h[1-3][^>]*>(.*?)</h[1-3]>""", Pattern.CASE_INSENSITIVE).matcher(html)
        if (hMatcher.find()) {
            val h = hMatcher.group(1)
            if (h != null) return h.replace(Regex("""<[^>]+>"""), "").trim()
        }
        val titleMatcher = Pattern.compile("""<title[^>]*>(.*?)</title>""", Pattern.CASE_INSENSITIVE).matcher(html)
        if (titleMatcher.find()) {
            val t = titleMatcher.group(1)
            if (t != null) return t.trim()
        }
        return ""
    }

    internal fun extractAttr(tag: String, attr: String): String? {
        val m = Pattern.compile("""$attr\s*=\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE).matcher(tag)
        return if (m.find()) m.group(1) else null
    }

    internal fun normalizeZipPath(path: String): String {
        val parts = path.split("/").filter { it.isNotEmpty() && it != "." }
        val stack = mutableListOf<String>()
        for (p in parts) {
            if (p == "..") {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            } else {
                stack.add(p)
            }
        }
        return stack.joinToString("/")
    }

    // ---------------------------------------------------------------- //
    // 文件读写与编码侦测
    // ---------------------------------------------------------------- //

    private fun readAllBytes(context: Context, uri: Uri): ByteArray {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bos = ByteArrayOutputStream()
            stream.copyTo(bos)
            return bos.toByteArray()
        } ?: throw IllegalStateException("无法打开文件输入流")
    }

    internal fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        // 尝试严格验证 UTF-8
        val utf8Decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val cb = runCatching { utf8Decoder.decode(ByteBuffer.wrap(bytes)) }.getOrNull()
        if (cb != null) {
            return cb.toString()
        }

        // 回退至中文超集 GB18030 (包含 GBK / GB2312)
        val gbCharset = runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.UTF_8)
        return String(bytes, gbCharset)
    }

    private fun saveChapter(chaptersDir: File, cid: String, title: String, text: String) {
        saveChapterContent(chaptersDir, cid, title, text, emptyList())
    }

    private fun saveChapterContent(chaptersDir: File, cid: String, title: String, text: String, images: List<String>) {
        val json = JSONObject().apply {
            put("cid", cid)
            put("title", title)
            put("text", text)
            put("images", JSONArray(images))
        }
        File(chaptersDir, "$cid.json").writeText(json.toString())
    }

    private fun saveMeta(bookDir: File, info: BookInfo) {
        val json = JSONObject().apply {
            put("id", info.id)
            put("title", info.title)
            put("author", info.author)
            put("status", info.status)
            put("lastUpdate", info.lastUpdate)
            put("wordCount", info.wordCount)
            put("description", info.description)
            put("coverUrl", info.coverUrl ?: "")
            put("groupId", info.groupId ?: 0)
            put("tags", JSONArray(info.tags))
        }
        File(bookDir, "meta.json").writeText(json.toString())
    }

    private fun saveToc(bookDir: File, volumes: List<Volume>) {
        val arr = JSONArray()
        for (v in volumes) {
            val vObj = JSONObject().apply {
                put("name", v.name)
                val cArr = JSONArray()
                for (c in v.chapters) {
                    cArr.put(JSONObject().apply {
                        put("cid", c.cid)
                        put("name", c.name)
                    })
                }
                put("chapters", cArr)
            }
            arr.put(vObj)
        }
        File(bookDir, "toc.json").writeText(arr.toString())
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
