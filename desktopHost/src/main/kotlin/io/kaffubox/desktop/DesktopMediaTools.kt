// 将媒体探测、转换、GIF、裁剪和音频处理转换为受控 FFmpeg 参数并清理失败输出
package io.kaffubox.desktop

import io.kaffubox.core.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

class DesktopMediaTools(private val files: DesktopFileBroker, private val ffmpeg: String?, private val ffprobe: String?) {
    private val permissions = setOf(Capability.FileRead, Capability.FileWrite, Capability.NativeProcess)
    fun definitions(): List<ToolDefinition> = buildList {
        add(defineTool("media.engines", Workbench.Media) { _, _ -> jsonObject("ffmpeg" to available(ffmpeg), "ffprobe" to available(ffprobe)) })
        if (available(ffprobe)) add(defineTool("media.probe", Workbench.Media, mapOf("file" to "string"), listOf("file"), setOf(Capability.FileRead, Capability.NativeProcess)) { args, _ ->
            val input = path(args.string("file"))
            val result = ProcessRunner.run(listOf(ffprobe!!, "-v", "error", "-protocol_whitelist", "file,pipe", "-show_format", "-show_streams", "-of", "json", input))
            ensureSuccess(result); CoreJson.parseToJsonElement(result.output)
        })
        if (!available(ffmpeg)) return@buildList
        add(defineTool("media.videoToGif", Workbench.Media, mapOf("file" to "string", "name" to "string", "originalSize" to "boolean", "width" to "integer", "fps" to "integer", "start" to "number", "duration" to "number"), listOf("file"), permissions) { args, context ->
            val width = args.int("width", 480); val fps = args.int("fps", 15)
            val originalSize = args.flag("originalSize")
            requireArgument((originalSize || width in 16..4096) && fps in 1..60, "widthOrFps")
            val scaling = if (originalSize) "" else "scale=$width:-1:flags=lanczos,"
            val filters = "fps=$fps," + scaling + "split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=sierra2_4a"
            transcode(args, context, "gif", listOf("-filter_complex", filters, "-loop", "0", "-an"))
        })
        add(defineTool("format.mediaConvert", Workbench.Format, mapOf("file" to "string", "format" to "string", "name" to "string", "quality" to "integer"), listOf("file", "format"), permissions) { args, context ->
            val format = args.string("format").lowercase(java.util.Locale.ROOT)
            val quality = args.int("quality", 23); requireArgument(quality in 0..51, "quality")
            val options = when (format) {
                "mp4", "mov" -> listOf("-c:v", "libx264", "-crf", quality.toString(), "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart")
                "mkv" -> listOf("-c:v", "libx264", "-crf", quality.toString(), "-c:a", "aac")
                "webm" -> listOf("-c:v", "libvpx-vp9", "-crf", quality.toString(), "-b:v", "0", "-c:a", "libopus")
                "mp3" -> listOf("-vn", "-c:a", "libmp3lame", "-q:a", "2")
                "wav" -> listOf("-vn", "-c:a", "pcm_s16le")
                "flac" -> listOf("-vn", "-c:a", "flac")
                "ogg" -> listOf("-vn", "-c:a", "libvorbis", "-q:a", "5")
                "m4a" -> listOf("-vn", "-c:a", "aac", "-movflags", "+faststart")
                "aac" -> listOf("-vn", "-c:a", "aac")
                "webp" -> listOf("-c:v", "libwebp", "-loop", "0", "-an")
                "apng" -> listOf("-c:v", "apng", "-plays", "0", "-an")
                else -> throw ToolFailure("mediaFormatUnsupported")
            }
            transcode(args, context, format, options)
        })
        add(defineTool("media.extractAudio", Workbench.Media, mapOf("file" to "string", "format" to "string", "name" to "string"), listOf("file"), permissions) { args, context ->
            val format = args.string("format", "mp3")
            val codec = when (format) { "mp3" -> "libmp3lame"; "wav" -> "pcm_s16le"; "flac" -> "flac"; "m4a", "aac" -> "aac"; "ogg" -> "libvorbis"; else -> throw ToolFailure("audioFormatUnsupported") }
            transcode(args, context, format, listOf("-vn", "-c:a", codec))
        })
        add(defineTool("media.edit", Workbench.Media, mapOf("file" to "string", "name" to "string", "start" to "number", "duration" to "number", "speed" to "number", "rotate" to "integer", "width" to "integer", "mute" to "boolean", "normalizeAudio" to "boolean"), listOf("file"), permissions) { args, context ->
            val speed = args.number("speed", 1.0); requireArgument(speed in 0.25..4.0, "speed")
            val rotation = args.int("rotate", 0); requireArgument(rotation in setOf(0, 90, 180, 270), "rotate")
            val video = mutableListOf<String>(); val audio = mutableListOf<String>()
            when (rotation) { 90 -> video += "transpose=1"; 180 -> video += "hflip,vflip"; 270 -> video += "transpose=2" }
            if (args.containsKey("width")) { val width = args.int("width"); requireArgument(width in 16..7680 && width % 2 == 0, "width"); video += "scale=$width:-2" }
            if (args.containsKey("crop")) {
                val crop = args.objectValue("crop"); val width = crop.int("width"); val height = crop.int("height"); val x = crop.int("x", 0); val y = crop.int("y", 0)
                requireArgument(width > 0 && height > 0 && x >= 0 && y >= 0, "crop"); video.add(0, "crop=$width:$height:$x:$y")
            }
            if (speed != 1.0) { video += "setpts=PTS/$speed"; var remaining = speed; while (remaining > 2) { audio += "atempo=2"; remaining /= 2 }; while (remaining < 0.5) { audio += "atempo=0.5"; remaining /= 0.5 }; audio += "atempo=$remaining" }
            val options = mutableListOf("-c:v", "libx264", "-crf", args.int("quality", 23).also { requireArgument(it in 0..51, "quality") }.toString(), "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart")
            if (video.isNotEmpty()) options += listOf("-vf", video.joinToString(","))
            if (args.flag("mute")) options += "-an" else if (audio.isNotEmpty()) options += listOf("-af", audio.joinToString(","))
            transcode(args, context, "mp4", options)
        })
        add(defineTool("media.extractFrame", Workbench.Media, mapOf("file" to "string", "start" to "number", "name" to "string"), listOf("file"), permissions) { args, context ->
            transcode(args, context, "png", listOf("-frames:v", "1", "-update", "1"))
        })
        add(defineTool("media.extractSubtitles", Workbench.Media, mapOf("file" to "string", "index" to "integer", "name" to "string"), listOf("file"), permissions) { args, context ->
            val stream = args.int("index", 0); requireArgument(stream in 0..63, "index")
            transcode(args, context, "srt", listOf("-map", "0:s:$stream", "-c:s", "srt"))
        })
    }

