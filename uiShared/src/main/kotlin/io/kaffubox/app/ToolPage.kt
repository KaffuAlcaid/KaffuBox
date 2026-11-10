// 渲染工具参数、可排序文件列表、图片高级处理选项及真实执行结果
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.kaffubox.core.*
import kotlin.math.roundToInt

@Composable
fun ToolPage(controller: AppController) {
    val tool = controller.selectedTool
    if (tool == null) { Text("工具暂不可用，请检查插件是否已启用"); return }
    PageHeader(tool.title, back = { controller.navigate(AppPage.Tools) }) {
        IconButton(onClick = { controller.favorite(tool.id) }) { Icon(if (tool.id in controller.preferences.favorites) Icons.Outlined.Star else Icons.Outlined.StarBorder, "收藏") }
    }
    Text(tool.summary, Modifier.padding(bottom = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    when (tool.id) {
        "science.calculate" -> CalculatorPage(controller)
        "science.unit" -> UnitPage(controller)
        "science.matrix" -> MatrixPage(controller)
        "developer.encoding" -> EncodingPage(controller)
        "daily.countdown" -> CountdownPage(controller)
        "image.sampleColor" -> ColorPickerPage(controller, tool)
        else -> GenericToolPage(controller, tool)
    }
}

@Composable
private fun GenericToolPage(controller: AppController, tool: UiTool) {
    val state = controller.form(tool)
    val needsEngine = tool.id.startsWith("media.") || tool.id == "format.mediaConvert"
    val unavailable = needsEngine && !controller.mediaToolsAvailable
    if (tool.id in setOf("media.extractFrame", "media.extractSubtitles")) PrepareMediaInfo(controller, tool, state)
    val subtitleUnavailable = tool.id == "media.extractSubtitles" && (state.probing || subtitleChoices(state.mediaInfo).none { it.supported && it.index.toString() == state.values["index"] })
    val frameTime = state.values["start"]?.toDoubleOrNull()
    val frameDuration = (state.mediaInfo?.get("format") as? kotlinx.serialization.json.JsonObject)?.mediaText("duration")?.toDoubleOrNull()
    val frameUnavailable = tool.id == "media.extractFrame" && (state.files["file"].isNullOrEmpty() || frameTime == null || !frameTime.isFinite() || frameTime < 0 || (frameDuration != null && frameTime > (frameDuration - 0.05).coerceAtLeast(0.0)))
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (unavailable) Panel(Modifier.fillMaxWidth()) {
            InlineError("未找到媒体引擎")
            Text("将 ffmpeg.exe 和 ffprobe.exe 放入程序旁的 Engines 文件夹，或加入系统 PATH，然后重新启动")
        }
        Panel(Modifier.fillMaxWidth()) {
            tool.fields.forEach { field ->
                if (!field.isVisible(state.values)) return@forEach
                if (tool.id == "media.extractSubtitles" && field.key == "index") { SubtitleTrackField(state); return@forEach }
                if (tool.id == "media.extractFrame" && field.key == "start") { FrameTimeField(controller, state); return@forEach }
                when (field.kind) {
                    FieldKind.File, FieldKind.Files -> FileField(controller, tool, field, state)
                    FieldKind.Toggle -> ToggleRow(field.label, state.values[field.key].toBoolean(), { state.values[field.key] = it.toString() })
                    FieldKind.Choice -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChoiceField(field.label, state.values[field.key].orEmpty(), field.choices, { state.values[field.key] = it })
                        if (field.hint.isNotEmpty()) Text(field.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        if (field.sliderRange != null) {
                            IntegerSliderField(field, state)
                            return@forEach
                        }
                        val multiline = field.kind in setOf(FieldKind.Paragraph, FieldKind.Json, FieldKind.Lines)
                        OutlinedTextField(state.values[field.key].orEmpty(), { state.values[field.key] = it },
                            Modifier.fillMaxWidth().then(if (multiline) Modifier.heightIn(min = 150.dp, max = 300.dp) else Modifier),
                            label = { Text(field.label + if (field.required) " *" else "") },
                            supportingText = if (field.hint.isNotEmpty()) ({ Text(field.hint) }) else null,
                            singleLine = !multiline)
                    }
                }
            }
            if (tool.id in setOf("image.transform", "media.edit")) ImageOptions(state, tool.id == "media.edit")
            InlineError(state.error)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { controller.runForm(tool) }, enabled = !state.busy && !unavailable && !subtitleUnavailable && !frameUnavailable) { Text(if (state.busy) "处理中" else tool.action) }
                if (state.busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); TextButton(onClick = { state.job?.cancel() }) { Text("取消") } }
                if (tool.queued) TextButton(onClick = { controller.navigate(AppPage.Tasks) }) { Text("查看任务") }
            }
            if (tool.queued) Text(controller.outputDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.selectedTask?.let { id -> controller.tasks.firstOrNull { it.id == id }?.let { task -> TaskCard(controller, task, showResult = true) } }
        state.result?.let { ResultView(controller, it, tool.id) }
    }
}

