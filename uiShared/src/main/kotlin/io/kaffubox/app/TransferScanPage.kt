// 将二维码导入、地址确认和文件下载分阶段显示，主要操作始终紧邻当前内容
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.kaffubox.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable
fun TransferScanPage(controller: AppController, tool: UiTool) {
    val state = controller.form(tool)
    val scope = rememberCoroutineScope()
    val input = state.values["__scanText"].orEmpty()
    val valid = remember(input) { runCatching { transferUri(input) }.isSuccess }
    val info = state.mediaInfo?.takeIf { it["input"]?.jsonPrimitive?.content == input }
    val ref = state.files["file"]?.firstOrNull()
    var choosing by remember(tool.id) { mutableStateOf(input.isBlank()) }
    fun setCode(text: String) { state.values["__scanText"] = text; state.mediaInfo = null; state.error = null }
    fun inspect() { scope.launch {
        state.busy = true; state.error = null; state.mediaInfo = null; choosing = false
        try {
            val metadata = controller.execute("transfer.inspectDownload", jsonObject("url" to input)).jsonObject
            if (state.values["__scanText"] == input) state.mediaInfo = JsonObject(metadata + ("input" to JsonPrimitive(input)))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { state.error = UiText.failure(failure) }
        finally { state.busy = false }
    } }
    LaunchedEffect(ref?.id) {
        if (ref == null) return@LaunchedEffect
        state.busy = true; state.error = null
        try { setCode(controller.execute("transfer.scanCode", jsonObject("file" to ref.id)).jsonObject.getValue("text").jsonPrimitive.content); choosing = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { state.error = UiText.failure(failure) }
        finally { state.busy = false }
    }
    ReceiveMethodTabs(controller, false)
    val latest = controller.tasks.firstOrNull { it.id == state.taskIds.lastOrNull() }
    Column(Modifier.widthIn(max = 820.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (latest != null) {
            TaskCard(controller, latest, showResult = true)
            if (latest.state !in ActiveTaskStates) OutlinedButton(onClick = { state.taskIds.clear(); state.files.clear(); setCode(""); choosing = true }) { Text("继续接收文件") }
        } else Panel(Modifier.fillMaxWidth()) {
            if (choosing || input.isBlank()) {
                TransferScanCapture(controller) { code -> state.files.clear(); setCode(code); choosing = false }
                FileDropArea({ controller.acceptFiles(it, tool, tool.fields.first()) }, Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { controller.selectFiles(tool, tool.fields.first()) }, modifier = Modifier.fillMaxWidth()) { Text(if (controller.supportsFileDrop) "选择二维码图片，或拖到这里" else "从图片识别二维码") }
                }
                OutlinedTextField(input, { setCode(it) }, Modifier.fillMaxWidth(), label = { Text("也可以粘贴文件下载链接") }, minLines = 2, maxLines = 4)
                Button(onClick = ::inspect, enabled = valid && !state.busy, modifier = Modifier.fillMaxWidth()) { Text(if (state.busy) "正在读取" else "读取文件信息") }
            } else if (info == null) {
                Text("确认目标地址", style = MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(input) }
                Text("核对地址后读取文件信息，确认文件后才会下载", style = MaterialTheme.typography.bodySmall)
                if (!valid) Text("识别内容不是 HTTP / HTTPS 文件链接，可以复制后使用其他工具处理", style = MaterialTheme.typography.bodySmall)
                Button(onClick = ::inspect, enabled = valid && !state.busy, modifier = Modifier.fillMaxWidth()) { Text(if (state.busy) "正在读取" else "读取文件信息") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { choosing = true }) { Text("重新选择或修改地址") }
                    TextButton(onClick = { controller.copy(input) }) { Text("复制识别内容") }
                }
            } else {
                val redirect = info["redirectUrl"]?.jsonPrimitive?.contentOrNull
                if (redirect != null) {
                    Text("确认跳转地址", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(redirect) }
                    Button(onClick = { setCode(redirect) }, modifier = Modifier.fillMaxWidth()) { Text("使用新地址，返回确认") }
                } else {
                    val suggested = info["name"]?.jsonPrimitive?.contentOrNull ?: "DownloadedFile"
                    var name by remember(info) { mutableStateOf(suggested) }
                    var options by remember(info) { mutableStateOf(false) }
                    val mime = info["mimeType"]?.jsonPrimitive?.contentOrNull
                    val type = when {
                        mime == "application/pdf" -> "PDF 文档"
                        mime in setOf("application/zip", "application/x-zip-compressed") -> "ZIP 压缩包"
                        mime?.startsWith("image/") == true -> "图片"
                        mime?.startsWith("audio/") == true -> "音频"
                        mime?.startsWith("video/") == true -> "视频"
                        mime?.startsWith("text/") == true -> "文本文件"
                        else -> "文件"
                    }
                    Text(suggested, style = MaterialTheme.typography.titleMedium)
                    Text(readableBytes(info["size"]?.jsonPrimitive?.longOrNull) + " · " + type, style = MaterialTheme.typography.bodySmall)
                    Text("来源  " + runCatching { transferUri(input).host }.getOrDefault(input), style = MaterialTheme.typography.bodySmall)
                    if (info["metadataAvailable"]?.jsonPrimitive?.booleanOrNull == false) Text("服务器未提供文件大小，下载时仍会检查大小上限", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        try {
                            val limit = (state.values["__maxDownloadMiB"] ?: "1024").toLongOrNull()
                            requireArgument(limit != null && limit in 1..10240, "maxBytes")
                            val id = controller.submitWork("transfer.download", jsonObject("url" to info.getValue("url"), "name" to name, "maxBytes" to limit!! * 1048576, "etag" to info["etag"]?.jsonPrimitive?.contentOrNull))
                            state.taskIds += id; state.error = null
                        } catch (failure: Exception) { state.error = UiText.failure(failure) }
                    }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("确认下载并保存") }
                    Text(controller.outputDescription, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { options = !options }) { Text(if (options) "收起下载选项" else "下载选项") }
                        TextButton(onClick = { state.mediaInfo = null; choosing = true }) { Text("换一个文件") }
                    }
                    if (options) {
                        Text("服务器内容类型  " + (mime ?: "未提供"), style = MaterialTheme.typography.bodySmall)
                        SelectionContainer { Text(info.getValue("url").jsonPrimitive.content, style = MaterialTheme.typography.bodySmall) }
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("保存文件名") }, singleLine = true)
                        OutlinedTextField(state.values["__maxDownloadMiB"] ?: "1024", { state.values["__maxDownloadMiB"] = it }, Modifier.fillMaxWidth(), label = { Text("下载上限（MiB，最大 10240）") }, singleLine = true)
                        Text("同名文件自动添加序号，中断或取消时清理未完成的文件", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            InlineError(state.error)
        }
    }
}