    private suspend fun transcode(args: JsonObject, context: ToolContext, extension: String, options: List<String>): JsonElement {
        val input = context.files.resolve(args.string("file")).localPath ?: throw ToolFailure("localFileRequired")
        val outputName = safeFileName(args.string("name", "Converted.$extension"))
        requireArgument(outputName.substringAfterLast('.', "").equals(extension, true), "nameExtension")
        val ref = context.files.createOutput(outputName)
        val output = context.files.resolve(ref.id).localPath ?: throw ToolFailure("localFileRequired")
        try {
            val arguments = mutableListOf(ffmpeg!!, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-protocol_whitelist", "file,pipe")
            if (args.containsKey("start")) { val start = args.number("start"); requireArgument(start >= 0, "start"); arguments += listOf("-ss", start.toString()) }
            arguments += listOf("-i", input)
            if (args.containsKey("duration")) { val duration = args.number("duration"); requireArgument(duration > 0, "duration"); arguments += listOf("-t", duration.toString()) }
            arguments += adjustedOptions(args, extension, options)
            arguments += listOf("-progress", "pipe:1", "-nostats", output)
            val totalSeconds = args["duration"]?.jsonPrimitive?.doubleOrNull ?: if (available(ffprobe)) runCatching {
                val probe = ProcessRunner.run(listOf(ffprobe!!, "-v", "error", "-protocol_whitelist", "file,pipe", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", input))
                probe.output.trim().toDoubleOrNull()?.let { (it - args.number("start", 0.0)).coerceAtLeast(0.0) }
            }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else null } else null
            val result = ProcessRunner.run(arguments, 6 * 60 * 60 * 1000L) { line ->
                if (line.startsWith("out_time_us=")) line.substringAfter('=').toLongOrNull()?.let { context.progress(it, totalSeconds?.let { duration -> (duration * 1_000_000).toLong() }, "encoding") }
            }
            ensureSuccess(result)
            context.checkpoint()
            return jsonObject("file" to CoreJson.encodeToJsonElement(ref.copy(size = Files.size(Path.of(output)))))
        } catch (failure: Throwable) { context.files.discard(ref); throw failure }
    }

    private fun adjustedOptions(args: JsonObject, extension: String, original: List<String>): List<String> {
        if (extension in setOf("png", "srt", "gif")) return original
        val result = original.toMutableList()
        fun set(key: String, value: String) { val at = result.indexOf(key); if (at >= 0) result[at + 1] = value else result += listOf(key, value) }
        fun filter(key: String, value: String) { val at = result.indexOf(key); set(key, if (at >= 0) result[at + 1] + "," + value else value) }
        val video = extension in setOf("mp4", "mkv", "mov", "webm", "webp", "apng")
        if (video) {
            args.optionalString("videoEncoder")?.takeIf(String::isNotBlank)?.let { encoder ->
                val allowed = when (extension) { "mp4", "mov" -> setOf("libx264", "libx265"); "webm" -> setOf("libvpx-vp9"); "mkv" -> setOf("libx264", "libx265", "libvpx-vp9"); else -> emptySet() }
                requireArgument(encoder in allowed, "videoEncoder"); set("-c:v", encoder)
            }
            if (args.containsKey("width") && original.none { it.contains("scale=") }) {
                val width = args.int("width"); val height = args.int("height", -2)
                requireArgument(width in 16..7680 && width % 2 == 0 && (height == -2 || height in 16..7680 && height % 2 == 0), "dimensions")
                filter("-vf", "scale=$width:$height")
            }
            args["fps"]?.jsonPrimitive?.doubleOrNull?.let { requireArgument(it.isFinite() && it in 1.0..120.0, "fps"); set("-r", it.toString()) }
        }
        if (extension !in setOf("webp", "apng") && "-an" !in result) {
            args.optionalString("audioEncoder")?.takeIf(String::isNotBlank)?.let { encoder ->
                val allowed = when (extension) { "mp4", "mov", "m4a", "aac" -> setOf("aac"); "webm" -> setOf("libopus", "libvorbis"); "mp3" -> setOf("libmp3lame"); "wav" -> setOf("pcm_s16le", "pcm_s24le"); "flac" -> setOf("flac"); "ogg" -> setOf("libopus", "libvorbis"); "mkv" -> setOf("aac", "libopus", "flac"); else -> emptySet() }
                requireArgument(encoder in allowed, "audioEncoder"); set("-c:a", encoder)
            }
            args["audioBitrate"]?.jsonPrimitive?.intOrNull?.let { requireArgument(it in 16..1536, "audioBitrate"); val q = result.indexOf("-q:a"); if (q >= 0) { result.removeAt(q); result.removeAt(q) }; set("-b:a", "${it}k") }
            args["sampleRate"]?.jsonPrimitive?.intOrNull?.let { requireArgument(it in setOf(8000, 16000, 22050, 24000, 32000, 44100, 48000, 96000, 192000), "sampleRate"); set("-ar", it.toString()) }
            args["channels"]?.jsonPrimitive?.intOrNull?.let { requireArgument(it in 1..8, "channels"); set("-ac", it.toString()) }
            val volume = args.number("volumeDb", 0.0); requireArgument(volume in -60.0..30.0, "volumeDb"); if (volume != 0.0) filter("-af", "volume=${volume}dB")
            if (args.flag("normalizeAudio")) { val loudness = args.number("loudness", -16.0); requireArgument(loudness in -70.0..-5.0, "loudness"); filter("-af", "loudnorm=I=$loudness:TP=-1.5:LRA=11") }
        }
        if (args.flag("stripMetadata")) result += listOf("-map_metadata", "-1")
        return result
    }

    private fun path(id: String): String = files.resolve(id).localPath ?: throw ToolFailure("localFileRequired")
    private fun available(executable: String?): Boolean = executable != null && Files.isRegularFile(Path.of(executable))
    private fun ensureSuccess(result: ProcessResult) { if (result.exitCode != 0) throw ToolFailure("mediaEngineFailed", jsonObject("exitCode" to result.exitCode, "details" to result.error.takeLast(12_000))) }
}
