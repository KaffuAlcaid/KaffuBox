// 使用 Android 系统解码器提供媒体信息、视频帧、GIF、音轨和文字字幕导出
package io.kaffubox.android

import android.content.Context
import android.graphics.Bitmap
import android.media.*
import android.os.Build
import io.kaffubox.core.*
import com.squareup.gifencoder.GifEncoder
import com.squareup.gifencoder.ImageOptions
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.*

class AndroidMediaTools(private val context: Context, private val files: AndroidFileBroker) {
    private val read = setOf(Capability.FileRead)
    private val write = setOf(Capability.FileRead, Capability.FileWrite)
    private fun retriever(id: String) = MediaMetadataRetriever().apply { setDataSource(context, files.sourceUri(id)) }
    private fun extractor(id: String) = MediaExtractor().apply { setDataSource(context, files.sourceUri(id), null) }
    private fun MediaFormat.text(key: String): String? = runCatching { getString(key) }.getOrNull()
    private fun MediaFormat.integer(key: String): Int? = runCatching { getInteger(key) }.getOrNull()
    private fun MediaFormat.longValue(key: String): Long? = runCatching { getLong(key) }.getOrNull()
    private fun MediaFormat.number(key: String): Double? = runCatching { getFloat(key).toDouble() }.getOrNull() ?: integer(key)?.toDouble()

    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("media.probe", Workbench.Media, mapOf("file" to "string"), listOf("file"), read) { args, _ -> probe(args.string("file")) },
        defineTool("media.extractFrame", Workbench.Media, mapOf("file" to "string", "start" to "number", "name" to "string"), listOf("file"), write) { args, ctx ->
            val frame = frame(args.string("file"), args.number("start", 0.0))
            try { ctx.checkpoint(); val output = java.io.ByteArrayOutputStream(); frame.compress(Bitmap.CompressFormat.PNG, 100, output); val ref = files.writeBytes(args.string("name", "Frame.png"), output.toByteArray(), "image/png"); jsonObject("file" to CoreJson.encodeToJsonElement(ref)) }
            finally { frame.recycle() }
        },
        defineTool("media.videoToGif", Workbench.Format, mapOf("file" to "string", "originalSize" to "boolean", "width" to "integer", "fps" to "integer", "start" to "number", "duration" to "number", "name" to "string"), listOf("file"), write) { args, ctx ->
            withContext(Dispatchers.IO) {
                val reader = retriever(args.string("file"))
                val ref = files.createOutput(args.string("name", "Video.gif"), "image/gif")
                try {
                    val fullDuration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000) ?: throw ToolFailure("mediaDurationUnavailable")
                    val start = args.number("start", 0.0); val duration = args.number("duration", fullDuration - start)
                    val fps = args.int("fps", 15)
                    requireArgument(start >= 0 && start < fullDuration && duration > 0 && fps in 1..60, "intervalOrFps")
                    val count = ceil(min(duration, fullDuration - start) * fps).toInt().coerceAtLeast(1)
                    val first = reader.getFrameAtTime((start * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) ?: throw ToolFailure("mediaNoVideo")
                    val width = if (args.flag("originalSize")) first.width else args.int("width", 480)
                    val height = max(1, (first.height.toDouble() * width / first.width).roundToInt())
                    requireArgument(width in 16..4096 && width.toLong() * height <= 16_000_000, "pixelCount")
                    first.recycle()
                    files.resolve(ref.id).openOutput!!.invoke().use { output ->
                        val encoder = GifEncoder(output, width, height, 0)
                        val options = ImageOptions().setDelay((1000.0 / fps).roundToLong(), TimeUnit.MILLISECONDS)
                        val pixels = IntArray(width * height)
                        for (index in 0 until count) {
                            ctx.checkpoint(index.toLong(), count.toLong(), "encoding")
                            val bitmap = if (Build.VERSION.SDK_INT >= 27) reader.getScaledFrameAtTime(((start + index.toDouble() / fps) * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST, width, height)
                            else reader.getFrameAtTime(((start + index.toDouble() / fps) * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                            if (bitmap == null) throw ToolFailure("imageDecodeFailed")
                            val scaled = if (bitmap.width == width && bitmap.height == height) bitmap else Bitmap.createScaledBitmap(bitmap, width, height, true)
                            try { scaled.getPixels(pixels, 0, width, 0, 0, width, height); encoder.addImage(pixels, width, options) }
                            finally { if (scaled !== bitmap) scaled.recycle(); bitmap.recycle() }
                        }
                        encoder.finishEncoding()
                    }
                    jsonObject("file" to CoreJson.encodeToJsonElement(files.resolve(ref.id).ref))
                } catch (failure: Throwable) { files.discard(ref); throw failure }
                finally { reader.release() }
            }
        },
        defineTool("media.extractAudio", Workbench.Media, mapOf("file" to "string", "name" to "string"), listOf("file"), write) { args, ctx ->
            withContext(Dispatchers.IO) {
                val source = extractor(args.string("file")); val ref = files.createOutput(args.string("name", "Audio.m4a"), "audio/mp4")
                try {
                    val track = (0 until source.trackCount).firstOrNull { source.getTrackFormat(it).text(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: throw ToolFailure("mediaNoAudio")
                    val format = source.getTrackFormat(track)
                    if (format.text(MediaFormat.KEY_MIME) !in setOf("audio/mp4a-latm", "audio/3gpp", "audio/amr-wb")) throw ToolFailure("audioCodecUnsupported")
                    source.selectTrack(track)
                    val target = MediaMuxer(files.resolve(ref.id).localPath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    try {
                        val targetTrack = target.addTrack(format); target.start()
                        val buffer = ByteBuffer.allocate((format.integer(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 1024 * 1024).coerceIn(65536, 4 * 1024 * 1024))
                        val info = MediaCodec.BufferInfo(); val duration = format.longValue(MediaFormat.KEY_DURATION)
                        while (true) { ctx.checkpoint(source.sampleTime.coerceAtLeast(0), duration, "extracting"); buffer.clear(); val size = source.readSampleData(buffer, 0); if (size < 0) break; info.set(0, size, source.sampleTime, source.sampleFlags); target.writeSampleData(targetTrack, buffer, info); if (!source.advance()) break }
                        target.stop()
                    } finally { target.release() }
                    jsonObject("file" to CoreJson.encodeToJsonElement(files.resolve(ref.id).ref))
                } catch (failure: Throwable) { files.discard(ref); throw failure }
                finally { source.release() }
            }
        },
        defineTool("media.extractSubtitles", Workbench.Media, mapOf("file" to "string", "index" to "integer", "name" to "string"), listOf("file"), write) { args, ctx ->
            withContext(Dispatchers.IO) {
                val source = extractor(args.string("file"))
                try {
                    val tracks = (0 until source.trackCount).filter { subtitleCodec(source.getTrackFormat(it).text(MediaFormat.KEY_MIME).orEmpty()) != null }
                    val track = tracks.getOrNull(args.int("index", 0)) ?: throw ToolFailure("mediaNoSubtitles")
                    val mime = source.getTrackFormat(track).text(MediaFormat.KEY_MIME).orEmpty()
                    source.selectTrack(track)
                    val buffer = ByteBuffer.allocate(1024 * 1024); val entries = mutableListOf<Pair<Long, String>>()
                    while (true) {
                        ctx.checkpoint(); buffer.clear(); val size = source.readSampleData(buffer, 0); if (size < 0) break
                        val bytes = ByteArray(size); buffer.position(0); buffer.get(bytes)
                        val text = if (mime == "text/3gpp" && size >= 2) { val length = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255); bytes.copyOfRange(2, minOf(size, 2 + length)).toString(Charsets.UTF_8) } else bytes.toString(Charsets.UTF_8)
                        if (text.isNotBlank()) entries += source.sampleTime / 1000 to text
                        requireArgument(entries.size <= 100000, "subtitles")
                        if (!source.advance()) break
                    }
                    if (entries.isEmpty()) throw ToolFailure("mediaNoSubtitles")
                    fun stamp(ms: Long): String = "%02d:%02d:%02d,%03d".format(java.util.Locale.ROOT, ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
                    val content = entries.mapIndexed { index, (start, text) -> (index + 1).toString() + "\n" + stamp(start) + " --> " + stamp(entries.getOrNull(index + 1)?.first ?: start + 3000) + "\n" + text + "\n" }.joinToString("\n")
                    jsonObject("file" to CoreJson.encodeToJsonElement(files.writeBytes(args.string("name", "Subtitles.srt"), content.toByteArray(Charsets.UTF_8), "application/x-subrip")))
                } finally { source.release() }
            }
        }
    )

    suspend fun frame(id: String, seconds: Double, maxWidth: Int? = null): Bitmap = withContext(Dispatchers.IO) {
        requireArgument(seconds.isFinite() && seconds >= 0, "time")
        val reader = retriever(id)
        try {
            val bitmap = reader.getFrameAtTime((seconds * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) ?: throw ToolFailure("mediaNoVideo")
            if (maxWidth == null || bitmap.width <= maxWidth) bitmap
            else Bitmap.createScaledBitmap(bitmap, maxWidth, max(1, (bitmap.height.toDouble() * maxWidth / bitmap.width).roundToInt()), true).also { bitmap.recycle() }
        } finally { reader.release() }
    }

    private fun subtitleCodec(mime: String): String? = when (mime) { "text/3gpp" -> "mov_text"; "application/x-subrip" -> "subrip"; "text/vtt" -> "webvtt"; "text/x-ssa" -> "ass"; else -> null }
    suspend fun probe(id: String): JsonObject = withContext(Dispatchers.IO) {
        val reader = retriever(id); val source = extractor(id)
        try {
            val streams = (0 until source.trackCount).map { index ->
                val format = source.getTrackFormat(index); val mime = format.text(MediaFormat.KEY_MIME).orEmpty()
                val subtitle = subtitleCodec(mime)
                val type = when { mime.startsWith("video/") -> "video"; mime.startsWith("audio/") -> "audio"; subtitle != null -> "subtitle"; else -> "data" }
                val codec = subtitle ?: when (mime) { "video/avc" -> "h264"; "video/hevc" -> "hevc"; "audio/mp4a-latm" -> "aac"; else -> mime.substringAfter('/') }
                jsonObject("index" to index, "codec_type" to type, "codec_name" to codec, "codec_long_name" to mime,
                    "width" to format.integer(MediaFormat.KEY_WIDTH), "height" to format.integer(MediaFormat.KEY_HEIGHT),
                    "avg_frame_rate" to format.number(MediaFormat.KEY_FRAME_RATE)?.let { "$it/1" }, "sample_rate" to format.integer(MediaFormat.KEY_SAMPLE_RATE),
                    "channels" to format.integer(MediaFormat.KEY_CHANNEL_COUNT), "bit_rate" to format.integer(MediaFormat.KEY_BIT_RATE),
                    "duration" to format.longValue(MediaFormat.KEY_DURATION)?.div(1000000.0),
                    "tags" to jsonObject("language" to format.text(MediaFormat.KEY_LANGUAGE)), "disposition" to jsonObject("default" to (format.integer(MediaFormat.KEY_IS_DEFAULT) ?: 0)))
            }
            val ref = files.resolve(id).ref
            jsonObject("streams" to streams, "format" to jsonObject("filename" to ref.name, "format_long_name" to (reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ref.mimeType), "size" to ref.size,
                "duration" to reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000), "bit_rate" to reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()))
        } finally { reader.release(); source.release() }
    }
}
