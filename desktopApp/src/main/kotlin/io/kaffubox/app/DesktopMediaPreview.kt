// 提供桌面平台的界面适配实现
package io.kaffubox.app

import androidx.compose.ui.graphics.*
import io.kaffubox.core.*
import io.kaffubox.desktop.ProcessRunner
import kotlinx.coroutines.*
import java.nio.file.Files
import javax.imageio.ImageIO

suspend fun videoPreview(controller: AppController, ref: FileRef, seconds: Double): ImageBitmap = withContext(Dispatchers.IO) {
    val engine = controller.ffmpeg ?: throw ToolFailure("mediaEngineUnavailable")
    val input = controller.files.resolve(ref.id).localPath ?: throw ToolFailure("localFileRequired")
    val directory = controller.dataRoot.resolve("Previews"); Files.createDirectories(directory)
    val target = Files.createTempFile(directory, "Frame", ".png")
    try {
        val result = ProcessRunner.run(listOf(engine, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-protocol_whitelist", "file,pipe", "-ss", seconds.toString(), "-i", input, "-frames:v", "1", "-vf", "scale=960:540:force_original_aspect_ratio=decrease", "-update", "1", target.toString()), 30000)
        if (result.exitCode != 0) throw ToolFailure("mediaEngineFailed", jsonObject("details" to result.error))
        val image = ImageIO.read(target.toFile()) ?: throw ToolFailure("imageDecodeFailed")
        try { image.toComposeImageBitmap() } finally { image.flush() }
    } finally { Files.deleteIfExists(target) }
}
