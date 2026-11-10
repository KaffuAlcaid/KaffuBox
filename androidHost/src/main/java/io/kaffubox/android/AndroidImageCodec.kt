// 使用 Android Bitmap 编解码与 Canvas 文字绘制适配共享图片工具，不创建界面
package io.kaffubox.android

import android.graphics.*
import io.kaffubox.core.*
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream

class AndroidImageCodec : ImageCodecPort {
    override val formats = setOf("png", "jpeg", "webp")
    override fun information(handle: FileHandle): JsonObject {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        handle.openInput().use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw ToolFailure("imageFormatUnsupported")
        return jsonObject("width" to options.outWidth, "height" to options.outHeight, "format" to options.outMimeType)
    }
    override fun decode(handle: FileHandle, maxPixels: Int): RasterImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        handle.openInput().use { BitmapFactory.decodeStream(it, null, bounds) }
        requireArgument(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= maxPixels, "pixelCount")
        val bitmap = handle.openInput().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) } ?: throw ToolFailure("imageDecodeFailed")
        return try { val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height); RasterImage(bitmap.width, bitmap.height, pixels) } finally { bitmap.recycle() }
    }
    override fun encode(image: RasterImage, format: String, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(image.pixels, 0, image.width, 0, 0, image.width, image.height)
            val outputBitmap = if (format == "jpeg") Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).apply { Canvas(this).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) } } else bitmap
            try {
                val codec = when (format) { "png" -> Bitmap.CompressFormat.PNG; "jpeg" -> Bitmap.CompressFormat.JPEG; "webp" -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP; else -> throw ToolFailure("imageFormatUnsupported") }
                return ByteArrayOutputStream().use { output -> if (!outputBitmap.compress(codec, quality, output)) throw ToolFailure("imageEncodeFailed"); output.toByteArray() }
            } finally { if (outputBitmap !== bitmap) outputBitmap.recycle() }
        } finally { bitmap.recycle() }
    }
    override fun watermark(image: RasterImage, text: String, x: Int, y: Int, size: Int, color: Int): RasterImage {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(image.pixels, 0, image.width, 0, 0, image.width, image.height)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size.toFloat(); this.color = color }
            Canvas(bitmap).drawText(text, x.toFloat(), y.toFloat(), paint)
            val pixels = IntArray(image.pixels.size); bitmap.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            return RasterImage(image.width, image.height, pixels)
        } finally { bitmap.recycle() }
    }
}
