// 从 Windows 剪贴板读取二维码图片并复用本地识码器，不访问识别出的地址
package io.kaffubox.app

import androidx.compose.material3.*
import androidx.compose.runtime.*
import io.kaffubox.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage

@Composable
fun TransferScanCapture(controller: AppController, onCode: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Button(onClick = { scope.launch {
        busy = true
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) { controller.message = "剪贴板里没有图片，请先复制二维码图片或截图"; return@launch }
            val source = clipboard.getData(DataFlavor.imageFlavor) as java.awt.Image
            val width = source.getWidth(null); val height = source.getHeight(null)
            requireArgument(width > 0 && height > 0 && width.toLong() * height <= 20_000_000, "pixelCount")
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            try { graphics.color = java.awt.Color.WHITE; graphics.fillRect(0, 0, width, height); graphics.drawImage(source, 0, 0, null) } finally { graphics.dispose() }
            val result = try { withContext(Dispatchers.Default) { CodeReader.read(RasterImage(width, height, image.getRGB(0, 0, width, height, null, 0, width))) } } finally { image.flush() }
            onCode(result.getValue("text").jsonPrimitive.content)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { controller.report(failure, "识别剪贴板图片失败") }
        finally { busy = false }
    } }, enabled = !busy) { Text(if (busy) "正在识别" else "粘贴二维码图片") }
}
