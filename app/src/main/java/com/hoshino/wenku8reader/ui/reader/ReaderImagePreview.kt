package com.hoshino.wenku8reader.ui.reader

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import com.hoshino.wenku8reader.R
import com.hoshino.wenku8reader.Wenku8Application
import com.hoshino.wenku8reader.data.FileSaver
import com.hoshino.wenku8reader.data.Wenku8Hosts
import com.hoshino.wenku8reader.di.AppContainer
import com.hoshino.wenku8reader.ui.theme.isMiuixStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.window.WindowDialog

/** 预览的最大放大倍数：支持超清插图高达 5 倍的平滑切片缩放 */
private const val PREVIEW_MAX_SCALE = 5f

/**
 * 插图全屏预览（基于 Telephoto 高性能分块切片缩放组件）。
 *
 * 核心特性：
 * 1. **超清切片/分块加载**：集成 Telephoto ZoomableAsyncImage，即使 4K/8K 巨幅跨页插图也以 Tile 切片按需解码，零 OOM 风险；
 * 2. **手势与流体物理**：原生支持平滑的双指缩放、惯性拖拽平移、双击智能放大/还原与边缘弹性回弹；
 * 3. **极简 HUD**：顶部提供半透明沉浸操作胶囊（关闭与一键保存），同时支持单击任意处退出与长按图片呼出保存对话框；
 * 4. **物理返回拦截**：响应系统返回手势关闭预览，避免直接退出阅读页；
 * 5. **原图无损保存**：优先复用 Coil 磁盘缓存原文，保证原画质落盘。
 */
@Composable
fun ReaderImagePreview(
    url: String,
    displayName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(Wenku8Hosts.normalizeImageUrl(url))
            .setHeader("Referer", Wenku8Hosts.IMAGE_REFERER)
            .crossfade(true)
            .build()
    }

    var showSaveDialog by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // 拦截返回手势
    BackHandler(onBack = onDismiss)

    val zoomableState = rememberZoomableState(
        zoomSpec = ZoomSpec(maxZoomFactor = PREVIEW_MAX_SCALE)
    )
    val imageState = rememberZoomableImageState(zoomableState)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
    ) {
        // Telephoto 核心全屏交互图片组件
        ZoomableAsyncImage(
            model = request,
            contentDescription = stringResource(R.string.reader_illustration),
            state = imageState,
            modifier = Modifier.fillMaxSize(),
            onClick = { onDismiss() },
            onLongClick = { showSaveDialog = true }
        )

        // 顶部浮动快捷操作栏（半透明胶囊）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onDismiss,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_cancel)
                )
            }

            IconButton(
                onClick = { showSaveDialog = true },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = stringResource(R.string.reader_image_save)
                )
            }
        }
    }

    if (showSaveDialog) {
        SaveImageDialog(
            saving = saving,
            onDismiss = { if (!saving) showSaveDialog = false },
            onConfirm = {
                saving = true
                scope.launch {
                    // 取**原始字节**而不是复用预览已解码的 Bitmap：位图重新编码（JPEG 有损）
                    // 会改变文件内容，"保存图片"必须与站点原图逐字节一致。
                    // 优先读 Coil 磁盘缓存里那份响应体原文（内容与原图相同，省一次网络往返），
                    // 没命中才重新下载——行为与改动前一致。
                    val bytes = withContext(Dispatchers.IO) { illustrationOriginalBytes(context, url) }
                        ?: runCatching { context.appContainer.client.imageBytes(url) }.getOrNull()
                    val saved = bytes?.let {
                        FileSaver.saveDownload(
                            context = context,
                            displayName = "$displayName.${extensionOf(url)}",
                            mime = mimeOf(url),
                            bytes = it,
                        )
                    }
                    val message = if (saved != null) {
                        context.getString(R.string.reader_image_saved, saved)
                    } else {
                        context.getString(R.string.reader_image_save_failed)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    saving = false
                    showSaveDialog = false
                }
            },
        )
    }
}

/**
 * 保存确认框：按界面风格二选一（MIUIX 用 miuix `WindowDialog`，Material 用 M3 `AlertDialog`），
 * 与阅读器其他面板保持同一套分派口径。
 */
@Composable
private fun SaveImageDialog(
    saving: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(R.string.reader_image_save_title)
    val message = stringResource(R.string.reader_image_save_message)
    val confirmText = stringResource(R.string.reader_image_save)
    val cancelText = stringResource(R.string.action_cancel)

    if (isMiuixStyle()) {
        WindowDialog(
            show = true,
            title = title,
            summary = message,
            onDismissRequest = onDismiss,
        ) {
            Column(Modifier.padding(top = 12.dp)) {
                Button(
                    onClick = onConfirm,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { MiuixText(confirmText) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onDismiss,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { MiuixText(cancelText) }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onConfirm, enabled = !saving) { Text(confirmText) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, enabled = !saving) { Text(cancelText) }
            },
        )
    }
}

/**
 * 取插图的**原始字节**：优先读 Coil 磁盘缓存里那份响应体原文，未命中返回 null（由调用方走网络）。
 *
 * 为什么缓存里的字节就等于原图：Coil 2 的磁盘缓存 key 是请求 data 的字符串
 * （`HttpUriFetcher.diskCacheKey = options.diskCacheKey ?: url`），而缓存文件是在**解码之前**
 * 写入的响应体原文——所以拿到的字节与重新下载得到的完全一致，只是省掉一次网络往返。
 *
 * key 必须与两个 ImageRequest 的 data 保持一致：本文件与 `ReaderIllustration` 都用
 * `Wenku8Hosts.normalizeImageUrl(url)`，这里也必须用同一个值，否则查找会**静默失效**
 * （退化成重新下载，不报任何错，只是白跑一趟网络）。
 *
 * 未命中的情形都属正常：图还没被加载过、用户清过图片缓存、或响应带 `Cache-Control: no-store`
 * ——Coil 默认尊重缓存头（`ImageLoaderOptions.respectCacheHeaders = true`），那种响应根本不落盘。
 */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
private fun illustrationOriginalBytes(context: Context, url: String): ByteArray? {
    val snapshot = context.imageLoader.diskCache?.openSnapshot(Wenku8Hosts.normalizeImageUrl(url))
        ?: return null
    // Snapshot.data 就是缓存文件（okio Path）；必须在 close 之前把内容读完。
    return snapshot.use { snap ->
        runCatching { snap.data.toFile().readBytes() }.getOrNull()
    }
}

/** 从图片 URL 取扩展名（认不出就按 jpg 处理——站点插图绝大多数是 jpg）。 */
private fun extensionOf(url: String): String {
    val name = url.substringBefore('?').substringAfterLast('/')
    val ext = name.substringAfterLast('.', "").lowercase()
    return if (ext in setOf("jpg", "jpeg", "png", "webp", "gif")) ext else "jpg"
}

/** 与 [extensionOf] 对应的 MIME。 */
private fun mimeOf(url: String): String = when (extensionOf(url)) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "image/jpeg"
}

private val Context.appContainer: AppContainer
    get() = (applicationContext as Wenku8Application).container
