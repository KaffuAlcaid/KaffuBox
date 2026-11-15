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

private val DedicatedToolPages = setOf("science.calculate", "science.unit", "science.matrix", "developer.encoding", "daily.countdown", "image.sampleColor", "transfer.stopShare", "transfer.scanCode", "transfer.share", "transfer.receive")

@Composable
fun FocusedToolPage(controller: AppController) {
    val tool = controller.selectedTool
    val hasAction = tool != null && tool.id !in DedicatedToolPages && !(tool.provider == "builtin" && workspaceOf(tool) == WorkspaceKind.Text && textInputKey(tool) != null)
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = controller.isTouchDevice || maxWidth < 940.dp
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { ScrollPage(scroll = navigationScrollState(controller)) { ToolPage(controller) } }
        if (hasAction && tool != null) {
            val state = controller.form(tool)
            val resultMode = compact && state.values["__view"] == "result" && (state.result != null || state.taskIds.isNotEmpty() || state.busy)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    if (resultMode) state.values["__view"] = "input"
                    else { controller.runForm(tool); if (state.error == null && (state.busy || state.taskIds.isNotEmpty() || state.result != null)) state.values["__view"] = "result" }
                }, enabled = resultMode || canRunTool(controller, tool, state), modifier = if (compact) Modifier.weight(1f) else Modifier.widthIn(min = 180.dp)) { Text(if (resultMode) "调整参数" else if (state.busy) "处理中" else tool.action) }
                if (state.busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); TextButton(onClick = { state.job?.cancel() }) { Text("取消") } }
                if (tool.queued) TextButton(onClick = { controller.navigate(AppPage.Tasks) }) { Text("查看任务") }
            }
        }
    }
    }
}

private fun canRunTool(controller: AppController, tool: UiTool, state: ToolFormState): Boolean {
    if (state.busy || controller.findTool(tool.id) == null) return false
    if (tool.id == "file.rename" && state.values["__renameApproved"] != renameSignature(state)) return false
    if (tool.id == "media.extractSubtitles" && (state.probing || subtitleChoices(state.mediaInfo).none { it.supported && it.index.toString() == state.values["index"] })) return false
    if (tool.id == "media.extractFrame") {
        val time = state.values["start"]?.toDoubleOrNull()
        val duration = (state.mediaInfo?.get("format") as? kotlinx.serialization.json.JsonObject)?.mediaText("duration")?.toDoubleOrNull()
        if (state.files["file"].isNullOrEmpty() || time == null || !time.isFinite() || time < 0 || (duration != null && time > (duration - 0.05).coerceAtLeast(0.0))) return false
    }
    return tool.fields.filter { it.required && it.kind in setOf(FieldKind.File, FieldKind.Files) }.all { state.files[it.key].orEmpty().isNotEmpty() }
}

@Composable
fun ToolPage(controller: AppController) {
    val tool = controller.selectedTool
    if (tool == null) {
        PageHeader(controller.workspace.selected?.title ?: "工具", back = { controller.navigate(AppPage.Tools) })
        if (controller.workspace.selected == WorkspaceKind.Media && controller.selectedToolId == null && !controller.isTouchDevice) {
            InlineError("没有找到可用的媒体引擎")
            Text("请将 ffmpeg.exe 和 ffprobe.exe 放入程序旁的 Engines 文件夹，或加入系统 PATH 后重新启动")
            Text("FFmpeg 用于转换与编辑，FFprobe 用于媒体信息与时间范围预览")
        } else Text("工具暂不可用，请检查插件是否已启用")
        return
    }
    ToolHeader(controller, tool)
    when (tool.id) {
        "science.calculate" -> CalculatorPage(controller)
        "science.unit" -> UnitPage(controller)
        "science.matrix" -> MatrixPage(controller)
        "developer.encoding" -> EncodingPage(controller)
        "daily.countdown" -> CountdownPage(controller)
        "image.sampleColor" -> ColorPickerPage(controller, tool)
        "transfer.stopShare" -> TransferManagerPage(controller)
        "transfer.scanCode" -> TransferScanPage(controller, tool)
        "transfer.share", "transfer.receive" -> TransferSetupPage(controller, tool)
        else -> if (tool.provider == "builtin" && workspaceOf(tool) == WorkspaceKind.Text && textInputKey(tool) != null) TextWorkspacePage(controller, tool) else GenericToolPage(controller, tool)
    }
}

