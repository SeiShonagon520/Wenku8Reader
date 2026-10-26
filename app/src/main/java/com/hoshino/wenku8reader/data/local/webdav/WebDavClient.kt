package com.hoshino.wenku8reader.data.local.webdav

import com.hoshino.wenku8reader.data.local.LibraryBackupCodec
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class WebDavSnapshot(
    val fileName: String,
    val timestampMillis: Long,
    val sizeBytes: Long,
)

internal class WebDavException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Small RFC 4918 client: HTTPS only, Basic authentication, no cross-origin redirects. */
internal class WebDavClient(
    private val credentials: WebDavCredentials,
    private val httpClient: OkHttpClient = defaultClient(),
) {

    private val serverRoot: HttpUrl = credentials.serverUrl.toHttpUrl().also {
        require(it.isHttps) { "WebDAV 只支持 HTTPS 地址" }
    }
    private val directorySegments = credentials.remoteDirectory
        .trim('/')
        .split('/')
        .filter(String::isNotBlank)

    fun testConnection() {
        val request = davRequest(serverRoot)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "0")
            .build()
        execute(request) { response ->
            if (response.code != 207 && response.code != 200) {
                throw httpError(response.code, "测试 WebDAV 连接")
            }
        }
    }

    fun uploadSnapshot(file: File): WebDavSnapshot {
        require(file.isFile) { "本地备份文件不存在" }
        if (file.length() > LibraryBackupCodec.MAX_BACKUP_BYTES) throw IOException("备份文件过大")
        ensureRemoteDirectory()

        val timestamp = System.currentTimeMillis()
        val fileName = snapshotFileName(timestamp)
        val request = davRequest(fileUrl(fileName))
            .put(file.asRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request) { response ->
            if (response.code !in setOf(200, 201, 204)) {
                throw httpError(response.code, "上传 WebDAV 备份")
            }
        }
        return WebDavSnapshot(fileName, timestamp, file.length())
    }

    fun listSnapshots(): List<WebDavSnapshot> {
        val directory = directoryUrl()
        val request = davRequest(directory)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .build()
        return execute(request) { response ->
            if (response.code == 404) return@execute emptyList()
            if (response.code != 207 && response.code != 200) {
                throw httpError(response.code, "读取 WebDAV 备份列表")
            }
            val xml = response.body?.string() ?: throw IOException("WebDAV 返回了空列表")
            parseSnapshots(xml)
        }.sortedByDescending { it.timestampMillis }
    }

    fun downloadSnapshot(snapshot: WebDavSnapshot, destination: File) {
        require(parseSnapshotTimestamp(snapshot.fileName) != null) { "远端备份文件名无效" }
        val request = davRequest(fileUrl(snapshot.fileName)).get().build()
        execute(request) { response ->
            if (!response.isSuccessful) throw httpError(response.code, "下载 WebDAV 备份")
            val body = response.body ?: throw IOException("WebDAV 下载响应为空")
            if (body.contentLength() > LibraryBackupCodec.MAX_BACKUP_BYTES) {
                throw IOException("备份文件过大")
            }
            destination.parentFile?.mkdirs()
            var total = 0L
            try {
                body.byteStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > LibraryBackupCodec.MAX_BACKUP_BYTES) {
                                throw IOException("备份文件过大")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
            } catch (e: Exception) {
                destination.delete()
                throw e
            }
        }
    }

    fun deleteSnapshot(fileName: String) {
        require(parseSnapshotTimestamp(fileName) != null) { "拒绝删除非备份文件" }
        val request = davRequest(fileUrl(fileName)).delete().build()
        execute(request) { response ->
            if (response.code !in setOf(200, 202, 204, 404)) {
                throw httpError(response.code, "清理旧 WebDAV 备份")
            }
        }
    }

    fun ensureRemoteDirectory() {
        directorySegments.forEachIndexed { index, _ ->
            val parent = appendSegments(serverRoot, directorySegments.take(index + 1))
            val request = davRequest(parent).method("MKCOL", null).build()
            val created = execute(request) { response ->
                when (response.code) {
                    200, 201, 204 -> true
                    405 -> false // Collection may already exist; verify it below.
                    else -> throw httpError(response.code, "创建 WebDAV 目录")
                }
            }
            if (!created && !isCollection(parent)) {
                throw IOException("WebDAV 路径已存在，但不是目录")
            }
        }
    }

    private fun isCollection(url: HttpUrl): Boolean {
        val request = davRequest(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "0")
            .build()
        return execute(request) { response ->
            if (response.code != 207 && response.code != 200) return@execute false
            val xml = response.body?.string() ?: return@execute false
            val factory = secureDocumentFactory()
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            document.getElementsByTagNameNS("*", "collection").length > 0
        }
    }

    private fun parseSnapshots(xml: String): List<WebDavSnapshot> {
        val document = secureDocumentFactory()
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val responses = document.getElementsByTagNameNS("*", "response")
        return (0 until responses.length).mapNotNull { index ->
            val response = responses.item(index)
            val href = response.childText("href") ?: return@mapNotNull null
            val fileName = href.substringBefore('?').substringAfterLast('/').decodePercentPathSegment()
            val timestamp = parseSnapshotTimestamp(fileName) ?: return@mapNotNull null
            val size = response.childText("getcontentlength")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            WebDavSnapshot(fileName, timestamp, size)
        }.distinctBy { it.fileName }
    }

    private fun org.w3c.dom.Node.childText(localName: String): String? {
        val elements = (this as? org.w3c.dom.Element)?.getElementsByTagNameNS("*", localName)
            ?: return null
        return elements.item(0)?.textContent?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun String.decodePercentPathSegment(): String = runCatching {
        java.net.URLDecoder.decode(replace("+", "%2B"), Charsets.UTF_8.name())
    }.getOrDefault(this)

    private fun secureDocumentFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }

    private fun fileUrl(fileName: String): HttpUrl =
        appendSegments(serverRoot, directorySegments + fileName)

    private fun directoryUrl(): HttpUrl = appendSegments(serverRoot, directorySegments)

    private fun appendSegments(url: HttpUrl, segments: List<String>): HttpUrl =
        url.newBuilder().apply { segments.forEach(::addPathSegment) }.build()

    private fun davRequest(url: HttpUrl): Request.Builder {
        require(url.isHttps) { "WebDAV 请求必须使用 HTTPS" }
        return Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(credentials.username, credentials.password, StandardCharsets.UTF_8))
            .header("User-Agent", USER_AGENT)
    }

    private inline fun <T> execute(request: Request, block: (okhttp3.Response) -> T): T =
        httpClient.newCall(request).execute().use(block)

    private fun httpError(code: Int, operation: String): WebDavException {
        val message = when (code) {
            401, 403 -> "$operation：用户名、密码或目录权限不正确（HTTP $code）"
            404 -> "$operation：WebDAV 地址或远端路径不存在（HTTP $code）"
            405 -> "$operation：服务器不支持所需 WebDAV 方法（HTTP $code）"
            507 -> "$operation：WebDAV 空间不足（HTTP $code）"
            in 300..399 -> "$operation：服务器发生跳转，请填写最终 HTTPS 地址"
            else -> "${operation}失败（HTTP $code）"
        }
        return WebDavException(
            message,
            retryable = code == 408 || code == 429 || (code in 500..599 && code != 507),
        )
    }

    companion object {
        const val SNAPSHOT_PREFIX = "wenku8reader-backup-"
        const val SNAPSHOT_SUFFIX = ".json"
        private const val USER_AGENT = "Wenku8Reader-Android"
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>
        """

        fun snapshotFileName(timestampMillis: Long): String =
            "$SNAPSHOT_PREFIX$timestampMillis$SNAPSHOT_SUFFIX"

        fun parseSnapshotTimestamp(fileName: String): Long? {
            if (!fileName.startsWith(SNAPSHOT_PREFIX) || !fileName.endsWith(SNAPSHOT_SUFFIX)) return null
            return fileName.removePrefix(SNAPSHOT_PREFIX)
                .removeSuffix(SNAPSHOT_SUFFIX)
                .toLongOrNull()
                ?.takeIf { it > 0L }
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
