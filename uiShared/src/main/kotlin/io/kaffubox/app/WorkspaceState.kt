// 保存工作台共享输入、会话内请求记录和工具预设，并通过现有任务中心提交批处理
package io.kaffubox.app

import androidx.compose.runtime.*
import io.kaffubox.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Path

enum class WorkspaceKind(val title: String, val description: String) {
    Media("媒体工作台", "视频、音频、动图与字幕"), Image("图像工作台", "批量处理、取色、拼接与识别"),
    Files("文件与传输工作台", "文件整理、校验、压缩与局域网传输"), Text("文本与文档工作台", "共享编辑区、格式转换与连续处理"),
    Developer("开发与网络工作台", "请求调试、网络诊断与开发工具"), Science("科学计算工作台", "计算器、单位、矩阵、向量、复数与绘图")
}

fun workspaceOf(tool: UiTool): WorkspaceKind? = when {
    tool.id in setOf("developer.json", "developer.yamlToJson", "developer.jsonToYaml", "developer.tomlToJson", "developer.xmlFormat") -> WorkspaceKind.Text
    else -> when (tool.workbench) {
        Workbench.Media, Workbench.Format -> WorkspaceKind.Media; Workbench.Image -> WorkspaceKind.Image
        Workbench.File, Workbench.Transfer -> WorkspaceKind.Files; Workbench.Text, Workbench.Document -> WorkspaceKind.Text
        Workbench.Developer, Workbench.Network -> WorkspaceKind.Developer; Workbench.Science -> WorkspaceKind.Science; else -> null
    }
}

fun textInputKey(tool: UiTool): String? = if (tool.id == "developer.encoding") "__encodingInput" else tool.fields.firstOrNull { it.key in setOf("text", "html", "token", "left") && it.kind in setOf(FieldKind.Paragraph, FieldKind.Text) }?.key
fun resultFiles(result: JsonElement): List<FileRef> = when (result) {
    is JsonObject -> runCatching { if (result["id"] is JsonPrimitive && result["name"] is JsonPrimitive) CoreJson.decodeFromJsonElement(FileRef.serializer(), result) else null }.getOrNull()?.let(::listOf) ?: result.values.flatMap(::resultFiles)
    is JsonArray -> result.flatMap(::resultFiles); else -> emptyList()
}

@Serializable
data class ToolPreset(val name: String, val toolId: String, val values: Map<String, String>)
data class RequestRecord(val whenMillis: Long, val arguments: JsonObject)
fun renameSignature(state: ToolFormState) = state.values.filterKeys { !it.startsWith("__") }.toSortedMap().toString() + state.files.toSortedMap().toString()
fun sensitiveOption(key: String) = Regex("(?i)token|password|secret|authorization|cookie|credential|api[_-]?key").containsMatchIn(key)
class WorkspaceSession {
    var files by mutableStateOf<List<FileRef>>(emptyList())
    var text by mutableStateOf("")
    var operation by mutableStateOf<String?>(null)
}
class WorkspaceState {
    val scrollOffsets = linkedMapOf<String, Int>()
    var directoryFilter by mutableStateOf("all")
    var returnPage = AppPage.Tools
    var returnSearch = ""
    var returnCategory: Workbench? = null
    fun rememberOrigin(controller: AppController) {
        if (controller.page != AppPage.Tool) { returnPage = controller.page; returnSearch = controller.search; returnCategory = controller.selectedCategory }
    }
    var selected by mutableStateOf<WorkspaceKind?>(null)
    val sessions = WorkspaceKind.entries.associateWith { WorkspaceSession() }
    val requestHistory = mutableStateListOf<RequestRecord>()
    val mediaCache = mutableMapOf<String, JsonObject>()
    fun capture(controller: AppController) {
        val tool = controller.selectedTool ?: return
        if (tool.id == "transfer.scanCode") return
        val session = sessions[workspaceOf(tool)] ?: return
        val state = controller.form(tool)
        val field = tool.fields.firstOrNull { it.kind in setOf(FieldKind.File, FieldKind.Files) }
        field?.let {
            val current = state.files[it.key].orEmpty()
            if (it.kind == FieldKind.Files || tool.batch || session.files.isEmpty() || current.firstOrNull()?.id != session.files.firstOrNull()?.id) session.files = current
        }
        val separateInputs = tool.fields.filter { it.kind == FieldKind.File }
        if (separateInputs.size > 1) session.files = separateInputs.flatMap { state.files[it.key].orEmpty() }
        textInputKey(tool)?.let { session.text = if (tool.id == "developer.encoding") controller.encoding.input else state.values[it].orEmpty() }
    }
    fun bind(controller: AppController, tool: UiTool, input: List<FileRef>) {
        val group = workspaceOf(tool) ?: return
        selected = group
        val session = sessions.getValue(group); val state = controller.form(tool)
        session.operation = tool.id
        if (tool.id == "transfer.scanCode") return
        if (input.isNotEmpty()) session.files = input
        val field = tool.fields.firstOrNull { it.kind in setOf(FieldKind.File, FieldKind.Files) }
        if (field != null && session.files.isNotEmpty()) {
            val refs = if (field.kind == FieldKind.Files || tool.batch) session.files else session.files.take(1)
            if (state.files[field.key] != refs) { state.files[field.key] = refs; state.resetResult() }
        }
        val separateInputs = tool.fields.filter { it.kind == FieldKind.File }
        if (separateInputs.size > 1 && session.files.size >= separateInputs.size) separateInputs.forEachIndexed { index, item -> state.files[item.key] = listOf(session.files[index]) }
        textInputKey(tool)?.let { if (state.values[it] != session.text) { state.values[it] = session.text; state.resetResult() } }
        if (tool.id == "developer.encoding") controller.encoding.input = session.text
    }
}

