// 提供平台无关的像素处理、拼图、目标体积压缩、取色和二维码识别，编码交由平台适配器
package io.kaffubox.core

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.math.*

data class RasterImage(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong()) }
}

interface ImageCodecPort {
    val formats: Set<String>
    fun information(handle: FileHandle): JsonObject
    fun decode(handle: FileHandle, maxPixels: Int): RasterImage
    fun encode(image: RasterImage, format: String, quality: Int): ByteArray
    fun watermark(image: RasterImage, text: String, x: Int, y: Int, size: Int, color: Int): RasterImage
}

class ImageTools(private val codec: ImageCodecPort) {
    private val mutex = Mutex()
    private val read = setOf(Capability.FileRead)
    private val write = setOf(Capability.FileRead, Capability.FileWrite)
    private val maxPixels = 16_000_000

    suspend fun readImage(handle: FileHandle, context: ToolContext): RasterImage = mutex.withLock { load(handle, context) }

    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("image.formats", Workbench.Image) { _, _ -> jsonObject("formats" to codec.formats.sorted(), "maxPixels" to maxPixels) },
        defineTool("image.info", Workbench.Image, mapOf("file" to "string"), listOf("file"), read) { args, context ->
            val handle = context.files.resolve(args.string("file"))
            JsonObject(codec.information(handle) + ("orientation" to JsonPrimitive(MediaMetadata.orientation(handle))))
        },
        defineTool("image.transform", Workbench.Image, mapOf("file" to "string", "format" to "string", "width" to "integer", "height" to "integer", "rotate" to "integer", "quality" to "integer", "targetBytes" to "integer", "grayscale" to "boolean"), listOf("file"), write) { args, context ->
            mutex.withLock {
                var image = load(context.files.resolve(args.string("file")), context)
                args["crop"]?.let {
                    val crop = args.objectValue("crop"); val x = crop.int("x", 0); val y = crop.int("y", 0); val width = crop.int("width"); val height = crop.int("height")
                    requireArgument(x >= 0 && y >= 0 && width > 0 && height > 0 && x.toLong() + width <= image.width && y.toLong() + height <= image.height, "crop")
                    val pixels = IntArray(width * height)
                    for (row in 0 until height) { context.checkpoint(); image.pixels.copyInto(pixels, row * width, (row + y) * image.width + x, (row + y) * image.width + x + width) }
                    image = RasterImage(width, height, pixels)
                }
                val rotate = args.int("rotate", 0); requireArgument(rotate in setOf(0, 90, 180, 270), "rotate")
                if (rotate != 0) image = rotate(image, rotate, context)
                if (args.containsKey("width") || args.containsKey("height")) {
                    val width = args.int("width", ((args.int("height", image.height).toDouble() / image.height) * image.width).roundToInt())
                    val height = args.int("height", (width.toDouble() * image.height / image.width).roundToInt())
                    image = resize(image, width, height, context)
                }
                if (args.flag("grayscale")) {
                    for (i in image.pixels.indices) {
                        if (i % 65536 == 0) context.checkpoint()
                        val pixel = image.pixels[i]; val gray = (0.2126 * (pixel ushr 16 and 255) + 0.7152 * (pixel ushr 8 and 255) + 0.0722 * (pixel and 255)).roundToInt()
                        image.pixels[i] = (pixel and -0x1000000) or (gray shl 16) or (gray shl 8) or gray
                    }
                }
                args["mosaic"]?.let {
                    val area = args.objectValue("mosaic"); val x = area.int("x", 0); val y = area.int("y", 0); val width = area.int("width", image.width - x); val height = area.int("height", image.height - y); val block = area.int("block", 12)
                    requireArgument(x >= 0 && y >= 0 && width > 0 && height > 0 && x.toLong() + width <= image.width && y.toLong() + height <= image.height && block in 2..256, "mosaic")
                    for (top in y until y + height step block) {
                        context.checkpoint()
                        for (left in x until x + width step block) {
                            var red = 0L; var green = 0L; var blue = 0L; var alpha = 0L; var count = 0
                            val bottom = minOf(top + block, y + height); val right = minOf(left + block, x + width)
                            for (row in top until bottom) for (column in left until right) { val p = image.pixels[row * image.width + column]; alpha += p ushr 24; red += p ushr 16 and 255; green += p ushr 8 and 255; blue += p and 255; count++ }
                            val pixel = ((alpha / count).toInt() shl 24) or ((red / count).toInt() shl 16) or ((green / count).toInt() shl 8) or (blue / count).toInt()
                            for (row in top until bottom) java.util.Arrays.fill(image.pixels, row * image.width + left, row * image.width + right, pixel)
                        }
                    }
                }
                args["watermark"]?.let {
                    val mark = args.objectValue("watermark"); val size = mark.int("size", 24); requireArgument(size in 4..512, "watermarkSize")
                    image = codec.watermark(image, mark.string("text"), mark.int("x", 16), mark.int("y", size + 16), size, parseColor(mark.string("color", "#CCFFFFFF")))
                }
                save(image, args, context)
            }
        },
        defineTool("image.stitch", Workbench.Image, mapOf("files" to "array", "direction" to "string", "gap" to "integer", "background" to "string", "format" to "string"), listOf("files"), write) { args, context ->
            mutex.withLock {
                val ids = args.strings("files"); requireArgument(ids.size in 1..32, "files")
                val images = mutableListOf<RasterImage>(); var pixels = 0L
                for (id in ids) {
                    context.checkpoint()
                    val image = load(context.files.resolve(id), context)
                    pixels += image.pixels.size; requireArgument(pixels <= maxPixels, "pixelCount"); images += image
                }
                val vertical = when (args.string("direction", "vertical")) { "vertical" -> true; "horizontal" -> false; else -> throw ToolFailure("directionInvalid") }
                val gap = args.int("gap", 0); requireArgument(gap in 0..4096, "gap")
                val width = if (vertical) images.maxOf { it.width } else images.sumOf { it.width } + gap * (images.size - 1)
                val height = if (vertical) images.sumOf { it.height } + gap * (images.size - 1) else images.maxOf { it.height }
                checkSize(width, height)
                val background = parseColor(args.string("background", "#FFFFFFFF"))
                val output = IntArray(width * height) { background }
                var offset = 0
                for (image in images) {
                    for (row in 0 until image.height) { context.checkpoint(); val target = if (vertical) (row + offset) * width else row * width + offset; image.pixels.copyInto(output, target, row * image.width, (row + 1) * image.width) }
                    offset += (if (vertical) image.height else image.width) + gap
                }
                save(RasterImage(width, height, output), args, context)
            }
        },
        defineTool("image.sampleColor", Workbench.Image, mapOf("file" to "string", "x" to "integer", "y" to "integer"), listOf("file", "x", "y"), read) { args, context ->
            mutex.withLock {
                val image = load(context.files.resolve(args.string("file")), context)
                val x = args.int("x"); val y = args.int("y"); requireArgument(x in 0 until image.width && y in 0 until image.height, "coordinates")
                val pixel = image.pixels[y * image.width + x]
                jsonObject("argb" to "#%08X".format(pixel), "red" to (pixel ushr 16 and 255), "green" to (pixel ushr 8 and 255), "blue" to (pixel and 255), "alpha" to (pixel ushr 24))
            }
        },
        defineTool("image.readCode", Workbench.Image, mapOf("file" to "string"), listOf("file"), read) { args, context ->
            mutex.withLock {
                val image = load(context.files.resolve(args.string("file")), context)
                val luminance = RGBLuminanceSource(image.width, image.height, image.pixels)
                try {
                    val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(luminance)), mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8"))
                    jsonObject("text" to result.text, "format" to result.barcodeFormat.name)
                } catch (_: NotFoundException) { throw ToolFailure("codeNotFound") }
            }
        }
    )

    private suspend fun save(original: RasterImage, args: JsonObject, context: ToolContext): JsonElement {
        val format = args.string("format", "png").lowercase(java.util.Locale.ROOT).let { if (it == "jpg") "jpeg" else it }
        requireArgument(format in codec.formats, "format")
        var quality = args.int("quality", 90); requireArgument(quality in 1..100, "quality")
        var image = original
        var encoded = codec.encode(image, format, quality)
        if (args.containsKey("targetBytes")) {
            val target = args.long("targetBytes"); requireArgument(target in 64..128_000_000, "targetBytes")
            var attempts = 0
            while (encoded.size > target) {
                context.checkpoint()
                if (format in setOf("jpeg", "webp")) {
                    var low = 1; var high = quality; var best: ByteArray? = null; var bestQuality = 1
                    while (low <= high) {
                        context.checkpoint(); val middle = (low + high) / 2; val bytes = codec.encode(image, format, middle)
                        if (bytes.size <= target) { best = bytes; bestQuality = middle; low = middle + 1 } else high = middle - 1
                    }
                    if (best != null) { encoded = best; quality = bestQuality; break }
                }
                if (!args.flag("allowResize") || ++attempts > 12 || min(image.width, image.height) <= 16) throw ToolFailure("targetSizeUnreachable", jsonObject("bytes" to encoded.size))
                image = resize(image, max(1, (image.width * 0.8).toInt()), max(1, (image.height * 0.8).toInt()), context)
                encoded = codec.encode(image, format, quality)
            }
        }
        context.checkpoint()
        val extension = if (format == "jpeg") "jpg" else format
        val name = safeFileName(args.string("name", "Image.$extension"))
        requireArgument(name.substringAfterLast('.').lowercase(java.util.Locale.ROOT) in if (format == "jpeg") setOf("jpg", "jpeg") else setOf(format), "nameExtension")
        val ref = context.files.writeBytes(name, encoded, "image/$format")
        return jsonObject("file" to CoreJson.encodeToJsonElement(ref), "width" to image.width, "height" to image.height, "quality" to quality.takeIf { format in setOf("jpeg", "webp") }, "metadataPreserved" to false)
    }

    private fun checkSize(width: Int, height: Int) { requireArgument(width > 0 && height > 0 && width.toLong() * height <= maxPixels, "pixelCount") }

    private suspend fun load(handle: FileHandle, context: ToolContext): RasterImage {
        val orientation = MediaMetadata.orientation(handle)
        var image = codec.decode(handle, maxPixels)
        image = when (orientation) { 3 -> rotate(image, 180, context); 5, 6, 7 -> rotate(image, 90, context); 8 -> rotate(image, 270, context); else -> image }
        if (orientation in setOf(2, 4, 5, 7)) {
            val horizontal = orientation == 2 || orientation == 5
            val output = IntArray(image.pixels.size)
            for (y in 0 until image.height) {
                context.checkpoint()
                for (x in 0 until image.width) {
                    val targetX = if (horizontal) image.width - 1 - x else x
                    val targetY = if (horizontal) y else image.height - 1 - y
                    output[targetY * image.width + targetX] = image.pixels[y * image.width + x]
                }
            }
            image = RasterImage(image.width, image.height, output)
        }
        return image
    }

    private suspend fun rotate(image: RasterImage, degrees: Int, context: ToolContext): RasterImage {
        val width = if (degrees == 180) image.width else image.height; val height = if (degrees == 180) image.height else image.width
        val output = IntArray(image.pixels.size)
        for (y in 0 until image.height) {
            context.checkpoint()
            for (x in 0 until image.width) {
                val targetX = when (degrees) { 90 -> image.height - 1 - y; 180 -> image.width - 1 - x; else -> y }
                val targetY = when (degrees) { 90 -> x; 180 -> image.height - 1 - y; else -> image.width - 1 - x }
                output[targetY * width + targetX] = image.pixels[y * image.width + x]
            }
        }
        return RasterImage(width, height, output)
    }

    private suspend fun resize(image: RasterImage, width: Int, height: Int, context: ToolContext): RasterImage {
        checkSize(width, height)
        if (image.width == width && image.height == height) return image
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            context.checkpoint()
            val sourceY = ((y + 0.5) * image.height / height - 0.5).coerceIn(0.0, image.height - 1.0); val y0 = sourceY.toInt(); val y1 = min(y0 + 1, image.height - 1); val fy = sourceY - y0
            for (x in 0 until width) {
                val sourceX = ((x + 0.5) * image.width / width - 0.5).coerceIn(0.0, image.width - 1.0); val x0 = sourceX.toInt(); val x1 = min(x0 + 1, image.width - 1); val fx = sourceX - x0
                var alpha = 0.0; var red = 0.0; var green = 0.0; var blue = 0.0
                for (index in 0..3) {
                    val pixel = image.pixels[(if (index < 2) y0 else y1) * image.width + if (index % 2 == 0) x0 else x1]
                    val weight = (if (index % 2 == 0) 1 - fx else fx) * (if (index < 2) 1 - fy else fy)
                    val a = (pixel ushr 24) / 255.0; alpha += a * weight; red += (pixel ushr 16 and 255) * a * weight; green += (pixel ushr 8 and 255) * a * weight; blue += (pixel and 255) * a * weight
                }
                pixels[y * width + x] = if (alpha <= 0) 0 else ((alpha * 255).roundToInt().coerceIn(0, 255) shl 24) or ((red / alpha).roundToInt().coerceIn(0, 255) shl 16) or ((green / alpha).roundToInt().coerceIn(0, 255) shl 8) or (blue / alpha).roundToInt().coerceIn(0, 255)
            }
        }
        return RasterImage(width, height, pixels)
    }
}

fun parseColor(value: String): Int {
    requireArgument(value.matches(Regex("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?")), "color")
    return if (value.length == 7) value.substring(1).toLong(16).toInt() or -0x1000000 else value.substring(1).toLong(16).toInt()
}