@Composable
private fun GenericToolPage(controller: AppController, tool: UiTool) {
    val state = controller.form(tool)
    val needsEngine = tool.id.startsWith("media.") || tool.id == "format.mediaConvert"
    val unavailable = controller.findTool(tool.id) == null
    if (needsEngine && controller.findTool("media.probe") != null) PrepareMediaInfo(controller, tool, state)
    ToolWorkspaceColumns(controller, state, input = {
        if (unavailable) Panel(Modifier.fillMaxWidth()) {
            InlineError("未找到媒体引擎")
            Text("将 ffmpeg.exe 和 ffprobe.exe 放入程序旁的 Engines 文件夹，或加入系统 PATH，然后重新启动")
        }
        Panel(Modifier.fillMaxWidth()) {
            ToolFields(controller, tool, state)
            if (needsEngine && state.mediaInfo != null) {
                var showInfo by remember(tool.id) { mutableStateOf(false) }
                TextButton(onClick = { showInfo = !showInfo }) { Text(if (showInfo) "收起媒体信息" else "查看当前输入的媒体信息") }
                if (showInfo) MediaInfoView(state.mediaInfo!!)
            }
            if (tool.fields.any { it.key == "duration" }) MediaRangeControls(controller, state)
            if (tool.id == "file.rename") RenamePreview(controller, tool, state)
            if (tool.id == "image.transform") Text("缩放、裁剪、水印和马赛克在一次像素处理流程内组合，保存时移除 EXIF、GPS 等原始元数据", style = MaterialTheme.typography.bodySmall)
            OutputControls(controller, tool, state)
            PresetControls(controller, tool, state)
            InlineError(state.error)
        }
        if (tool.id == "network.http") HttpRequestHistory(controller, tool, state)
        if (tool.workbench == Workbench.Transfer) TextButton(onClick = { controller.findTool("transfer.stopShare")?.let { controller.openTool(it) } }) { Text("管理文件分享任务") }
    }, output = {
        BatchResults(controller, state, tool)
    })
}

@Composable
private fun ToolWorkspaceColumns(controller: AppController, state: ToolFormState, input: @Composable () -> Unit, output: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (!controller.isTouchDevice && maxWidth >= 940.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { input() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { output() }
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val hasOutput = state.result != null || state.taskIds.isNotEmpty() || state.busy
            val resultMode = hasOutput && state.values["__view"] == "result"
            if (hasOutput) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !resultMode, onClick = { state.values["__view"] = "input" }, label = { Text("参数") })
                FilterChip(selected = resultMode, onClick = { state.values["__view"] = "result" }, label = { Text("结果") })
            }
            if (resultMode) { InlineError(state.error); output() } else input()
        }
    }
}

@Composable
fun ToolFields(controller: AppController, tool: UiTool, state: ToolFormState, exclude: Set<String> = emptySet()) {
    val advancedKeys = when {
        tool.id == "image.transform" -> setOf("rotate", "targetBytes", "allowResize", "grayscale", "name")
        tool.id == "network.http" -> setOf("headers", "timeoutMillis")
        workspaceOf(tool) == WorkspaceKind.Media -> setOf("videoEncoder", "audioEncoder", "height", "audioBitrate", "sampleRate", "channels", "volumeDb", "normalizeAudio", "loudness", "stripMetadata", "name")
        else -> emptySet()
    }
    val fields = tool.fields.filter { it.key !in exclude && it.isVisible(state.values) && !(it.key in setOf("start", "duration") && tool.fields.any { item -> item.key == "duration" }) }
    var advanced by remember(tool.id) { mutableStateOf(false) }
    var effects by remember(tool.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        fields.filter { it.key !in advancedKeys }.forEach { RenderToolField(controller, tool, it, state) }
        val additional = fields.filter { it.key in advancedKeys }
        if (additional.isNotEmpty()) {
            val configured = additional.count { state.values[it.key].orEmpty().isNotBlank() && state.values[it.key] != it.initial }
            TextButton(onClick = { advanced = !advanced }) { Text((if (advanced) "收起高级参数" else "高级参数") + if (configured > 0) "（已调整 $configured 项）" else "") }
            if (advanced) additional.forEach { RenderToolField(controller, tool, it, state) }
        }
        if (tool.id in setOf("image.transform", "media.edit")) {
            val enabled = listOf("cropEnabled", "watermarkEnabled", "mosaicEnabled").count { state.values[it] == "true" }
            TextButton(onClick = { effects = !effects }) { Text((if (effects) "收起局部处理" else "裁剪、水印与马赛克") + if (enabled > 0) "（已启用 $enabled 项）" else "") }
            if (effects) ImageOptions(state, tool.id == "media.edit")
        }
    }
}

@Composable
private fun RenderToolField(controller: AppController, tool: UiTool, field: ToolField, state: ToolFormState) {
    if (tool.id == "media.extractSubtitles" && field.key == "index") { SubtitleTrackField(state); return }
    if (tool.id == "media.extractFrame" && field.key == "start") { FrameTimeField(controller, state); return }
    when (field.kind) {
        FieldKind.File, FieldKind.Files -> FileField(controller, tool, field, state)
        FieldKind.Toggle -> ToggleRow(field.label, state.values[field.key].toBoolean(), { state.values[field.key] = it.toString() })
        FieldKind.Choice -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceField(field.label, state.values[field.key].orEmpty(), field.choices, { state.values[field.key] = it })
            if (field.hint.isNotEmpty()) Text(field.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            if (field.sliderRange != null) { IntegerSliderField(field, state); return }
            val multiline = field.kind in setOf(FieldKind.Paragraph, FieldKind.Json, FieldKind.Lines)
            OutlinedTextField(state.values[field.key].orEmpty(), { state.values[field.key] = it },
                Modifier.fillMaxWidth().then(if (multiline) Modifier.heightIn(min = 150.dp, max = 300.dp) else Modifier),
                label = { Text(field.label + if (field.required) " *" else "") },
                supportingText = if (field.hint.isNotEmpty()) ({ Text(field.hint) }) else null,
                singleLine = !multiline)
        }
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
    val refs = state.files[field.key].orEmpty().map { runCatching { controller.files.resolve(it.id).ref }.getOrDefault(it) }
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