fun AppController.openWorkspace(group: WorkspaceKind) {
    workspace.capture(this)
    workspace.directoryFilter = group.name
    navigate(AppPage.Tools)
}

fun AppController.sendText(tool: UiTool, value: String) {
    workspace.capture(this)
    workspace.sessions[workspaceOf(tool)]?.text = value
    openTool(tool)
    textInputKey(tool)?.let { form(tool).values[it] = value; form(tool).resetResult() }
    if (tool.id == "developer.encoding") encoding.input = value
}

fun AppController.enqueue(tool: UiTool, arguments: JsonObject, state: ToolFormState): String {
    val descriptor = host.registry.list().first { it.id == tool.id }
    val root = if (!isTouchDevice && !state.values["__outputDirectory"].isNullOrBlank()) Path.of(state.values.getValue("__outputDirectory")) else files.outputDirectory
    val output = if (Capability.FileWrite in descriptor.capabilities && !tool.id.startsWith("transfer.") && root != null && !(tool.id == "file.rename" && state.values["mode"] == "rename")) OutputSession(files, root, state.values["__overwritePolicy"] ?: "unique", state.files.values.flatten()) else null
    return try { submitWork(tool.id, arguments, output).also { state.taskIds += it; state.selectedTask = it } } catch (failure: Throwable) { output?.close(); throw failure }
}

fun runWorkspaceTool(controller: AppController, tool: UiTool, queued: Boolean) {
    val state = controller.form(tool)
    try {
        state.files.keys.toList().forEach { key -> state.files[key] = state.files[key].orEmpty().map { controller.files.resolve(it.id).ref } }
        controller.workspace.capture(controller)
        val field = tool.fields.firstOrNull { it.kind == FieldKind.File }
        val inputs = field?.let { state.files[it.key] }.orEmpty()
        val batch: List<FileRef?> = if (tool.batch && inputs.size > 1) inputs else listOf(null)
        val requests = batch.map { controller.arguments(tool, state, field?.key, it) }
        state.error = null; state.result = null
        if (controller.preferences.rememberParameters) {
            val keys = tool.fields.filter { !sensitiveOption(it.key) && it.kind in setOf(FieldKind.Choice, FieldKind.Toggle, FieldKind.Integer, FieldKind.Number) }.map { it.key }.toSet()
            controller.updatePreferences(controller.preferences.copy(toolOptions = controller.preferences.toolOptions + (tool.id to state.values.filterKeys { it in keys })))
        }
        if (tool.id == "network.http") {
            controller.workspace.requestHistory.add(0, RequestRecord(System.currentTimeMillis(), requests.first()))
            while (controller.workspace.requestHistory.size > 30) controller.workspace.requestHistory.removeAt(controller.workspace.requestHistory.lastIndex)
        }
        if (queued || requests.size > 1) {
            state.taskIds.clear()
            requests.forEach { controller.enqueue(tool, it, state) }
            controller.message = "已加入 ${requests.size} 个任务"
        } else {
            state.job?.cancel(); state.busy = true
            state.job = controller.launchWork {
                try { state.result = controller.execute(tool.id, requests.first()) }
                catch (_: CancellationException) { }
                catch (failure: Exception) { state.error = UiText.failure(failure) }
                finally { state.busy = false }
            }
        }
        state.values["__view"] = "result"
    } catch (failure: Exception) { state.error = UiText.failure(failure); controller.report(failure, tool.title) }
}

fun compatibleFileTools(tools: List<UiTool>, refs: List<FileRef>): List<UiTool> = tools.filter { tool ->
    tool.fields.any { it.kind in setOf(FieldKind.File, FieldKind.Files) } &&
        (refs.size <= 1 || tool.batch || tool.fields.any { it.kind == FieldKind.Files } || tool.fields.count { it.kind == FieldKind.File } >= refs.size) && when {
        tool.id == "transfer.scanCode" -> refs.all { it.looksLikeImage() }
        tool.id in setOf("media.extractAudio", "media.videoToGif", "media.extractFrame", "media.extractSubtitles", "media.edit") -> refs.all { it.mimeType?.startsWith("video/") == true || it.name.substringAfterLast('.').lowercase() in setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "mpeg", "mpg", "ts") }
        else -> when (workspaceOf(tool)) {
        WorkspaceKind.Image -> refs.all { it.looksLikeImage() }
        WorkspaceKind.Media -> refs.all { it.mimeType?.startsWith("audio/") == true || it.mimeType?.startsWith("video/") == true || it.name.substringAfterLast('.').lowercase() in setOf("mp4", "mkv", "webm", "mov", "avi", "mp3", "aac", "m4a", "flac", "wav", "ogg", "gif", "webp", "apng") }
        else -> true
        }
    }
}
