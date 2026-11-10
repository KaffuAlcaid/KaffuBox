// 提供可编辑矩阵表格、批量粘贴、维度调整与高精度矩阵结果回填
package io.kaffubox.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.kaffubox.core.jsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class MatrixInput {
    var rows by mutableStateOf(3)
    var columns by mutableStateOf(3)
    var cells by mutableStateOf(List(3) { row -> List(3) { column -> if (row == column) "1" else "0" } })
    fun resize(r: Int, c: Int) { cells = List(r) { row -> List(c) { col -> cells.getOrNull(row)?.getOrNull(col) ?: "0" } }; rows = r; columns = c }
    fun set(row: Int, col: Int, value: String) { cells = cells.mapIndexed { r, line -> if (r == row) line.mapIndexed { c, old -> if (c == col) value else old } else line } }
    fun fill(identity: Boolean) { cells = List(rows) { r -> List(columns) { c -> if (identity && r == c) "1" else "0" } } }
    fun load(values: List<List<String>>) { require(values.isNotEmpty() && values.size <= 64 && values.first().size in 1..64 && values.all { it.size == values.first().size }); cells = values; rows = values.size; columns = values.first().size }
    fun json() = JsonArray(cells.map { JsonArray(it.map { value -> JsonPrimitive(value.ifBlank { "0" }) }) })
}
class MatrixState {
    val left = MatrixInput()
    val right = MatrixInput()
    var operation by mutableStateOf("multiply")
    var scalar by mutableStateOf("2")
    var result by mutableStateOf<JsonElement?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
private fun MatrixEditor(name: String, input: MatrixInput) {
    var paste by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }
    Panel(Modifier.fillMaxWidth(), name) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoiceField("行", input.rows.toString(), (1..64).map { it.toString() to it.toString() }, { input.resize(it.toInt(), input.columns) }, Modifier.weight(1f))
            ChoiceField("列", input.columns.toString(), (1..64).map { it.toString() to it.toString() }, { input.resize(input.rows, it.toInt()) }, Modifier.weight(1f))
        }
        Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            input.cells.forEachIndexed { row, line ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    line.forEachIndexed { col, value -> OutlinedTextField(value, { input.set(row, col, it) }, Modifier.width(95.dp), singleLine = true, textStyle = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        Row {
            TextButton(onClick = { paste = true; pasteError = null }) { Text("批量粘贴") }
            TextButton(onClick = { input.fill(true) }) { Text("单位矩阵") }
            TextButton(onClick = { input.fill(false) }) { Text("清零") }
        }
        Text("单元格支持 1/3、sqrt(2)、pi 等表达式，空白按 0 计算", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (paste) AlertDialog(onDismissRequest = { paste = false }, title = { Text("粘贴到$name") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("每行一组，列之间用空格、制表符或逗号分隔，也可粘贴二维 JSON 数组")
            OutlinedTextField(pasteText, { pasteText = it }, Modifier.fillMaxWidth().height(220.dp))
            InlineError(pasteError)
        }
    }, confirmButton = { Button(onClick = {
        try {
            val values = if (pasteText.trimStart().startsWith("[")) Json.parseToJsonElement(pasteText).jsonArray.map { it.jsonArray.map { cell -> cell.jsonPrimitive.content } }
            else pasteText.trim().lines().filter { it.isNotBlank() }.map { it.trim().split(Regex("[\\s,，]+")) }
            input.load(values); paste = false
        } catch (_: Exception) { pasteError = "请检查数据，每行列数需一致，最多 64 行、64 列" }
    }) { Text("填入") } }, dismissButton = { TextButton(onClick = { paste = false }) { Text("取消") } })
}

@Composable
fun MatrixPage(controller: AppController) {
    val state = controller.matrix
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            ChoiceField("运算", state.operation, listOf("add" to "A + B", "subtract" to "A − B", "multiply" to "A × B", "scale" to "数乘 kA", "transpose" to "转置 Aᵀ", "inverse" to "逆矩阵 A⁻¹", "determinant" to "行列式 det(A)", "rank" to "秩 rank(A)", "trace" to "迹 tr(A)", "solve" to "解方程 AX = B"), { state.operation = it; state.result = null; state.error = null })
            if (state.operation == "scale") OutlinedTextField(state.scalar, { state.scalar = it }, label = { Text("系数 k") }, singleLine = true)
        }
        MatrixEditor("矩阵 A", state.left)
        if (state.operation in setOf("add", "subtract", "multiply", "solve")) MatrixEditor("矩阵 B", state.right)
        Button(onClick = {
            state.busy = true; state.error = null; state.result = null
            val request = jsonObject("matrix" to state.left.json(), "right" to state.right.json(), "operation" to state.operation, "scalar" to state.scalar)
            scope.launch {
                try { state.result = controller.execute("science.matrix", request) }
                catch (_: CancellationException) { } catch (failure: Exception) { state.error = UiText.failure(failure) }
                finally { state.busy = false }
            }
        }, enabled = !state.busy) { Text(if (state.busy) "计算中" else "计算") }
        InlineError(state.error)
        state.result?.let { result ->
            ResultView(controller, result, "science.matrix")
            (result as? JsonObject)?.get("cells")?.jsonArray?.let { rows ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { state.left.load(rows.map { it.jsonArray.map { cell -> cell.jsonPrimitive.content } }) }) { Text("填入矩阵 A") }
                    OutlinedButton(onClick = { state.right.load(rows.map { it.jsonArray.map { cell -> cell.jsonPrimitive.content } }) }) { Text("填入矩阵 B") }
                }
            }
        }
    }
}
