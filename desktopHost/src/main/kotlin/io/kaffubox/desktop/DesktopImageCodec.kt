// 使用桌面 ImageIO 与 Graphics2D 提供像素编解码和文字水印，不创建窗口
package io.kaffubox.desktop

import io.kaffubox.core.*
import kotlinx.serialization.json.JsonObject
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.IIOImage
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream

class DesktopImageCodec : ImageCodecPort {
    override val formats = setOf("png", "jpeg", "bmp", "gif")
    override fun information(handle: FileHandle): JsonObject = handle.openInput().use { input -> MemoryCacheImageInputStream(input).use { stream ->
        val readers = ImageIO.getImageReaders(stream); if (!readers.hasNext()) throw ToolFailure("imageFormatUnsupported")
        val reader = readers.next()
        try { reader.input = stream; jsonObject("width" to reader.getWidth(0), "height" to reader.getHeight(0), "format" to reader.formatName.lowercase()) } finally { reader.dispose() }
    } }

    override fun decode(handle: FileHandle, maxPixels: Int): RasterImage = handle.openInput().use { input -> MemoryCacheImageInputStream(input).use { stream ->
        val readers = ImageIO.getImageReaders(stream); if (!readers.hasNext()) throw ToolFailure("imageFormatUnsupported")
        val reader = readers.next()
        try {
            reader.input = stream
            val width = reader.getWidth(0); val height = reader.getHeight(0)
            requireArgument(width.toLong() * height <= maxPixels, "pixelCount")
            val image = reader.read(0)
            try { RasterImage(width, height, image.getRGB(0, 0, width, height, null, 0, width)) } finally { image.flush() }
        } finally { reader.dispose() }
    } }

    override fun encode(image: RasterImage, format: String, quality: Int): ByteArray {
        val buffered = BufferedImage(image.width, image.height, if (format in setOf("jpeg", "bmp")) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB)
        val pixels = if (format in setOf("jpeg", "bmp")) IntArray(image.pixels.size) { index ->
            val pixel = image.pixels[index]
            val alpha = pixel ushr 24
            val r = ((pixel ushr 16 and 255) * alpha + 255 * (255 - alpha)) / 255
            val g = ((pixel ushr 8 and 255) * alpha + 255 * (255 - alpha)) / 255
            val b = ((pixel and 255) * alpha + 255 * (255 - alpha)) / 255
            -0x1000000 or (r shl 16) or (g shl 8) or b
        } else image.pixels
        buffered.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val writers = ImageIO.getImageWritersByFormatName(format)
        if (!writers.hasNext()) throw ToolFailure("imageFormatUnsupported")
        val writer = writers.next(); val bytes = ByteArrayOutputStream()
        try {
            MemoryCacheImageOutputStream(bytes).use { output ->
                writer.output = output
                val parameters = writer.defaultWriteParam
                if (format == "jpeg") { parameters.compressionMode = ImageWriteParam.MODE_EXPLICIT; parameters.compressionQuality = quality / 100f }
                writer.write(null, IIOImage(buffered, null, null), parameters)
            }
            return bytes.toByteArray()
        } finally { writer.dispose(); buffered.flush() }
    }

    override fun watermark(image: RasterImage, text: String, x: Int, y: Int, size: Int, color: Int): RasterImage {
        val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        try {
            buffered.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
            val graphics = buffered.createGraphics()
            try { graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON); graphics.font = Font("Dialog", Font.PLAIN, size); graphics.color = Color(color, true); graphics.drawString(text, x, y) } finally { graphics.dispose() }
            return RasterImage(image.width, image.height, buffered.getRGB(0, 0, image.width, image.height, null, 0, image.width))
        } finally { buffered.flush() }
    }
}
