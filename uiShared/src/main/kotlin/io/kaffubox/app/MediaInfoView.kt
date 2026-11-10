// 将媒体容器和各类轨道整理为便于阅读的中文信息
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import java.util.Locale

fun JsonObject.mediaText(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeUnless { it.isBlank() || it == "N/A" }
fun mediaNumber(value: Double): String = "%.3f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')
fun mediaDuration(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0) return "未知"
    val hours = seconds.toLong() / 3600; val minutes = seconds.toLong() / 60 % 60
    return (if (hours > 0) hours.toString() + " 小时 " else "") + (if (minutes > 0 || hours > 0) minutes.toString() + " 分 " else "") + mediaNumber(seconds % 60) + " 秒"
}
fun mediaFrameRate(value: String?): Double? {
    val parts = value?.split('/') ?: return null
    val rate = if (parts.size == 2) (parts[0].toDoubleOrNull() ?: return null) / (parts[1].toDoubleOrNull() ?: return null) else value.toDoubleOrNull() ?: return null
    return rate.takeIf { it.isFinite() && it > 0 }
}
fun languageName(code: String?): String = when (code?.lowercase()) {
    "chi", "zho", "zh", "zh-cn", "zh-hans" -> "中文"; "zh-tw", "zh-hant" -> "繁体中文"; "eng", "en" -> "英语"; "jpn", "ja" -> "日语"; "kor", "ko" -> "韩语"; "fra", "fre", "fr" -> "法语"; "deu", "ger", "de" -> "德语"; "spa", "es" -> "西班牙语"; null, "und" -> "未标注语言"; else -> code
}
data class MediaSection(val title: String, val rows: List<Pair<String, String>>)
fun mediaSections(info: JsonObject): List<MediaSection> = buildList {
    val format = info["format"] as? JsonObject ?: JsonObject(emptyMap())
    val streams = (info["streams"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    add(MediaSection("文件概览", buildList {
        format.mediaText("filename")?.let { add("文件名" to it.replace('\\', '/').substringAfterLast('/')) }
        format.mediaText("format_long_name")?.let { add("容器格式" to it) }
        val duration = format.mediaText("duration")?.toDoubleOrNull() ?: streams.mapNotNull { it.mediaText("duration")?.toDoubleOrNull() }.maxOrNull()
        duration?.let { add("时长" to mediaDuration(it)) }
        format.mediaText("size")?.toLongOrNull()?.let { add("文件大小" to readableBytes(it)) }
        format.mediaText("bit_rate")?.toDoubleOrNull()?.let { add("总码率" to (mediaNumber(it / 1000) + " kbps")) }
        add("轨道" to (streams.count { it.mediaText("codec_type") == "video" }.toString() + " 条视频 · " + streams.count { it.mediaText("codec_type") == "audio" } + " 条音频 · " + streams.count { it.mediaText("codec_type") == "subtitle" } + " 条字幕"))
    }))
    val counts = mutableMapOf<String, Int>()
    streams.forEach { stream ->
        val type = stream.mediaText("codec_type") ?: "unknown"
        val index = (counts[type] ?: 0) + 1; counts[type] = index
        val kind = when (type) { "video" -> "视频"; "audio" -> "音频"; "subtitle" -> "字幕"; else -> "其他轨道" }
        val codec = stream.mediaText("codec_name")?.uppercase(Locale.ROOT) ?: "未知编码"
        val tags = stream["tags"] as? JsonObject ?: JsonObject(emptyMap())
        add(MediaSection(kind + " " + index + " · " + codec, buildList {
            stream.mediaText("codec_long_name")?.let { add("编码" to it) }
            if (type == "video") {
                val width = stream.mediaText("width"); val height = stream.mediaText("height")
                if (width != null && height != null) add("分辨率" to "$width × $height px")
                (mediaFrameRate(stream.mediaText("avg_frame_rate")) ?: mediaFrameRate(stream.mediaText("r_frame_rate")))?.let { add("平均帧率" to (mediaNumber(it) + " 帧/秒")) }
                stream.mediaText("display_aspect_ratio")?.let { add("显示比例" to it) }
                stream.mediaText("pix_fmt")?.let { add("像素格式" to it) }
            }
            if (type == "audio") {
                stream.mediaText("sample_rate")?.toDoubleOrNull()?.let { add("采样率" to (mediaNumber(it / 1000) + " kHz")) }
                stream.mediaText("channels")?.toIntOrNull()?.let { add("声道" to when (it) { 1 -> "单声道"; 2 -> "立体声"; else -> "$it 声道" }) }
            }
            stream.mediaText("bit_rate")?.toDoubleOrNull()?.let { add("码率" to (mediaNumber(it / 1000) + " kbps")) }
            if (type in setOf("audio", "subtitle")) add("语言" to languageName(tags.mediaText("language")))
            tags.mediaText("title")?.let { add("名称" to it) }
            val disposition = stream["disposition"] as? JsonObject
            if (disposition?.get("default")?.jsonPrimitive?.intOrNull == 1) add("播放选择" to "默认轨道")
        }))
    }
}
fun mediaInfoText(info: JsonObject): String = mediaSections(info).joinToString("\n\n") { section -> section.title + "\n" + section.rows.joinToString("\n") { it.first + "：" + it.second } }

@Composable
fun MediaInfoView(info: JsonObject) {
    mediaSections(info).forEachIndexed { index, section ->
        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text(section.title, style = MaterialTheme.typography.titleMedium)
        section.rows.forEach { (label, value) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(label, Modifier.width(104.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(value, Modifier.weight(1f)) }
        } }
    }
}
