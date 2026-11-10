// 提供图片点击拖动取色、像素放大查看和可复制的颜色值
package io.kaffubox.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.semantics.*
import io.kaffubox.core.*
import kotlinx.coroutines.*
import java.util.Locale

@Composable
fun ColorPickerPage(controller: AppController, tool: UiTool) {
    val form = controller.form(tool)
    val ref = form.files["file"]?.firstOrNull()
    var raster by remember(ref?.id) { mutableStateOf<RasterImage?>(null) }
    var bitmap by remember(ref?.id) { mutableStateOf<ImageBitmap?>(null) }
    var selected by remember(ref?.id) { mutableStateOf<IntOffset?>(null) }
    var error by remember(ref?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(ref?.id) {
        if (ref == null) return@LaunchedEffect
        try { val loaded = controller.readImage(ref); bitmap = withContext(Dispatchers.Default) { loaded.previewBitmap(1800) }; raster = loaded }
        catch (_: CancellationException) { } catch (failure: Exception) { error = UiText.failure(failure) }
    }
    Panel(Modifier.fillMaxWidth()) {
        FileField(controller, tool, tool.fields.first { it.key == "file" }, form, showPreview = false)
        InlineError(error)
        val image = raster; val display = bitmap
        if (ref != null && image == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (image != null && display != null) {
            Text("点击图片选择颜色，按住鼠标拖动可连续取色", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ImageCanvas(display, Modifier.fillMaxWidth().height(330.dp)
                .semantics { contentDescription = "图片取色预览" }
                .pointerHoverIcon(PointerIcon.Crosshair)
                .pointerInput(image) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val rect = imageDisplayRect(image.width, image.height, size.width.toFloat(), size.height.toFloat())
                        val first = imagePixelAt(down.position, rect, image.width, image.height)
                        if (first != null) {
                            selected = first; down.consume()
                            drag(down.id) { change -> imagePixelAt(change.position, rect, image.width, image.height)?.let { selected = it }; change.consume() }
                        }
                    }
                }, selected, IntSize(image.width, image.height))
            Text(image.width.toString() + " × " + image.height + " px · 取色以原图像素为准", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            selected?.let { point ->
                val pixel = image.pixels[point.y * image.width + point.x]
                val red = pixel ushr 16 and 255; val green = pixel ushr 8 and 255; val blue = pixel and 255; val alpha = pixel ushr 24
                val hex = "#%02X%02X%02X".format(red, green, blue)
                val rgb = "rgb($red, $green, $blue)"
                val rgba = "rgba($red, $green, $blue, " + "%.3f".format(Locale.ROOT, alpha / 255.0).trimEnd('0').trimEnd('.') + ")"
                BoxWithConstraints {
                val magnifierSize = if (maxWidth < 560.dp) 72.dp else 121.dp
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PixelMagnifier(image, point, Modifier.size(magnifierSize))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(32.dp).background(Color(pixel)))
                            SelectionContainer { Text(hex, style = MaterialTheme.typography.headlineSmall) }
                        }
                        Text("不透明度 " + (alpha * 100 / 255) + "%", style = MaterialTheme.typography.bodySmall)
                        SelectionContainer { Text(rgb + "\n" + rgba) }
                        Text("像素位置  X " + point.x + " · Y " + point.y, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { controller.copy(hex) }) { Text("复制 HEX") }
                            TextButton(onClick = { controller.copy(rgb) }) { Text("复制 RGB") }
                            TextButton(onClick = { controller.copy(rgba) }) { Text("复制 RGBA") }
                        }
                    }
                }
                }
            } ?: Text("在图片上选一点，查看颜色与像素细节", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PixelMagnifier(image: RasterImage, selected: IntOffset, modifier: Modifier) {
    Canvas(modifier) {
        val cell = size.width / 11
        for (row in 0..10) for (column in 0..10) {
            val x = (selected.x + column - 5).coerceIn(0, image.width - 1); val y = (selected.y + row - 5).coerceIn(0, image.height - 1)
            drawRect(if ((row + column) % 2 == 0) Color.LightGray else Color.White, Offset(column * cell, row * cell), Size(cell, cell))
            drawRect(Color(image.pixels[y * image.width + x]), Offset(column * cell, row * cell), Size(cell, cell))
        }
        drawRect(Color.Black, Offset(5 * cell, 5 * cell), Size(cell, cell), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        drawRect(Color.White, Offset(5 * cell, 5 * cell), Size(cell, cell), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
    }
}
