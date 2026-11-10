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
                "webp" -> listOf("-c:v", "libwebp", "-loop", "0", "-an")
                "apng" -> listOf("-c:v", "apng", "-plays", "0", "-an")
                else -> throw ToolFailure("mediaFormatUnsupported")
            }
            transcode(args, context, format, options)
        })
        add(defineTool("media.extractAudio", Workbench.Media, mapOf("file" to "string", "format" to "string", "name" to "string"), listOf("file"), permissions) { args, context ->
            val format = args.string("format", "mp3")
            val codec = when (format) { "mp3" -> "libmp3lame"; "wav" -> "pcm_s16le"; "flac" -> "flac"; else -> throw ToolFailure("audioFormatUnsupported") }
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
            if (args.flag("normalizeAudio")) audio += "loudnorm=I=-16:TP=-1.5:LRA=11"
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
        val input = path(args.string("file"))
        val outputName = safeFileName(args.string("name", "Converted.$extension"))
        requireArgument(outputName.substringAfterLast('.', "").equals(extension, true), "nameExtension")
        val ref = files.createOutput(outputName)
        val output = path(ref.id)
        try {
            val arguments = mutableListOf(ffmpeg!!, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-protocol_whitelist", "file,pipe")
            if (args.containsKey("start")) { val start = args.number("start"); requireArgument(start >= 0, "start"); arguments += listOf("-ss", start.toString()) }
            arguments += listOf("-i", input)
            if (args.containsKey("duration")) { val duration = args.number("duration"); requireArgument(duration > 0, "duration"); arguments += listOf("-t", duration.toString()) }
            arguments += options
            arguments += listOf("-progress", "pipe:1", "-nostats", output)
            val result = ProcessRunner.run(arguments, 6 * 60 * 60 * 1000L) { line ->
                if (line.startsWith("out_time_us=")) line.substringAfter('=').toLongOrNull()?.let { context.progress(it, args["duration"]?.jsonPrimitive?.doubleOrNull?.let { duration -> (duration * 1_000_000).toLong() }, "encoding") }
            }
            ensureSuccess(result)
            context.checkpoint()
            return jsonObject("file" to CoreJson.encodeToJsonElement(ref.copy(size = Files.size(Path.of(output)))))
        } catch (failure: Throwable) { files.discard(ref); throw failure }
    }

    private fun path(id: String): String = files.resolve(id).localPath ?: throw ToolFailure("localFileRequired")
    private fun available(executable: String?): Boolean = executable != null && Files.isRegularFile(Path.of(executable))
    private fun ensureSuccess(result: ProcessResult) { if (result.exitCode != 0) throw ToolFailure("mediaEngineFailed", jsonObject("exitCode" to result.exitCode, "details" to result.error.takeLast(12_000))) }
}
