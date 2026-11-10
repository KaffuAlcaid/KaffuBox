// 提供双向编码转换、输入输出交换与文本结果保存
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.kaffubox.core.jsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class EncodingState {
    var format by mutableStateOf("base64")
    var decode by mutableStateOf(false)
    var input by mutableStateOf("")
    var output by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
}

@Composable
fun EncodingPage(controller: AppController) {
    val state = controller.encoding
    val scope = rememberCoroutineScope()
    Panel(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceField("格式", state.format, listOf("base64" to "Base64", "base64Url" to "Base64 URL", "base32" to "Base32", "hex" to "Hex", "url" to "URL", "form" to "URL 表单"), { state.format = it; state.output = "" }, Modifier.weight(1f))
            ChoiceField("方向", state.decode.toString(), listOf("false" to "编码", "true" to "解码"), { state.decode = it.toBoolean(); state.output = "" }, Modifier.weight(1f))
        }
        OutlinedTextField(state.input, { state.input = it }, Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 330.dp), label = { Text("输入内容") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                state.busy = true; state.error = null
                val request = jsonObject("text" to state.input, "format" to state.format, "decode" to state.decode)
                scope.launch {
                    try { state.output = (controller.execute("developer.encoding", request) as JsonObject).getValue("text").jsonPrimitive.content }
                    catch (_: CancellationException) { } catch (failure: Exception) { state.output = ""; state.error = UiText.failure(failure) }
                    finally { state.busy = false }
                }
            }, enabled = !state.busy) { Text(if (state.busy) "正在转换" else if (state.decode) "解码" else "编码") }
            TextButton(onClick = { state.input = state.output; state.output = ""; state.decode = !state.decode }, enabled = !state.busy && state.output.isNotEmpty()) { Text("交换输入与输出") }
            TextButton(onClick = { state.input = ""; state.output = ""; state.error = null }) { Text("清空") }
        }
        InlineError(state.error)
        OutlinedTextField(state.output, {}, Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 330.dp), label = { Text("转换结果") }, readOnly = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { controller.copy(state.output) }, enabled = state.output.isNotEmpty()) { Text("复制结果") }
            TextButton(onClick = { controller.saveText(state.output) }, enabled = state.output.isNotEmpty()) { Text("保存为文件") }
        }
    }
}
