// 提供手机滚动布局、图片转换、二维码绘制和视频帧预览适配
package io.kaffubox.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import io.kaffubox.core.*
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.math.*

@Composable
fun ScrollPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp), content = content)
}

@Composable
fun FileDropArea(onFiles: (List<Path>) -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) { Box(modifier, content = content) }

fun RasterImage.previewBitmap(maxSide: Int = 1200): ImageBitmap {
    val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    val scale = min(1.0, maxSide.toDouble() / max(width, height))
    val preview = if (scale == 1.0) bitmap else Bitmap.createScaledBitmap(bitmap, max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()), true).also { bitmap.recycle() }
    return preview.asImageBitmap()
}

@Composable
fun SvgPreview(svg: String, modifier: Modifier) {
    val bitmap = remember(svg) {
        val document = SVG.getFromString(svg).apply { documentWidth = 512f; documentHeight = 512f }
        val image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        document.renderToCanvas(Canvas(image)); image.asImageBitmap()
    }
    Image(bitmap, "生成的二维码", modifier)
}

suspend fun videoPreview(controller: AppController, ref: FileRef, seconds: Double): ImageBitmap = controller.media.frame(ref.id, seconds, 960).asImageBitmap()
