// 自动识别字幕轨道并提供视频帧预览、时间轴定位与精确时间输入
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.kaffubox.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

data class SubtitleChoice(val index: Int, val label: String, val preferred: Boolean, val supported: Boolean, val imageBased: Boolean)
fun subtitleChoices(info: JsonObject?): List<SubtitleChoice> = (info?.get("streams") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.mediaText("codec_type") == "subtitle" }.mapIndexed { index, stream ->
    val tags = stream["tags"] as? JsonObject ?: JsonObject(emptyMap())
    val codec = stream.mediaText("codec_name").orEmpty()
    val supported = codec in setOf("subrip", "ass", "ssa", "mov_text", "webvtt", "text", "sami", "microdvd", "subviewer", "subviewer1", "jacosub", "mpl2", "vplayer", "realtext", "pjs", "stl", "srt")
    val imageBased = codec in setOf("dvd_subtitle", "dvb_subtitle", "hdmv_pgs_subtitle", "xsub")
    SubtitleChoice(index, "字幕 " + (index + 1) + " · " + languageName(tags.mediaText("language")) + tags.mediaText("title")?.let { " · " + it }.orEmpty() + " · " + codec.uppercase() + if (supported) "" else if (imageBased) "（图片字幕）" else "（暂不支持）", (stream["disposition"] as? JsonObject)?.get("default")?.jsonPrimitive?.intOrNull == 1, supported, imageBased)
}

@Composable
fun PrepareMediaInfo(controller: AppController, tool: UiTool, state: ToolFormState) {
    val ref = state.files["file"]?.firstOrNull()
    LaunchedEffect(ref?.id) {
        state.mediaInfo = null; state.mediaError = null
        if (ref == null) { state.probing = false; return@LaunchedEffect }
        state.probing = true
        try {
            val info = controller.execute("media.probe", jsonObject("file" to ref.id)).jsonObject
            state.mediaInfo = info
            if (tool.id == "media.extractSubtitles") {
                val choices = subtitleChoices(info).filter { it.supported }
                state.values["index"] = (choices.firstOrNull { it.preferred } ?: choices.firstOrNull())?.index?.toString().orEmpty()
            }
        } catch (_: CancellationException) { } catch (failure: Exception) { state.mediaError = UiText.failure(failure) }
        finally { state.probing = false }
    }
}

@Composable
fun SubtitleTrackField(state: ToolFormState) {
    val choices = subtitleChoices(state.mediaInfo)
    val supported = choices.filter { it.supported }
    when {
        state.probing -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在查找字幕") }
        state.mediaError != null -> InlineError(state.mediaError)
        state.files["file"].isNullOrEmpty() -> Text("导入视频后自动查找字幕轨道", color = MaterialTheme.colorScheme.onSurfaceVariant)
        choices.isEmpty() -> Text("没有找到内嵌字幕，直接印在画面上的文字无法在这里提取", color = MaterialTheme.colorScheme.onSurfaceVariant)
        supported.isEmpty() -> Text(if (choices.any { it.imageBased }) "这个文件包含图片字幕，导出为 SRT 需要先进行文字识别" else "当前字幕编码暂不支持导出为 SRT", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {
            ChoiceField("选择字幕", state.values["index"].orEmpty(), supported.map { it.index.toString() to it.label }, { state.values["index"] = it })
            Text("自动优先选择默认字幕，也可按语言或名称切换，导出格式为 SRT", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


@Composable
fun FrameTimeField(controller: AppController, state: ToolFormState) {
    val ref = state.files["file"]?.firstOrNull()
    var bitmap by remember(ref?.id) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(ref?.id) { mutableStateOf<String?>(null) }
    var loading by remember(ref?.id) { mutableStateOf(false) }
    val duration = (state.mediaInfo?.get("format") as? JsonObject)?.mediaText("duration")?.toDoubleOrNull()
    val seconds = state.values["start"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    val end = duration?.takeIf { it.isFinite() && it > 0 }?.let { (it - 0.05).coerceAtLeast(0.0) }
    LaunchedEffect(ref?.id, seconds) {
        if (ref == null || seconds == null) return@LaunchedEffect
        delay(180); loading = true; error = null
        try { bitmap = videoPreview(controller, ref, seconds) }
        catch (_: CancellationException) { } catch (failure: Exception) { error = UiText.failure(failure) }
        finally { loading = false }
    }
    bitmap?.let { ImageCanvas(it, Modifier.fillMaxWidth().height(270.dp).semantics { contentDescription = "视频画面预览" }) }
    if (loading || state.probing) LinearProgressIndicator(Modifier.fillMaxWidth())
    InlineError(error ?: state.mediaError)
    if (end != null && end > 0) {
        Text("拖动时间轴预览画面", style = MaterialTheme.typography.titleSmall)
        Slider(value = (seconds ?: 0.0).coerceIn(0.0, end).toFloat(), onValueChange = { state.values["start"] = mediaNumber(it.toDouble()) }, valueRange = 0f..end.toFloat(), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "视频定位时间轴" })
        Text(mediaDuration(seconds ?: 0.0) + " / " + mediaDuration(duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutlinedTextField(state.values["start"].orEmpty(), { state.values["start"] = it }, Modifier.fillMaxWidth(), label = { Text("精确定位（秒）") }, singleLine = true,
        isError = seconds == null || (end != null && seconds > end), supportingText = { Text(if (seconds == null) "请输入不小于 0 的秒数" else if (end != null && seconds > end) "定位时间应小于视频时长" else "可输入小数，导出的图片使用视频原始分辨率") })
}
