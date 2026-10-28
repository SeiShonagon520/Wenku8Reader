package com.hoshino.wenku8reader.data

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JsResult
import android.webkit.ValueCallback
import com.hoshino.wenku8reader.Wenku8Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import com.hoshino.wenku8reader.data.local.HtmlDiskCache

/**
 * Minimal wenku8.net (jieqi CMS) API client.
 * All methods are suspend and safe to call from any dispatcher.
 *
 * Networking details:
 * - The site declares `charset=gbk` but actually emits GB18030 (a strict superset),
 *   so all pages are decoded with GB18030 to avoid mojibake on `•・〜` etc.
 * - Requests carry browser-like navigation headers (Sec-Fetch-*, Accept, etc.) to
 *   reduce the chance of triggering Cloudflare/anti-bot challenges.
 * - A global adaptive throttle plus exponential back-off handles 429/5xx.
 */
class Wenku8Client(
    context: Context,
    /** 用户选定的主站镜像（设置页可切换）；缺省用 wenku8.net */
    private val primaryMirrorProvider: () -> String = { DEFAULT_BASE },
    /** 内置账号凭据（供需登录接口 ensureLoggedIn 静默登录用） */
    private val defaultCredentials: () -> Pair<String, String>? = { null },
    /** 磁盘缓存上限（MB，设置页可调）；每次写入前同步，动态生效 */
    private val cacheMaxMbProvider: () -> Int = { 30 },
) {

    private val appContext = context.applicationContext
    private val cookieStore = CookieStore(appContext)
    private val htmlCache = HtmlDiskCache(appContext, cacheMaxMbProvider().toLong() * 1024 * 1024)
    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .cookieJar(cookieStore)
        .build()

    /**
     * Chromium network stack; its TLS fingerprint usually bypasses Cloudflare blocks.
     * 用 [Lazy] 持有，便于 [close] 判断引擎是否真的创建过（避免 close 反而触发创建）。
     */
    private val cronetEngineLazy: Lazy<CronetEngine?> = lazy {
        runCatching { CronetEngine.Builder(appContext).build() }.getOrNull()
    }
    private val cronetEngine: CronetEngine? get() = cronetEngineLazy.value

    /**
     * Cronet 回调执行器。复用 IO 调度器而不是自建单线程池：
     * 自建线程池的生命周期与应用等长却从不 `shutdown()`（线程泄漏），
     * 复用调度器后无需再管理其释放。
     */
    private val cronetExecutor: Executor = Dispatchers.IO.asExecutor()

    /**
     * 统一限流组件。原先 `lastRequest` / `lastSearch` / `rate` 三个字段与
     * `pace()` / `adjustRate()` / 搜索内联节流散落在类中，读代码要跳半个文件
     * 才能拼出"一次请求究竟等了几次"。收敛为一个所有者后策略清晰；
     * 分层本身保留——全局请求间隔、自适应速率、搜索硬间隔语义不同，不能压成一个延时。
     */
    private val pacer = RatePacer(PACE_BASE_INTERVAL_MS, SEARCH_MIN_INTERVAL_MS)

    /**
     * 登录互斥锁：见 [ensureLoggedIn]。同一时刻只允许一个登录流程在跑，
     * 排队者进入后先复查登录态，不重复发请求。
     */
    private val loginMutex = Mutex()

    /**
     * 隐藏 WebView 互斥锁：Cloudflare 兜底同一时刻只允许一个 WebView 在跑。
     *
     * 多个 WebView 同时解挑战会在主线程同时跑 JS，既拖慢首帧、抬高内存，
     * 也更容易被风控判定为异常；串行化后第二个请求通常直接复用刚拿到的
     * cf_clearance 走直连快路径，实际并不慢。
     */
    private val webViewMutex = Mutex()

    companion object {
        /** 默认主站镜像（单一来源：[Wenku8Hosts]）。 */
        private const val DEFAULT_BASE = Wenku8Hosts.DEFAULT_BASE
        private const val DL = "https://dl.wenku8.com"

        /** 可用镜像；供设置页等直接消费，避免多处各自硬编码。 */
        val MIRRORS: List<String> get() = Wenku8Hosts.MIRRORS

        // ---- 官方 App API（无网页 CF 验证，参考 LightNovelReader）----
        private const val APP_API_OFFICIAL = "http://app.wenku8.com/android.php"
        private const val APP_API_RELAY = "https://wenku8-relay.mewx.org"
        private const val APP_VER = "1.24-pico-mochi"
        private const val APP_UA =
            "Dalvik/2.1.0 (Linux; U; Android 15; 23114RD76B Build/AQ3A.240912.001)"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        private val GB18030: Charset = Charset.forName("GB18030")
        private val RATE_CODES = setOf(429, 500, 502, 503, 504)

        /** 登录页特征串：命中说明拿到的是登录页而非目标内容（不缓存、视为无效）。 */
        private const val LOGIN_PATH = "login.php"

        /** 站点限流错误页特征："两次搜索的间隔时间不得少于 5 秒"。 */
        private const val THROTTLE_MARK = "两次搜索的间隔时间"

        // ---- 搜索节流/重试（有界，避免无界递归）----
        private const val SEARCH_MIN_INTERVAL_MS = 5000L
        private const val SEARCH_MAX_ATTEMPTS = 3
        private const val SEARCH_RETRY_DELAY_MS = 5000L

        /** 全局请求间隔基数（毫秒），实际间隔 = 该值 × 自适应速率。 */
        private const val PACE_BASE_INTERVAL_MS = 600L

        /** 详情页/单书命中链接（`/book/{id}.htm`）。 */
        private val BOOK_URL = Regex("/book/(\\d+)\\.htm")

        /** Cronet 请求等待上限（秒）；超时后 cancel 并返回 null。 */
        private const val CRONET_TIMEOUT_SECONDS = 4L

        /** 单个隐藏 WebView 解挑战的等待上限（毫秒）。 */
        private const val WEBVIEW_TIMEOUT_MS = 6_000L

        /**
         * 等待 WebView 互斥锁的上限（毫秒）：比单个 WebView 的超时更长，
         * 保证"排队 + 执行"整体有界，排队的请求不会无限期挂着。
         */
        private const val WEBVIEW_LOCK_TIMEOUT_MS = 8_000L

        // ---- 磁盘缓存 TTL（见 HtmlDiskCache）----
        const val TTL_HOME = 60L * 60 * 1000                 // 首页 1 小时
        const val TTL_BOOK = 7L * 24 * 60 * 60 * 1000        // 详情/目录 7 天
        const val TTL_CHAPTER = 30L * 24 * 60 * 60 * 1000    // 章节正文 30 天
        const val TTL_TAG_BOOKS = 24L * 60 * 60 * 1000       // 标签书单 1 天

        /**
         * 内置分类清单（标准 wenku8 标签，参考 LightNovelReader 的 tagList）。
         * 直接作为「标签」页的分类来源：秒回、无需登录/网络；
         * 每个分类下的书籍仍按需在线抓取（tagBooks）。
         */
        private val BUILT_IN_TAGS = listOf(
            "校园", "青春", "恋爱", "治愈", "群像", "竞技", "音乐", "美食", "旅行", "欢乐向",
            "经营", "职场", "斗智", "脑洞", "宅文化", "穿越", "奇幻", "魔法", "异能", "战斗",
            "科幻", "机战", "战争", "冒险", "龙傲天", "悬疑", "犯罪", "复仇", "黑暗", "猎奇",
            "惊悚", "间谍", "末日", "游戏", "大逃杀", "青梅竹马", "妹妹", "女儿", "JK", "JC",
            "大小姐", "性转", "伪娘", "人外", "后宫", "百合", "耽美", "NTR", "女性视角",
        )

        /** 随机 Android Chrome UA（参考 LightNovelReader：随机 Build ID 与子版本）。 */
        private fun randomAndroidUa(): String {
            val os = listOf("8.1.0", "9", "10", "11", "12", "13", "14", "15")
            val device = listOf(
                "Pixel 7; Build/TQ3A.230805.001",
                "Pixel 6; Build/TQ3A.230805.001",
                "SM-G991B; Build/SP1A.210812.016",
                "SM-G998B; Build/SP1A.210812.016",
                "SM-S9010; Build/TD1A.220804.031",
                "Redmi K40; Build/RKQ1.200826.002",
                "Xiaomi 12; Build/SKQ1.211006.001",
                "OPPO Reno6; Build/RP1A.200720.011",
                "vivo X60; Build/RP1A.200720.012",
            )
            // Chrome 主版本 100-140，子版本/构建/补丁号随机（增大 UA 熵）
            val chrome = 100 + Random.nextInt(41)
            val minor = Random.nextInt(0, 4000)
            val build = Random.nextInt(0, 200)
            val patch = Random.nextInt(0, 150)
            return "Mozilla/5.0 (Linux; Android ${os.random()}; ${device.random()}) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome.0.$minor.$build Mobile Safari/537.$patch"
        }

        /** 检查文库8/Jieqi CMS 返回的文本中是否包含凭据认证错误 */
        private fun extractCredentialError(content: String): String? {
            val s = content
            return when {
                s.contains("用户名或密码错误") || s.contains("用户名和密码不匹配") || s.contains("用户名与密码不匹配") ->
                    "用户名或密码错误，请检查后重新输入"
                s.contains("密码错误") || s.contains("密码不正确") ->
                    "密码错误，请检查后重新输入"
                s.contains("该用户不存在") || s.contains("用户不存在") || s.contains("用户名不存在") ->
                    "该用户名不存在，请检查账号拼写"
                s.contains("验证码错误") || s.contains("请输入验证码") || s.contains("验证码不正确") ->
                    "触发站点验证码保护，请稍后重试或在网页端先完成验证"
                s.contains("账号已被锁定") || s.contains("账号已被封禁") || s.contains("禁止访问") ->
                    "该账号已被文库8站点限制访问"
                s.contains("两次登录尝试间隔") || s.contains("登录过于频繁") ->
                    "登录尝试过于频繁，请稍候 5 秒后再试"
                s.contains("您的 IP 暂时被禁止") ->
                    "当前网络 IP 被站点临时限制，请切换网络或稍后重试"
                s.contains("登录失败") ->
                    "登录失败，请检查账号密码"
                else -> null
            }
        }
    }

    // ------------------------------------------------------------------ //
    // 主镜像选择（设置页可切换）+ cf_clearance UA 绑定
    // ------------------------------------------------------------------ //

    /**
     * 用户选定的主镜像优先，其余镜像按固定顺序兜底。
     *
     * 结果按主镜像缓存：`uaFor`/`tryDirect`/`fetchWithBypass` 每次请求都会读取该属性，
     * 原实现是 `get` 属性里 `buildList + filter` 重新构造，属纯无谓分配。
     * 仅在设置页切换主镜像（[primaryMirrorProvider] 返回值变化）时重建。
     */
    @Volatile private var cachedPrimary: String? = null
    @Volatile private var cachedMirrors: List<String> = emptyList()

    private val mirrors: List<String>
        get() {
            val primary = primaryMirrorProvider().ifBlank { DEFAULT_BASE }
            if (primary != cachedPrimary) {
                cachedMirrors = buildList {
                    add(primary)
                    MIRRORS.filter { it != primary }.forEach { add(it) }
                }
                cachedPrimary = primary
            }
            return cachedMirrors
        }

    private val base: String get() = mirrors.first()

    private val networkPrefs by lazy {
        appContext.getSharedPreferences("wenku8_network", Context.MODE_PRIVATE)
    }

    /** 统一系统真实 WebView UserAgent，确保 OkHttp、Cronet 与 WebView 拥有 100% 一致的浏览器指纹。 */
    private val systemDefaultUa: String by lazy {
        runCatching {
            WebSettings.getDefaultUserAgent(appContext)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: UA
    }

    /**
     * WebView 解出 Cloudflare 挑战时使用的 UA（按主机记录持久化到 SharedPreferences）。
     * cf_clearance 令牌与该 UA 绑定，后续 OkHttp/Cronet 复用令牌时必须使用同一 UA。
     */
    private val challengeUa = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun uaFor(url: String): String {
        val host = runCatching { url.toHttpUrl().host }.getOrNull() ?: return systemDefaultUa
        return challengeUa[host]
            ?: networkPrefs.getString("ua_$host", null)
            ?: systemDefaultUa
    }

    private fun recordChallengeUa(host: String, ua: String) {
        if (host.isNotBlank() && ua.isNotBlank()) {
            challengeUa[host] = ua
            runCatching { networkPrefs.edit().putString("ua_$host", ua).apply() }
        }
    }

    /** 切换主镜像/清空会话时调用：清空全部 Cookie 与 UA 绑定。 */
    fun clearCookies() {
        challengeUa.clear()
        runCatching { networkPrefs.edit().clear().apply() }
        cookieStore.clear()
    }

    /**
     * 仅清空用户登录态 Cookie，保留 Cloudflare 挑战令牌（cf_clearance 等）与绑定的 UA。
     * 在登录/切换账号时调用，避免重跑耗时的 Cloudflare 挑战。
     */
    fun clearUserSession() {
        val baseHttp = runCatching { base.toHttpUrl() }.getOrNull()
        if (baseHttp != null) {
            val list = cookieStore.loadForRequest(baseHttp)
            val keep = list.filter {
                it.name.startsWith("cf_", ignoreCase = true) ||
                    it.name.startsWith("__cf", ignoreCase = true) ||
                    it.name.startsWith("_cf", ignoreCase = true)
            }
            cookieStore.clear()
            for (c in keep) {
                cookieStore.saveRaw(baseHttp, "${c.name}=${c.value}")
            }
            cookieStore.persist()
        }
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setCookie(base, "jieqiUserInfo=; Max-Age=0; Path=/")
            cm.setCookie(base, "jieqiUserId=; Max-Age=0; Path=/")
            cm.setCookie(base, "jieqiUserName=; Max-Age=0; Path=/")
            cm.setCookie(base, "PHPSESSID=; Max-Age=0; Path=/")
            cm.flush()
        }
    }

    private fun syncCookiesToWebView(targetUrl: String) {
        runCatching {
            val httpUrl = targetUrl.toHttpUrl()
            val list = cookieStore.loadForRequest(httpUrl)
            if (list.isEmpty()) return
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            for (c in list) {
                cm.setCookie(targetUrl, "${c.name}=${c.value}; Path=/")
            }
            cm.flush()
        }
    }

    private fun syncCookiesFromWebView(targetUrl: String) {
        runCatching {
            val cm = CookieManager.getInstance()
            val raw = cm.getCookie(targetUrl) ?: ""
            if (raw.isNotBlank()) {
                cookieStore.saveRaw(targetUrl.toHttpUrl(), raw)
            }
            val baseRaw = cm.getCookie(base) ?: ""
            if (baseRaw.isNotBlank()) {
                cookieStore.saveRaw(base.toHttpUrl(), baseRaw)
            }
            cookieStore.persist()
        }
    }

    // ------------------------------------------------------------------ //
    // 内存缓存（参考 LightNovelReader 的 2h Cache）
    // 均带 LRU 容量上限：只有 TTL 时，用户长读一本书会让 chapterCache 把
    // 全部已读章节正文（每章数十 KB）长期留在内存里。
    // ------------------------------------------------------------------ //
    private val infoCache = TimedCache(2 * 60 * 60 * 1000L, maxEntries = 128)
    private val tocCache = TimedCache(2 * 60 * 60 * 1000L, maxEntries = 32)
    private val chapterCache = TimedCache(30 * 60 * 1000L, maxEntries = 64)

    /**
     * 同键并发去重：缓存只挡"已完成"的重复访问，挡不住"同时发起"的重复请求
     * （详情页 + 目录页 + 阅读器 + 下载器可能同时要同一本书的数据）。
     * 见 [SingleFlight] 的说明；章节正文按 `bookId+cid` 分键，不同章节仍可并行。
     */
    private val singleFlight = SingleFlight()

    /**
     * 释放网络资源（应用退出或数据源被替换时调用）。
     * 仅在 Cronet 引擎确实创建过时才 shutdown，避免"释放"反而触发初始化。
     */
    fun close() {
        if (cronetEngineLazy.isInitialized()) {
            runCatching { cronetEngineLazy.value?.shutdown() }
        }
    }

    /** App API 串行限流（官方 App 行为：同时间仅一个请求）。 */
    private val appApiSemaphore = Semaphore(1)

    // ------------------------------------------------------------------ //
    // retry（节流状态统一在 [pacer]）
    // ------------------------------------------------------------------ //
    private suspend fun execute(req: Request, retries: Int = 3): Response {
        var attempt = 0
        while (true) {
            pacer.pace()
            val resp = withContext(Dispatchers.IO) { okHttp.newCall(req).execute() }
            if (resp.code == 403) {
                pacer.adjust(ok = false)
                resp.close()
                throw IOException("请求被拦截(HTTP 403)")
            }
            if (resp.code in RATE_CODES) {
                pacer.adjust(ok = false)
                if (attempt < retries) {
                    resp.close()
                    val backoff = min(1500L * (1L shl attempt), 30000L)
                    delay(backoff)
                    attempt++
                    continue
                }
                resp.close()
                val msg = if (resp.code == 429) {
                    "站点限流(HTTP 429)，请稍后重试"
                } else {
                    "请求失败(HTTP ${resp.code})，请稍后重试"
                }
                throw IOException(msg)
            }
            pacer.adjust(ok = true)
            return resp
        }
    }

    private suspend fun readBytes(resp: Response): ByteArray =
        withContext(Dispatchers.IO) { resp.use { it.body?.bytes() ?: ByteArray(0) } }

    // ------------------------------------------------------------------ //
    // low-level requests
    // ------------------------------------------------------------------ //
    private fun Request.Builder.browserHeaders(
        referer: String?,
        ua: String = UA,
    ): Request.Builder {
        header("User-Agent", ua)
        header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        header("Cache-Control", "max-age=0")
        header("Upgrade-Insecure-Requests", "1")
        header("Sec-Fetch-Dest", "document")
        header("Sec-Fetch-Mode", "navigate")
        header("Sec-Fetch-Site", "none")
        header("Sec-Fetch-User", "?1")
        referer?.let { header("Referer", it) }
        return this
    }

    private suspend fun getBytes(url: String, retries: Int = 3, ua: String? = null): ByteArray {
        val usedUa = ua ?: uaFor(url)
        val req = Request.Builder()
            .url(url)
            .browserHeaders(refererFor(url), usedUa)
            .build()
        return readBytes(execute(req, retries))
    }

    /** Referer matching the request's own host (mirror-safe). */
    private fun refererFor(url: String): String? = runCatching {
        val h = url.toHttpUrl()
        "${h.scheme}://${h.host}/"
    }.getOrNull()

    /** Fetches a page and decodes it as GB18030 on the IO dispatcher. */
    private suspend fun getHtml(url: String, retries: Int = 3, ua: String? = null): String =
        withContext(Dispatchers.IO) {
            val bytes = getBytes(url, retries, ua)
            String(bytes, GB18030)
        }

    /**
     * 带本地磁盘缓存的抓取：命中且未过期直接返回（免二次加载）。
     * 仅缓存「非 CF 挑战页/非登录页」的有效内容，避免缓存到垃圾页。
     * [category] 用于按内容类型分组管理（home/book/chapter/tag）。
     */
    private suspend fun getHtmlCached(
        url: String,
        ttlMs: Long,
        category: String = "other",
        retries: Int = 3,
        ua: String? = null,
    ): String {
        val usedUa = ua ?: uaFor(url)
        htmlCache.get(url, ttlMs, category)?.let { return it }
        val html = getHtml(url, retries, usedUa)
        if (html.isNotBlank() && !isChallenge(html) && !html.contains(LOGIN_PATH)) {
            // 写入前同步用户配置的缓存上限（动态生效）
            htmlCache.setMaxBytes(cacheMaxMbProvider().toLong() * 1024 * 1024)
            htmlCache.put(url, html, category)
        }
        return html
    }

    // ---- 缓存管理（设置页入口）----

    /** 各类型磁盘缓存大小（字节）。 */
    fun cacheStats(): Map<String, Long> = htmlCache.sizeByCategory()

    /** 网页离线缓存（`filesDir/html_cache`）总大小（字节）。 */
    fun htmlCacheSize(): Long = htmlCache.totalSize()

    /**
     * 立即按当前设置应用缓存上限。
     *
     * 原先上限只在**写入时**读取（见 [getHtmlCached]），用户把上限从 500MB 调到 30MB 后，
     * 超出的部分要等到下一次写入才会被裁掉——设置页看起来"没生效"。调整上限时显式调用本方法。
     */
    fun applyCacheLimit() {
        htmlCache.setMaxBytes(cacheMaxMbProvider().toLong() * 1024 * 1024)
    }

    /** 清理磁盘缓存（[category] = null 清全部）；清全部时同时清空内存缓存。 */
    fun clearCache(category: String? = null) {
        htmlCache.clear(category)
        if (category == null) {
            infoCache.clear()
            tocCache.clear()
            chapterCache.clear()
        }
    }

    private fun gbkForm(pairs: List<Pair<String, String>>): RequestBody {
        val sb = StringBuilder()
        pairs.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append('&')
            sb.append(URLEncoder.encode(k, "GBK"))
                .append('=')
                .append(URLEncoder.encode(v, "GBK"))
        }
        return sb.toString().toByteArray(GB18030)
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
    }

    private suspend fun postForm(url: String, pairs: List<Pair<String, String>>): Response {
        val req = Request.Builder()
            .url(url)
            .browserHeaders(refererFor(url) ?: "$base/login.php", uaFor(url))
            .post(gbkForm(pairs))
            .build()
        return execute(req, retries = 2)
    }

    // ------------------------------------------------------------------ //
    // account
    // ------------------------------------------------------------------ //

    /** 是否持有有效会话：以 jieqiUserInfo 会话 Cookie 为准（旧版 frmlogin 标记已随页面改版失效）。 */
    private fun hasSession(): Boolean =
        cookieStore.loadForRequest(base.toHttpUrl()).any {
            it.name == "jieqiUserInfo" && it.value.isNotBlank()
        }

    /** 是否已取得 Cloudflare 验证令牌（cf_clearance）。 */
    private fun hasCfClearance(): Boolean =
        cookieStore.loadForRequest(base.toHttpUrl()).any {
            it.name == "cf_clearance" && it.value.isNotBlank()
        }

    suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) { hasSession() }

    /**
     * 确保已登录（供 tags/bookcase 等需登录接口调用）。
     *
     * 加锁的原因：启动时静默登录、探索页 `tags()`、书架页 `bookcase()` 可能**同时**
     * 发现"未登录"并各自发起一次登录，产生重复请求、重复写 Cookie，还会让站点多算几次
     * 风控样本。锁内必须**再次检查**登录态——先到的协程可能已经把登录做完了，
     * 排队者直接复用结果即可。
     */
    suspend fun ensureLoggedIn(): Boolean {
        if (isLoggedIn()) return true
        return loginMutex.withLock {
            if (isLoggedIn()) return@withLock true
            val creds = defaultCredentials() ?: return@withLock false
            loginInternal(creds.first, creds.second).success
        }
    }

    /** 显式登录（切换镜像/登录个人账号等）；与 [ensureLoggedIn] 共用同一把锁，先清理旧会话再登录。 */
    suspend fun login(user: String, pass: String): LoginResult =
        loginMutex.withLock {
            clearUserSession()
            val result = loginInternal(user, pass)
            if (result.success) {
                cookieStore.persist()
            }
            result
        }

    private suspend fun loginInternal(user: String, pass: String): LoginResult =
        withContext(Dispatchers.IO) {
            // 1. 若已有 cf_clearance，优先尝试 OkHttp 快速登录
            val directResult = runCatchingNotCancelling {
                val resp = postForm(
                    "$base/login.php?do=submit" +
                        "&jumpurl=${URLEncoder.encode("$base/index.php", "UTF-8")}",
                    listOf(
                        "username" to user,
                        "password" to pass,
                        "usecookie" to "315360000",
                        "action" to "login",
                        "submit" to "\u00A0\u00A0\u767B\u00A0\u00A0\u5F55\u00A0",
                    )
                )
                val bodyText = readBytes(resp).toString(GB18030)
                Log.d("Wenku8Login", "direct login resp code=${resp.code}, bodyLen=${bodyText.length}")
                val credentialError = extractCredentialError(bodyText)
                if (credentialError != null) {
                    return@runCatchingNotCancelling LoginResult(false, credentialError)
                }
                if (hasSession()) {
                    cookieStore.persist()
                    return@runCatchingNotCancelling LoginResult(true, "登录成功")
                }
                null
            }.getOrNull()

            if (directResult != null) {
                return@withContext directResult
            }

            // 2. 若直接 POST 遭遇 Cloudflare 403 拦截或无令牌，升级至真实后台 WebView 登录流
            val wvResult = webViewLogin(user, pass)
            if (wvResult.success) {
                cookieStore.persist()
            }
            wvResult
        }

    /**
     * 等待并获取前台 Activity（最多等待 1.5 秒），供隐藏 WebView 附着到有效 Window。
     */
    private suspend fun getForegroundActivity(): Activity? {
        var act = Wenku8Application.currentActivity
        if (act != null && !act.isFinishing && !act.isDestroyed) return act
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 1500) {
            delay(100)
            act = Wenku8Application.currentActivity
            if (act != null && !act.isFinishing && !act.isDestroyed) return act
        }
        return null
    }

    /**
     * 真实 WebView 登录流：
     * 用于首次启动无 cf_clearance、或 OkHttp 登录遭遇 Cloudflare 403 时的终极兜底。
     * 1. 启动 WebView 载入 login.php，自然触发并跑通 Cloudflare 挑战并截获 cf_clearance。
     * 2. 当登录表单就绪时，注入 JavaScript 自动填入账号密码并触发表单提交。
     * 3. 登录成功拿到 jieqiUserInfo 后持久化所有 Cookie 与 UA，完成全套会话建立。
     * 4. 实时侦听 alert 弹窗与页面错误信息，密码/账号错误即时返回，杜绝 15 秒死等。
     */
    private suspend fun webViewLogin(user: String, pass: String): LoginResult =
        withTimeoutOrNull(WEBVIEW_LOCK_TIMEOUT_MS) {
            webViewMutex.withLock {
                withTimeoutOrNull(WEBVIEW_TIMEOUT_MS) {
                    val activity = getForegroundActivity()
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine { cont ->
                            val ctx = activity ?: appContext
                            val webView = runCatching { WebView(ctx) }.getOrNull()
                            if (webView == null) {
                                if (cont.isActive) cont.resume(LoginResult(false, "无法初始化浏览器引擎"))
                                return@suspendCancellableCoroutine
                            }
                            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                                runCatching {
                                    activity.addContentView(
                                        webView,
                                        ViewGroup.LayoutParams(1, 1)
                                    )
                                    webView.alpha = 0.99f
                                    webView.translationX = -5000f
                                    webView.isClickable = false
                                    webView.isFocusable = false
                                }
                            }
                            val host = runCatching { base.toHttpUrl().host }.getOrNull()
                            runCatching {
                                webView.layout(0, 0, 1080, 1920)
                                webView.settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                }
                                var detectedError: String? = null
                                webView.webChromeClient = object : WebChromeClient() {
                                    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                                        result?.confirm()
                                        if (!message.isNullOrBlank()) {
                                            val err = extractCredentialError(message) ?: message
                                            detectedError = err
                                        }
                                        return true
                                    }
                                }
                                val usedUa = systemDefaultUa
                                webView.settings.userAgentString = usedUa
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                                syncCookiesToWebView("$base/login.php")

                                var resumed = false
                                fun finishSuccess() {
                                    if (resumed) return
                                    resumed = true
                                    syncCookiesFromWebView(base)
                                    syncCookiesFromWebView("$base/login.php")
                                    if (host != null) recordChallengeUa(host, usedUa)
                                    destroyWebView(webView)
                                    if (cont.isActive) cont.resume(LoginResult(true, "登录成功"))
                                }

                                fun finishFailure(msg: String) {
                                    if (resumed) return
                                    resumed = true
                                    syncCookiesFromWebView(base)
                                    syncCookiesFromWebView("$base/login.php")
                                    if (host != null) recordChallengeUa(host, usedUa)
                                    destroyWebView(webView)
                                    if (cont.isActive) cont.resume(LoginResult(false, msg))
                                }

                                webView.webViewClient = object : WebViewClient() {
                                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                        val errorCode = error?.errorCode ?: 0
                                        if (errorCode == -3) return // net::ERR_ABORTED
                                        if (request != null && !request.isForMainFrame) return
                                    }
                                }

                                Log.d("Wenku8Debug", "webViewLogin: starting for $base/login.php, UA=$usedUa")
                                val pollScope = CoroutineScope(Dispatchers.Main)
                                val pollJob = pollScope.launch {
                                    val start = System.currentTimeMillis()
                                    var formSubmitted = false
                                    while (isActive && System.currentTimeMillis() - start < WEBVIEW_TIMEOUT_MS) {
                                        delay(500)
                                        if (resumed) break
                                        if (detectedError != null) {
                                            finishFailure(detectedError ?: "登录失败")
                                            break
                                        }
                                        syncCookiesFromWebView(base)
                                        syncCookiesFromWebView("$base/login.php")
                                        val wvCookies = runCatching { CookieManager.getInstance().getCookie("$base/login.php") }.getOrNull() ?: ""
                                        Log.d("Wenku8Debug", "webViewLogin poll: cookies=$wvCookies, formSubmitted=$formSubmitted")
                                        if (wvCookies.contains("cf_clearance") && host != null) {
                                            recordChallengeUa(host, usedUa)
                                        }
                                        if (wvCookies.contains("jieqiUserInfo")) {
                                            Log.d("Wenku8Debug", "webViewLogin: found jieqiUserInfo! SUCCESS!")
                                            finishSuccess()
                                            break
                                        }

                                        // 若当前登录表单可见且尚未提交，通过 JS 填充账号密码并提交表单
                                        if (!formSubmitted) {
                                            val js = """
                                                (function() {
                                                    try {
                                                        var u = document.querySelector('input[name="username"]') || 
                                                                document.querySelector('input[type="text"]') ||
                                                                document.querySelector('#username');
                                                        var p = document.querySelector('input[name="password"]') || 
                                                                document.querySelector('input[type="password"]') ||
                                                                document.querySelector('#password');
                                                        if (u && p) {
                                                            u.value = '${user.replace("'", "\\'")}';
                                                            p.value = '${pass.replace("'", "\\'")}';
                                                            u.dispatchEvent(new Event('input', { bubbles: true }));
                                                            u.dispatchEvent(new Event('change', { bubbles: true }));
                                                            p.dispatchEvent(new Event('input', { bubbles: true }));
                                                            p.dispatchEvent(new Event('change', { bubbles: true }));
                                                            var uc = document.querySelector('input[name="usecookie"]');
                                                            if (uc) uc.checked = true;
                                                            var submitBtn = document.querySelector('input[type="submit"]') || 
                                                                            document.querySelector('button[type="submit"]') ||
                                                                            document.querySelector('input[name="submit"]');
                                                            if (submitBtn) {
                                                                submitBtn.click();
                                                                return "clicked";
                                                            }
                                                            var form = u.form || document.querySelector('form');
                                                            if (form) {
                                                                if (typeof form.requestSubmit === 'function') {
                                                                    form.requestSubmit();
                                                                    return "request_submitted";
                                                                }
                                                                HTMLFormElement.prototype.submit.call(form);
                                                                return "proto_submitted";
                                                            }
                                                        }
                                                    } catch(e) {
                                                        return "err:" + e.message;
                                                    }
                                                    return "no_form";
                                                })()
                                            """.trimIndent()
                                            webView.evaluateJavascript(js) { res ->
                                                Log.e("Wenku8Debug", "webViewLogin JS result: $res")
                                                if (res != null && (res.contains("clicked") || res.contains("submitted"))) {
                                                    formSubmitted = true
                                                }
                                            }
                                        } else {
                                            // 表单提交后，快速检测页面是否渲染了错误提示
                                            val jsCheck = """
                                                (function() {
                                                    try {
                                                        var b = document.body ? document.body.innerText : '';
                                                        return b;
                                                    } catch(e) { return ''; }
                                                })()
                                            """.trimIndent()
                                            webView.evaluateJavascript(jsCheck) { pageText ->
                                                if (!pageText.isNullOrBlank()) {
                                                    val err = extractCredentialError(pageText)
                                                    if (err != null) {
                                                        detectedError = err
                                                    }
                                                }
                                            }
                                            if (detectedError != null) {
                                                finishFailure(detectedError ?: "用户名或密码错误")
                                                break
                                            }
                                        }
                                    }
                                    if (!resumed) {
                                        finishFailure(detectedError ?: "登录超时，请检查网络或账号密码")
                                    }
                                }

                                cont.invokeOnCancellation {
                                    pollJob.cancel()
                                    webView.post { destroyWebView(webView) }
                                }

                                webView.loadUrl("$base/login.php")
                            }.onFailure {
                                destroyWebView(webView)
                                if (cont.isActive) cont.resume(LoginResult(false, it.message ?: "登录异常"))
                            }
                        }
                    }
                } ?: LoginResult(false, "登录超时，请重试")
            }
        } ?: LoginResult(false, "登录排队超时，请重试")

    // 说明：这里**没有** logout()。本应用全程使用内置共享账号、不提供退出入口，
    // 原先的 logout() 在全仓已无任何调用点（清除会话统一走 clearCookies()），
    // 故一并移除，避免留下"可以退出登录"的误导性 API。

    // ------------------------------------------------------------------ //
    // read operations
    // ------------------------------------------------------------------ //
    suspend fun search(keyword: String, byAuthor: Boolean): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val type = if (byAuthor) "author" else "articlename"
            // 有界重试：站点在两次搜索间隔 < 5s 时返回错误页。
            // 原实现递归调用自身且无次数上限——站点持续返回错误页时会无界深递归，
            // 长时间挂起且协程取消无法从递归中恢复。这里改为定长循环。
            repeat(SEARCH_MAX_ATTEMPTS) { attempt ->
                pacer.paceSearch()
                val resp = runCatching {
                    postForm(
                        "$base/so.php",
                        listOf(
                            "searchtype" to type,
                            "searchkey" to keyword,
                            "charset" to "gbk",
                            "Submit" to "\u8F7B\u5C0F\u8BF4\u641C\u7D22",
                        )
                    )
                }.getOrNull()
                if (resp != null) {
                    val finalUrl = resp.request.url.toString()
                    val html = String(readBytes(resp), GB18030)
                    val throttled = html.contains(THROTTLE_MARK) || html.contains("出现错误")
                    if (!throttled && !isChallenge(html)) {
                        return@withContext parseSearchResult(finalUrl, html)
                    }
                }
                // 若无有效响应或遭 CF 拦截，触发一次主页绕过获取 cf_clearance
                if (attempt == 0) {
                    runCatching { fetchWithBypass({ "$it/index.php" }, { it }) }
                }
                if (attempt < SEARCH_MAX_ATTEMPTS - 1) delay(SEARCH_RETRY_DELAY_MS)
            }
            throw IOException("搜索被站点限流或人机验证阻断，请稍后重试")
        }

    /** 搜索响应解析：既可能是精确命中（302 直跳详情页），也可能是结果列表页。 */
    private fun parseSearchResult(finalUrl: String, html: String): List<SearchResult> {
        val m = BOOK_URL.find(finalUrl)
        if (m != null) {
            val id = m.groupValues[1].toIntOrNull() ?: return emptyList()
            val info = Parsers.parseBookInfo(html, id)
            // 单结果重定向：从详情页带上封面 URL（与列表页一致展示封面）
            return listOf(SearchResult(id, info.title, info.coverUrl))
        }
        return Parsers.parseSearchResults(html)
    }

    suspend fun bookInfo(id: Int): BookInfo = withContext(Dispatchers.IO) {
        // 同键并发只放行一个：排队者拿到锁时缓存已写好，不会再发一次请求
        singleFlight.run("info_$id") {
            infoCache.get("info_$id") ?: run {
                // 1. 快路径：多镜像自动轮询 + 本地磁盘缓存
                val web = tryDirect(
                    urlFor = { h -> "$h/book/$id.htm" },
                    parse = { html -> Parsers.parseBookInfo(html, id).takeIf { it.title.isNotBlank() } },
                    ttlMs = TTL_BOOK,
                    category = "book",
                )
                // 2. 直连遇 CF 拦截/无令牌：升级至三级绕过栈（Cronet -> 后台 WebView 解挑战 -> OkHttp）
                    ?: fetchWithBypass(
                        urlFor = { h -> "$h/book/$id.htm" },
                        parse = { html ->
                            Parsers.parseBookInfo(html, id).takeIf { it.title.isNotBlank() }?.also {
                                htmlCache.put("$base/book/$id.htm", html, "book")
                            }
                        },
                    )
                // 3. 官方 App API 兜底
                    ?: appApiBookInfo(id)
                    ?: throw IOException("书籍信息获取失败")
                infoCache.put("info_$id", web)
                web
            }
        }
    }

    /** Read one page of public book-review topics. Review pages are loaded only on demand. */
    suspend fun bookReviews(bookId: Int, page: Int = 1): BookReviewPage = withContext(Dispatchers.IO) {
        require(bookId > 0) { "书籍编号无效" }
        val safePage = page.coerceAtLeast(1)
        val parsed = tryDirect(
            urlFor = { host ->
                "$host/modules/article/reviews.php?aid=$bookId&type=all&page=$safePage"
            },
            parse = { html -> ReviewParsers.parseBookReviews(html, bookId, safePage) },
        ) ?: fetchWithBypass(
            urlFor = { host ->
                "$host/modules/article/reviews.php?aid=$bookId&type=all&page=$safePage"
            },
            parse = { html -> ReviewParsers.parseBookReviews(html, bookId, safePage) },
        )
        parsed ?: throw IOException("书评列表加载失败")
    }

    /** Read one page of a public review topic and its replies. */
    suspend fun reviewThread(reviewId: Int, page: Int = 1): ReviewThreadPage = withContext(Dispatchers.IO) {
        require(reviewId > 0) { "书评编号无效" }
        val safePage = page.coerceAtLeast(1)
        val parsed = tryDirect(
            urlFor = { host ->
                "$host/modules/article/reviewshow.php?rid=$reviewId&page=$safePage"
            },
            parse = { html -> ReviewParsers.parseReviewThread(html, reviewId, safePage) },
        ) ?: fetchWithBypass(
            urlFor = { host ->
                "$host/modules/article/reviewshow.php?rid=$reviewId&page=$safePage"
            },
            parse = { html -> ReviewParsers.parseReviewThread(html, reviewId, safePage) },
        )
        parsed ?: throw IOException("书评内容加载失败")
    }

    suspend fun chapters(bookId: Int, groupId: Int): List<Volume> = withContext(Dispatchers.IO) {
        singleFlight.run("toc_$bookId") {
            tocCache.get("toc_$bookId") ?: run {
                // 1. 快路径：多镜像自动轮询 + 本地磁盘缓存
                val web = tryDirect(
                    urlFor = { h -> "$h/novel/$groupId/$bookId/index.htm" },
                    parse = { html -> Parsers.parseChapterIndex(html).takeIf { it.isNotEmpty() } },
                    ttlMs = TTL_BOOK,
                    category = "book",
                )
                // 2. 直连遇 CF 拦截/无令牌：升级至三级绕过栈
                    ?: fetchWithBypass(
                        urlFor = { h -> "$h/novel/$groupId/$bookId/index.htm" },
                        parse = { html ->
                            Parsers.parseChapterIndex(html).takeIf { it.isNotEmpty() }?.also {
                                htmlCache.put("$base/novel/$groupId/$bookId/index.htm", html, "book")
                            }
                        },
                    )
                // 3. 官方 App API 兜底
                    ?: appApiVolumes(bookId)
                    ?: throw IOException("章节目录加载失败")
                tocCache.put("toc_$bookId", web)
                web
            }
        }
    }

    suspend fun chapterContent(gid: Int, bookId: Int, cid: String): ChapterContent =
        withContext(Dispatchers.IO) {
            // 按 bookId+cid 分键：同一章的并发请求合并，不同章节照旧并行
            singleFlight.run("chap_${bookId}_$cid") {
                chapterCache.get("chap_${bookId}_$cid") ?: run {
                    // 1. 快路径：多镜像自动轮询 + 本地磁盘缓存
                    val web = tryDirect(
                        urlFor = { h -> "$h/novel/$gid/$bookId/$cid.htm" },
                        parse = { html -> Parsers.parseChapter(html).takeIf { it.text.isNotBlank() || it.images.isNotEmpty() } },
                        ttlMs = TTL_CHAPTER,
                        category = "chapter",
                    )
                    // 2. 直连遇 CF 拦截/无令牌：升级至三级绕过栈
                        ?: fetchWithBypass(
                            urlFor = { h -> "$h/novel/$gid/$bookId/$cid.htm" },
                            parse = { html ->
                                Parsers.parseChapter(html).takeIf { it.text.isNotBlank() || it.images.isNotEmpty() }?.also {
                                    htmlCache.put("$base/novel/$gid/$bookId/$cid.htm", html, "chapter")
                                }
                            },
                        )
                    // 3. 官方 App API 兜底
                        ?: appApiChapter(bookId, cid)
                        ?: throw IOException("章节加载失败")
                    chapterCache.put("chap_${bookId}_$cid", web)
                    web
                }
            }
        }

    // ------------------------------------------------------------------ //
    // 官方 App API（免 CF，参考 LightNovelReader 的 Wenku8AppDataSource）
    // ------------------------------------------------------------------ //

    /** POST 官方 App API；官方地址失败后尝试社区中继。串行限流 + 随机延迟。 */
    private suspend fun appApiGet(request: String): String? = withContext(Dispatchers.IO) {
        appApiSemaphore.withPermit {
            for (host in listOf(APP_API_OFFICIAL, APP_API_RELAY)) {
                val text = runCatching {
                    var attempt = 0
                    while (true) {
                        val body = gbkForm(
                            listOf(
                                "request" to Base64.getEncoder()
                                    .encodeToString(request.toByteArray()),
                                "timetoken" to System.currentTimeMillis().toString(),
                                "appver" to APP_VER,
                            )
                        )
                        val req = Request.Builder()
                            .url(host)
                            .post(body)
                            .header("User-Agent", APP_UA)
                            .header("Accept", "*/*")
                            .build()
                        val resp = execute(req, retries = 1)
                        val text = readBytes(resp).toString(GB18030)
                        if (text.isNotBlank()) return@runCatching text
                        if (attempt >= 2) break
                        attempt++
                        delay(2500L * attempt)
                    }
                    ""
                }.getOrNull()
                if (!text.isNullOrBlank()) {
                    // App API 官方行为：请求间随机 1.5~2s 延迟，避免被限流
                    delay(Random.nextLong(1500, 2001))
                    return@withPermit text
                }
            }
            null
        }
    }

    private suspend fun appApiBookInfo(id: Int): BookInfo? {
        val meta = appApiGet("action=book&do=meta&aid=$id&t=0") ?: return null
        val info = AppParsers.parseAppBookInfo(meta, id) ?: return null
        // 简介在 do=intro 接口：响应正文即简介纯文本
        val intro = appApiGet("action=book&do=intro&aid=$id&t=0")
        val description = intro?.let { html ->
            html.substringAfter("<body>", html).substringBefore("</body>")
                .replace(Regex("<[^>]+>"), "").trim()
        }?.takeIf { it.isNotBlank() } ?: ""
        return info.copy(description = description)
    }

    private suspend fun appApiVolumes(bookId: Int): List<Volume>? {
        val list = appApiGet("action=book&do=list&aid=$bookId&t=0") ?: return null
        return AppParsers.parseAppVolumes(list)
    }

    private suspend fun appApiChapter(bookId: Int, cid: String): ChapterContent? {
        val text = appApiGet("action=book&do=text&aid=$bookId&cid=$cid&t=0") ?: return null
        return AppParsers.parseAppChapter(text)
    }

    suspend fun bookcase(): List<BookcaseItem> = withContext(Dispatchers.IO) {
        val allItems = mutableListOf<BookcaseItem>()
        val seenAids = mutableSetOf<Int>()

        // 支持文库8云端书架多页抓取（?page=1..10）
        for (page in 1..10) {
            val path = if (page == 1) "modules/article/bookcase.php" else "modules/article/bookcase.php?page=$page"
            val html = runCatchingNotCancelling {
                getHtml("$base/$path")
            }.getOrNull()?.takeIf { !it.contains(LOGIN_PATH) && !it.contains("Just a moment...") }
                ?: fetchWithBypass(
                    urlFor = { h -> "$h/$path" },
                    parse = { it.takeIf { text -> !text.contains("Just a moment...") && !text.contains(LOGIN_PATH) } }
                )

            if (html.isNullOrBlank()) break

            val pageItems = Parsers.parseBookcase(html)
            if (pageItems.isEmpty()) break

            var addedOnThisPage = 0
            for (item in pageItems) {
                if (item.aid > 0 && seenAids.add(item.aid)) {
                    allItems.add(item)
                    addedOnThisPage++
                }
            }
            // 若整页均为重复项，或者该页书籍数量较少（如少于 10 本），或不包含下一页链接，停止继续拉取
            val hasNextPage = html.contains("page=${page + 1}") || html.contains("page=" + (page + 1))
            if (addedOnThisPage == 0 || !hasNextPage || pageItems.size < 10) {
                break
            }
        }
        allItems
    }

    /**
     * 首页栏目。快路径：默认 UA 直连（常规情况毫秒级返回）；
     * 若直连失败或返回疑似 CF 挑战页（解析为空），自动升级到
     * WebView → Cronet → OkHttp 随机 UA 的三级 Cloudflare 绕过栈。
     */
    suspend fun homepage(): List<HomeSection> = withContext(Dispatchers.IO) {
        // 首页需要有效登录态（未登录访问 index.php 会 302 重定向到 login.php 导致解析为空）
        if (!isLoggedIn()) {
            ensureLoggedIn()
        }
        tryDirect(
            urlFor = { h -> "$h/index.php" },
            parse = { html -> Parsers.parseHomepage(html).takeIf { it.isNotEmpty() } },
            ttlMs = TTL_HOME,
            category = "home",
        )?.let {
            Log.d("Wenku8Debug", "homepage: tryDirect succeeded with ${it.size} sections")
            return@withContext it
        }

        Log.d("Wenku8Debug", "homepage: tryDirect failed, entering fetchWithBypass")
        val sections = fetchWithBypass(
            urlFor = { h -> "$h/index.php" },
            parse = { html -> Parsers.parseHomepage(html).takeIf { it.isNotEmpty() } },
        )
        if (sections != null && sections.isNotEmpty()) {
            Log.d("Wenku8Debug", "homepage: fetchWithBypass result=${sections.size}")
            return@withContext sections
        }

        // 兜底方案：从热门分类书单聚合成首页栏目展示
        val fallback = buildFallbackHomeSections()
        if (fallback.isNotEmpty()) {
            Log.d("Wenku8Debug", "homepage: buildFallbackHomeSections result=${fallback.size}")
            return@withContext fallback
        }

        throw IOException("首页获取失败：直连与三级绕过均未命中")
    }

    private suspend fun buildFallbackHomeSections(): List<HomeSection> = withContext(Dispatchers.IO) {
        val fallbackTags = listOf("轻小说", "热血", "恋爱", "奇幻", "冒险")
        val sections = mutableListOf<HomeSection>()
        for (tag in fallbackTags) {
            val books = runCatching { tagBooks(tag, page = 1) }.getOrNull()
            if (!books.isNullOrEmpty()) {
                sections.add(HomeSection(title = tag, books = books.take(8)))
            }
        }
        sections
    }

    /**
     * 分类清单：直接返回内置标准标签（秒回，无需登录/网络）。
     * 书籍仍按分类在线抓取，故此处先确保登录（供后续 tagBooks 使用）。
     */
    suspend fun tags(): List<String> = withContext(Dispatchers.IO) {
        ensureLoggedIn()
        BUILT_IN_TAGS
    }

    /** Books under a tag. 需登录；快路径 → WebView → Cronet → OkHttp mirrors。分页：page>=2 追加 &page=N。 */
    suspend fun tagBooks(tag: String, page: Int = 1): List<HomeBook> = withContext(Dispatchers.IO) {
        ensureLoggedIn()
        val query = URLEncoder.encode(tag, "GBK")
        val pageParam = if (page > 1) "&page=$page" else ""
        tryDirect(
            urlFor = { h -> "$h/modules/article/tags.php?t=$query&v=1$pageParam" },
            parse = { html -> Parsers.parseBookList(html).takeIf { it.isNotEmpty() } },
            ttlMs = TTL_TAG_BOOKS,
            category = "tag",
        ) ?: fetchWithBypass(
            urlFor = { h -> "$h/modules/article/tags.php?t=$query&v=1$pageParam" },
            parse = { html -> Parsers.parseBookList(html).takeIf { it.isNotEmpty() } },
        ) ?: emptyList()
    }

    private fun isChallenge(html: String): Boolean {
        if (html.contains("wenku8") || html.contains("轻小说文库")) return false
        return html.contains("Just a moment", ignoreCase = true) ||
            html.contains("window._cf_chl_opt") ||
            html.contains("challenge-error-text") ||
            html.contains("cf-challenge")
    }

    /**
     * `runCatching` 的同义封装：**不吞协程取消**。
     *
     * 标准 `runCatching` 捕获 `Throwable`，会把 `CancellationException` 一并吃掉：
     * 页面被取消时会被误判成"直连失败"，随后继续走 WebView/Cronet 绕过栈，
     * 协程既无法及时取消、还会多打一串无谓请求。
     */
    private inline fun <T> runCatchingNotCancelling(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    /**
     * 快路径（参考 LightNovelReader 的 cookie-first 思路）：直接用 OkHttp 携带
     * 持久化的 cf_clearance 与绑定 UA 直连各镜像；已有有效令牌时一次通过，无需跑 WebView。
     * [ttlMs] > 0 时启用磁盘缓存（见 getHtmlCached），避免二次加载。
     * 返回 null 表示全部镜像直连均无有效内容（此时才升级到三级绕过栈）。
     */
    private suspend fun <T> tryDirect(
        urlFor: (String) -> String,
        parse: (String) -> T?,
        ttlMs: Long = 0L,
        category: String = "other",
    ): T? {
        for (h in mirrors) {
            val ua = uaFor(urlFor(h))
            val parsed = runCatchingNotCancelling {
                parse(getHtmlCached(urlFor(h), ttlMs, category, retries = 1, ua = ua))
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    /**
     * Cloudflare 三级绕过抓取（与 tags/tagBooks 同栈）：
     * WebView（真浏览器跑 CF JS 挑战，解出后持久化 cf_clearance）→ Cronet（TLS 指纹）
     * → OkHttp 随机 Android UA，逐镜像尝试。[parse] 返回 null 表示该响应无有效内容。
     * 返回 null 表示全部失败。
     *
     * 注：原先还有一个 `steps: MutableList<String>` 诊断参数，用于收集各层失败原因，
     * 但从未有任何调用方传入（整条诊断链路是半成品），已移除以免误导。
     */
    private suspend fun <T> fetchWithBypass(
        urlFor: (String) -> String,
        parse: (String) -> T?,
    ): T? {
        val engine = cronetEngine
        if (engine != null) {
            for (h in mirrors) {
                val html = cronetGet(engine, urlFor(h)) ?: continue
                parse(html)?.let { return it }
            }
        }
        for (h in mirrors) {
            val html = webViewGet(urlFor(h)) ?: continue
            parse(html)?.let { return it }
        }
        for (h in mirrors) {
            // 若该主机已有 cf_clearance（WebView 解出后持久化），复用其绑定 UA 直接通过
            val ua = uaFor(urlFor(h))
            val parsed = runCatchingNotCancelling {
                parse(getHtml(urlFor(h), retries = 1, ua = ua))
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    /**
     * GET via Cronet, carrying the app's session cookies; returns null on failure/timeout.
     *
     * 用挂起等待替代 `CountDownLatch.await(8s)`：原先每个 Cronet 请求都要独占一个
     * IO 线程空等最多 8 秒（并发几个请求就白白占住几个线程），而且协程被取消时
     * 线程仍会等到超时才释放。改成 `suspendCancellableCoroutine` 后：
     * 线程在等待期间被释放、取消能立刻传播到 `UrlRequest.cancel()`。
     */
    private suspend fun cronetGet(engine: CronetEngine, url: String): String? =
        withTimeoutOrNull(CRONET_TIMEOUT_SECONDS * 1000) {
            val readBuffer = ByteBuffer.allocateDirect(64 * 1024)
            val body = ByteArrayOutputStream()
            val httpUrl = url.toHttpUrl()
            val cookieHeader = cookieStore.loadForRequest(httpUrl)
                .joinToString("; ") { "${it.name}=${it.value}" }

            suspendCancellableCoroutine { cont ->
                val callback = object : UrlRequest.Callback() {
                override fun onRedirectReceived(
                    request: UrlRequest,
                    info: UrlResponseInfo,
                    newLocationUrl: String?,
                ) {
                    request.followRedirect()
                }

                override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                    runCatching {
                        info.allHeadersAsList
                            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                            .mapNotNull { (_, v) ->
                                runCatching { Cookie.parse(httpUrl, v) }.getOrNull()
                            }
                            .takeIf { it.isNotEmpty() }
                            ?.let { cookieStore.saveFromResponse(httpUrl, it) }
                    }
                    request.read(readBuffer)
                }

                override fun onReadCompleted(
                    request: UrlRequest,
                    info: UrlResponseInfo,
                    byteBuffer: ByteBuffer,
                ) {
                    byteBuffer.flip()
                    val arr = ByteArray(byteBuffer.remaining())
                    byteBuffer.get(arr)
                    body.write(arr)
                    byteBuffer.clear()
                    request.read(byteBuffer)
                }

                override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                    if (cont.isActive) cont.resume(String(body.toByteArray(), GB18030))
                }

                override fun onFailed(
                    request: UrlRequest,
                    info: UrlResponseInfo?,
                    error: CronetException,
                ) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                    if (cont.isActive) cont.resume(null)
                }
            }

            val builder = engine.newUrlRequestBuilder(url, callback, cronetExecutor)
                .setHttpMethod("GET")
                .addHeader("User-Agent", uaFor(url))
                .addHeader(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                )
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .addHeader("Cache-Control", "max-age=0")
                .addHeader("Upgrade-Insecure-Requests", "1")
                .addHeader("Sec-Fetch-Dest", "document")
                .addHeader("Sec-Fetch-Mode", "navigate")
                .addHeader("Sec-Fetch-Site", "none")
                .addHeader("Sec-Fetch-User", "?1")
            refererFor(url)?.let { builder.addHeader("Referer", it) }
            if (cookieHeader.isNotEmpty()) builder.addHeader("Cookie", cookieHeader)
            val request = builder.build()
            // 取消（页面离开 / 超时）时立刻取消底层请求，回调再触发 onCanceled 也不影响已取消的协程
            cont.invokeOnCancellation { runCatching { request.cancel() } }
            request.start()
            }
        }

    /**
     * Loads the page in a hidden WebView so Cloudflare's JS challenge runs like in a
     * real browser, then reads back the rendered DOM. Carries the app's session cookies.
     *
     * 挑战通过后会做两件事（参考 LightNovelReader 的 cf_clearance 思路）：
     * 1. 记录本次使用的 UA（cf_clearance 与该 UA 绑定，后续复用需一致）；
     * 2. 把 WebView 写入的 Cookie（含 cf_clearance / __cf_bm）持久化到 CookieStore，
     *    之后 OkHttp/Cronet 直接带令牌请求，无需每次重跑 WebView。
     */
    private suspend fun webViewGet(url: String): String? =
        // 串行化：同一时刻只有一个隐藏 WebView 在解挑战（见 webViewMutex 说明）。
        // 外层超时覆盖"排队 + 执行"，避免排在后面的请求无限期等待。
        withTimeoutOrNull(WEBVIEW_LOCK_TIMEOUT_MS) {
            webViewMutex.withLock {
                withTimeoutOrNull(WEBVIEW_TIMEOUT_MS) {
                    val activity = getForegroundActivity()
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine { cont ->
                            val ctx = activity ?: appContext
                            val webView = runCatching { WebView(ctx) }.getOrNull()
                            if (webView == null) {
                                if (cont.isActive) cont.resume(null)
                                return@suspendCancellableCoroutine
                            }
                            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                                runCatching {
                                    activity.addContentView(
                                        webView,
                                        ViewGroup.LayoutParams(1, 1)
                                    )
                                    webView.alpha = 0.99f
                                    webView.translationX = -5000f
                                    webView.isClickable = false
                                    webView.isFocusable = false
                                }
                            }
                            cont.invokeOnCancellation {
                                webView.post { destroyWebView(webView) }
                            }
                            val host = runCatching { url.toHttpUrl().host }.getOrNull()
                            runCatching {
                                webView.layout(0, 0, 1080, 1920)
                                webView.settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                }
                                webView.webChromeClient = WebChromeClient()
                                val usedUa = systemDefaultUa
                                webView.settings.userAgentString = usedUa
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                                syncCookiesToWebView(url)

                                var resumed = false
                                fun tryFinishWithHtml(html: String) {
                                    if (resumed) return
                                    resumed = true
                                    syncCookiesFromWebView(url)
                                    if (host != null) recordChallengeUa(host, usedUa)
                                    destroyWebView(webView)
                                    if (cont.isActive) cont.resume(html)
                                }

                                webView.webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                        syncCookiesFromWebView(url)
                                        view?.evaluateJavascript("document.documentElement.outerHTML") { htmlJson ->
                                            val decoded = runCatching { JSONTokener(htmlJson).nextValue() as String }.getOrNull()
                                            if (decoded != null && !isChallenge(decoded) && !decoded.contains("Just a moment") && decoded.length > 500) {
                                                tryFinishWithHtml(decoded)
                                            }
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?,
                                    ) {
                                        val errorCode = error?.errorCode ?: 0
                                        if (errorCode == -3) return // net::ERR_ABORTED
                                        if (request != null && !request.isForMainFrame) return
                                    }
                                }

                                Log.d("Wenku8Debug", "webViewGet: starting for $url, UA=$usedUa")
                                val pollScope = CoroutineScope(Dispatchers.Main)
                                val pollJob = pollScope.launch {
                                    val start = System.currentTimeMillis()
                                    while (isActive && System.currentTimeMillis() - start < WEBVIEW_TIMEOUT_MS) {
                                        delay(800)
                                        if (resumed) break
                                        syncCookiesFromWebView(url)
                                        if (host != null) recordChallengeUa(host, usedUa)
                                        webView.evaluateJavascript("document.documentElement.outerHTML") { htmlJson ->
                                            if (resumed) return@evaluateJavascript
                                            val decoded = runCatching { JSONTokener(htmlJson).nextValue() as String }.getOrNull()
                                            if (decoded != null && !isChallenge(decoded) && !decoded.contains("Just a moment") && decoded.length > 500) {
                                                Log.d("Wenku8Debug", "webViewGet: DOM ready, finishing with HTML! len=${decoded.length}")
                                                tryFinishWithHtml(decoded)
                                            }
                                        }
                                    }
                                }

                                cont.invokeOnCancellation {
                                    pollJob.cancel()
                                    webView.post { destroyWebView(webView) }
                                }

                                webView.loadUrl(url)
                            }.onFailure {
                                destroyWebView(webView)
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    }
                }
            }
        }

    /**
     * 隐藏 WebView 的统一销毁路径。
     *
     * 直接 `destroy()` 会留下未停止的加载与回调引用；按官方建议先停加载、清历史，
     * 再摘掉 WebViewClient，最后销毁。每一步都 runCatching——任一步失败都不应
     * 阻止后续清理（否则就成了"清理过程中崩溃"）。
     */
    private fun destroyWebView(webView: WebView) {
        runCatching {
            val parent = webView.parent as? ViewGroup
            parent?.removeView(webView)
        }
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.clearHistory() }
        runCatching { webView.removeAllViews() }
        runCatching { webView.webViewClient = WebViewClient() }
        runCatching { webView.destroy() }
    }

    /** type: "txt"(GBK) | "utf8" | "big5" */
    suspend fun downloadFullTxt(id: Int, type: String): ByteArray =
        getBytes("$DL/down.php?type=$type&node=1&id=$id")

    /**
     * 抓取图片原始字节（阅读器插图的「保存图片」用）。
     *
     * 与 [getBytes] 的唯一差别是 Referer：图片走站点防盗链，必须带
     * [Wenku8Hosts.IMAGE_REFERER]，用同域根地址会被拒绝。地址同样统一升级为 HTTPS
     * （站点页面给的是 http，明文流量已被 networkSecurityConfig 禁止）。
     */
    suspend fun imageBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(Wenku8Hosts.normalizeImageUrl(url))
            .browserHeaders(Wenku8Hosts.IMAGE_REFERER, UA)
            .build()
        readBytes(execute(req))
    }

}
