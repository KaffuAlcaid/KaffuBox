// 组织六个工作台的连续操作入口、共享输入、参数预设、批量输出与结果流转
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.*
import io.kaffubox.core.*
import kotlinx.serialization.json.*

@Composable
fun WorkspaceDirectory(controller: AppController) = ToolDirectory(controller)
@Composable
fun WorkspaceColumns(controller: AppController, input: @Composable () -> Unit, output: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (!controller.isTouchDevice && maxWidth >= 940.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { input() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { output() }
        } else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { input(); output() }
    }
}

@Composable
fun PresetControls(controller: AppController, tool: UiTool, state: ToolFormState) {
    var expanded by remember(tool.id) { mutableStateOf(false) }
    var name by remember(tool.id) { mutableStateOf("") }
    var includeContent by remember(tool.id) { mutableStateOf(false) }
    val builtins = if (controller.isTouchDevice && workspaceOf(tool) == WorkspaceKind.Media) emptyList() else WorkspaceCatalog.presets(tool.id)
    val presets = builtins + controller.preferences.presets.filter { it.toolId == tool.id }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起参数预设" else "参数预设") }
    if (expanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { preset -> Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.values.putAll(preset.values); state.resetResult() }, modifier = Modifier.weight(1f)) { Text(preset.name) }
            if (preset !in builtins) TextButton(onClick = { controller.updatePreferences(controller.preferences.copy(presets = controller.preferences.presets - preset)) }) { Text("删除") }
        } }
        OutlinedTextField(name, { name = it }, label = { Text("预设名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (workspaceOf(tool) in setOf(WorkspaceKind.Text, WorkspaceKind.Developer) || tool.provider != "builtin") ToggleRow("同时保存输入内容（可能包含凭据）", includeContent, { includeContent = it })
        Text("默认只保存参数，不保存文件、正文、密码、令牌和请求地址", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            val safeKeys = tool.fields.filter { !sensitiveOption(it.key) && it.kind in setOf(FieldKind.Number, FieldKind.Integer, FieldKind.Choice, FieldKind.Toggle) }.map { it.key }.toSet()
            val localFileOptions = tool.provider == "builtin" && workspaceOf(tool) in setOf(WorkspaceKind.Media, WorkspaceKind.Image, WorkspaceKind.Files)
            val values = state.values.filter { (key, _) -> key !in setOf("__outputDirectory", "__view") && (includeContent || key in safeKeys || localFileOptions && key !in setOf("shareId", "__rawArguments")) }
            val preset = ToolPreset(name.trim(), tool.id, values)
            controller.updatePreferences(controller.preferences.copy(presets = controller.preferences.presets.filterNot { it.toolId == tool.id && it.name == preset.name } + preset))
            controller.message = "参数预设已保存"
        }, enabled = name.isNotBlank()) { Text("保存当前预设") }
    }
}

@Composable
fun OutputControls(controller: AppController, tool: UiTool, state: ToolFormState) {
    if (!tool.queued || tool.workbench in setOf(Workbench.Network, Workbench.Transfer)) return
    var expanded by remember(tool.id) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起输出设置" else "输出命名、目录与冲突策略") }
    if (expanded) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (tool.fields.any { it.key == "name" }) OutlinedTextField(state.values["__nameTemplate"].orEmpty(), { state.values["__nameTemplate"] = it }, Modifier.fillMaxWidth(), label = { Text("批量名称模板") }, supportingText = { Text("例如 {name}_{index}.{ext}，留空使用输出文件名，序号从 1 开始") })
        ChoiceField("同名文件", state.values["__overwritePolicy"] ?: "unique", listOf("unique" to "自动添加序号", "fail" to "报错并保留原文件", "replace" to "成功后替换同名输出"), { state.values["__overwritePolicy"] = it })
        if (!controller.isTouchDevice) OutlinedTextField(state.values["__outputDirectory"].orEmpty(), { state.values["__outputDirectory"] = it }, Modifier.fillMaxWidth(), label = { Text("本次输出目录") }, supportingText = { Text("留空使用应用设置中的输出目录，每次提交时固定目录") })
        Text(controller.outputDescription, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun WorkspaceResultActions(controller: AppController, result: JsonElement, toolId: String) {
    val refs = remember(result) { resultFiles(result).distinctBy { it.id } }
    val content = remember(result, toolId) { if (toolId == "network.http") ((result as? JsonObject)?.get("body") as? JsonPrimitive)?.contentOrNull ?: resultText(result, toolId) else resultText(result, toolId) }
    val table = (result as? JsonObject)?.get("rows") as? JsonArray
    val choices = if (refs.isNotEmpty()) compatibleFileTools(controller.tools, refs) else controller.tools.filter { textInputKey(it) != null || table != null && it.id == "document.csvWrite" }
    var expanded by remember(result) { mutableStateOf(false) }
    var target by remember(result) { mutableStateOf(choices.firstOrNull { it.id != toolId }?.id.orEmpty()) }
    TextButton(onClick = { expanded = !expanded }, enabled = choices.isNotEmpty()) { Text(if (expanded) "收起后续操作" else if (refs.isEmpty()) "将结果用于下一步" else "继续处理这些文件") }
    if (expanded) {
        ChoiceField("后续操作", target, choices.map { it.id to ((workspaceOf(it)?.title?.removeSuffix("工作台") ?: "快捷") + " · " + it.title) }, { target = it })
        Button(onClick = { choices.firstOrNull { it.id == target }?.let { next ->
            if (refs.isNotEmpty()) controller.openTool(next, refs)
            else if (table != null && next.id == "document.csvWrite") { controller.openTool(next); controller.form(next).values["rows"] = table.toString(); controller.form(next).resetResult() }
            else controller.sendText(next, if (table != null && next.id in setOf("developer.json", "developer.jsonToYaml", "document.convert")) table.toString() else content)
        } }, enabled = target.isNotEmpty()) { Text("使用当前结果") }
    }
}

@Composable
fun BatchResults(controller: AppController, state: ToolFormState, tool: UiTool) {
    val tasks = controller.tasks.filter { it.id in state.taskIds || it.id == state.selectedTask }.sortedBy { it.createdAt }
    if (tasks.isNotEmpty()) {
        if (workspaceOf(tool) == WorkspaceKind.Image) ImageComparisonView(controller, tasks)
        Text("本次任务 · ${tasks.count { it.state == TaskState.Succeeded }}/${tasks.size} 已完成", style = MaterialTheme.typography.titleMedium)
        val refs = tasks.filter { it.state == TaskState.Succeeded }.flatMap { it.result?.let(::resultFiles).orEmpty() }
        if (refs.isNotEmpty() && tasks.size > 1) WorkspaceResultActions(controller, jsonObject("files" to CoreJson.encodeToJsonElement(refs)), tool.id)
        if (tasks.any { it.state in ActiveTaskStates }) TextButton(onClick = { tasks.filter { it.state in ActiveTaskStates }.forEach { controller.host.tasks.cancel(it.id) } }) { Text("取消本批未完成任务") }
        tasks.forEach { task -> key(task.id) { TaskCard(controller, task, showResult = tasks.size == 1) } }
    }
    state.result?.let { ResultView(controller, it, tool.id) }
}
