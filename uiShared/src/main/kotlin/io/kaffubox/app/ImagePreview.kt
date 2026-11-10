// 加载按照片方向显示的图片预览，并将预览中的位置映射到原图像素
package io.kaffubox.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*
import io.kaffubox.core.*
import kotlinx.coroutines.*
import kotlin.math.*

fun FileRef.looksLikeImage(): Boolean = mimeType?.startsWith("image/") == true || name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "tif", "tiff")

fun imageDisplayRect(imageWidth: Int, imageHeight: Int, width: Float, height: Float): Rect {
    val scale = min(width / imageWidth, height / imageHeight)
    val w = imageWidth * scale; val h = imageHeight * scale
    return Rect((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
}
fun imagePixelAt(position: Offset, rect: Rect, width: Int, height: Int): IntOffset? {
    if (!rect.contains(position) || rect.width <= 0 || rect.height <= 0) return null
    return IntOffset(((position.x - rect.left) * width / rect.width).toInt().coerceIn(0, width - 1), ((position.y - rect.top) * height / rect.height).toInt().coerceIn(0, height - 1))
}

@Composable
fun ImageCanvas(bitmap: ImageBitmap, modifier: Modifier, selection: IntOffset? = null, originalSize: IntSize = IntSize(bitmap.width, bitmap.height)) {
    Canvas(modifier) {
        val tile = 12.dp.toPx()
        for (y in 0..(size.height / tile).toInt()) for (x in 0..(size.width / tile).toInt()) {
            drawRect(if ((x + y) % 2 == 0) Color(0xFFE2E4E7) else Color(0xFFBFC4CB), Offset(x * tile, y * tile), Size(tile, tile))
        }
        val rect = imageDisplayRect(originalSize.width, originalSize.height, size.width, size.height)
        drawImage(bitmap, dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()), dstSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)))
        selection?.let {
            val point = Offset(rect.left + (it.x + 0.5f) * rect.width / originalSize.width, rect.top + (it.y + 0.5f) * rect.height / originalSize.height)
            for ((color, stroke) in listOf(Color.Black to 4f, Color.White to 2f)) {
                drawCircle(color, 7.dp.toPx(), point, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawLine(color, point - Offset(13.dp.toPx(), 0f), point + Offset(13.dp.toPx(), 0f), stroke)
                drawLine(color, point - Offset(0f, 13.dp.toPx()), point + Offset(0f, 13.dp.toPx()), stroke)
            }
        }
    }
}

@Composable
fun ImageFilePreview(controller: AppController, ref: FileRef, height: Dp = 220.dp) {
    var bitmap by remember(ref.id) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(ref.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(ref.id) {
        try { bitmap = withContext(Dispatchers.Default) { controller.readImage(ref).previewBitmap() } }
        catch (_: CancellationException) { } catch (failure: Exception) { error = UiText.failure(failure) }
    }
    if (bitmap != null) {
        ImageCanvas(bitmap!!, Modifier.fillMaxWidth().height(height))
        if (ref.name.endsWith(".gif", ignoreCase = true)) Text("这里只预览动图首帧，打开文件可查看播放效果", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    else if (error != null) InlineError("无法预览图片：" + error)
    else LinearProgressIndicator(Modifier.fillMaxWidth())
}
