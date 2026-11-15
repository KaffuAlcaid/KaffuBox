// 提供共享文本编辑区、即时处理、输入输出交换和结构化结果预览
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import io.kaffubox.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable
fun TextWorkspacePage(controller: AppController, tool: UiTool) {
    val state = controller.form(tool)
    val inputKey = textInputKey(tool) ?: return
    var automatic by remember { mutableStateOf(true) }
    val values = state.values.toMap()
    var structure by remember { mutableStateOf(false) }
    LaunchedEffect(tool.id, values, automatic) {
        if (!automatic) return@LaunchedEffect
        delay(350); state.busy = true
        try { state.result = controller.execute(tool.id, controller.arguments(tool, state)); state.error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { state.error = UiText.failure(failure); state.result = null }
        finally { state.busy = false }
    }
    WorkspaceColumns(controller, input = {
        Panel(Modifier.fillMaxWidth()) {
            PresetControls(controller, tool, state)
            OutlinedTextField(state.values[inputKey].orEmpty(), { state.values[inputKey] = it }, Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 540.dp).onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Enter) { controller.runForm(tool); true } else false
            }, label = { Text(if (tool.id == "text.diff") "原始文本" else "共享输入") })
            ToolFields(controller, tool, state, setOf(inputKey))
            ToggleRow("即时处理", automatic, { automatic = it })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller.runForm(tool) }) { Text("执行（Ctrl+Enter）") }
                TextButton(onClick = { state.values[inputKey] = ""; state.resetResult() }) { Text("清空输入") }
                TextButton(onClick = { state.result?.let { result -> val previous = state.values[inputKey].orEmpty(); state.values[inputKey] = resultText(result, tool.id); automatic = false; state.result = jsonObject("text" to previous) } }, enabled = state.result != null) { Text("交换输入与输出") }
                TextButton(onClick = { state.result?.let { state.values[inputKey] = resultText(it, tool.id) } }, enabled = state.result != null) { Text("结果作为输入") }
            }
            InlineError(state.error)
        }
    }, output = {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val result = state.result
        if (result != null) {
            ResultView(controller, result, tool.id)
            val parsed = remember(result) { (result as? JsonObject)?.get("structure") ?: runCatching { CoreJson.parseToJsonElement(resultText(result, tool.id)) }.getOrNull() }
            if (parsed != null) {
                TextButton(onClick = { structure = !structure }) { Text(if (structure) "收起结构预览" else "查看结构预览") }
                if (structure) Panel(Modifier.fillMaxWidth()) { ResultSectionsView(readableResultSections(parsed, "")) }
            }
        }
    })
}