@Composable
private fun IntegerSliderField(field: ToolField, state: ToolFormState) {
    val range = field.sliderRange ?: return
    val default = field.initial.toIntOrNull() ?: range.first
    val value = (state.values[field.key]?.toIntOrNull() ?: default).coerceIn(range)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(value.toString(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { state.values[field.key] = default.toString() }, enabled = value != default) { Text("恢复默认 " + default) }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { state.values[field.key] = it.roundToInt().coerceIn(range).toString() },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = field.label; stateDescription = value.toString() }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(range.first.toString() + "  " + field.sliderLabels.first, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(range.last.toString() + "  " + field.sliderLabels.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (field.hint.isNotEmpty()) Text(field.hint, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FileField(controller: AppController, tool: UiTool, field: ToolField, state: ToolFormState, showPreview: Boolean = true) {
    val refs = state.files[field.key].orEmpty()
    val multiple = field.kind == FieldKind.Files || tool.batch
    FileDropArea({ controller.acceptFiles(it, tool, field) }, Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { controller.selectFiles(tool, field) }) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text(if (refs.isEmpty()) "选择文件" else if (multiple) "添加文件" else "更换文件") }
        }
        if (refs.isEmpty()) Surface(onClick = { controller.selectFiles(tool, field) }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.UploadFile, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                Text(if (controller.supportsFileDrop) "点击选择文件，或拖到这里" else "点击选择文件", Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        refs.forEachIndexed { index, ref ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text((index + 1).toString(), Modifier.width(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) { Text(ref.name); Text(readableBytes(ref.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (refs.size > 1) {
                    IconButton(onClick = { val copy = refs.toMutableList(); java.util.Collections.swap(copy, index, index - 1); state.files[field.key] = copy; state.resetResult() }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "上移") }
                    IconButton(onClick = { val copy = refs.toMutableList(); java.util.Collections.swap(copy, index, index + 1); state.files[field.key] = copy; state.resetResult() }, enabled = index < refs.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "下移") }
                }
                IconButton(onClick = { state.files[field.key] = refs.filterIndexed { i, _ -> i != index }; state.resetResult() }) { Icon(Icons.Outlined.Close, "移除文件") }
            }
        }
        if (refs.size > 1) TextButton(onClick = { state.files[field.key] = emptyList(); state.resetResult() }) { Text("清空文件列表") }
        if (showPreview && refs.size == 1 && refs.first().looksLikeImage()) ImageFilePreview(controller, refs.first())
        if (refs.isNotEmpty() && controller.supportsFileDrop) Text(if (multiple) "继续拖入可添加文件，重复文件会自动跳过" else "拖入另一份文件即可更换", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    }
}

@Composable
private fun ImageOptions(state: ToolFormState, video: Boolean = false) {
    HorizontalDivider()
    Text("局部处理", style = MaterialTheme.typography.titleSmall)
    (if (video) listOf("crop" to "裁剪画面") else listOf("crop" to "裁剪", "mosaic" to "马赛克", "watermark" to "文字水印")).forEach { (section, title) ->
        ToggleRow(title, state.values[section + "Enabled"] == "true", { state.values[section + "Enabled"] = it.toString() })
        if (state.values[section + "Enabled"] == "true") {
            val fields = if (section == "watermark") listOf("Text" to "水印文字", "X" to "横坐标", "Y" to "基线纵坐标", "Size" to "字号", "Color" to "颜色 #AARRGGBB")
            else listOf("X" to "左上角 X", "Y" to "左上角 Y", "Width" to "宽度", "Height" to "高度") + if (section == "mosaic") listOf("Block" to "色块大小") else emptyList()
            fields.forEach { (suffix, label) ->
                val fallback = when (suffix) { "X" -> "0"; "Y" -> if (section == "watermark") "40" else "0"; "Size" -> "24"; "Color" -> "#CCFFFFFF"; "Block" -> "12"; else -> "" }
                OutlinedTextField(state.values[section + suffix] ?: fallback, { state.values[section + suffix] = it }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
            }
        }
    }
}
