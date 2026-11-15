// 展示媒体时间范围、重命名预览、网络步骤、会话内请求历史和可关闭的文件传输会话
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import io.kaffubox.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable
fun MediaRangeControls(controller: AppController, state: ToolFormState) {
    val duration = (state.mediaInfo?.get("format") as? JsonObject)?.mediaText("duration")?.toDoubleOrNull()
    val start = state.values["start"]?.toDoubleOrNull() ?: 0.0
    val end = state.values["__end"]?.toDoubleOrNull() ?: state.values["duration"]?.toDoubleOrNull()?.let { start + it } ?: duration
    var previewEnd by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    val ref = state.files["file"]?.firstOrNull()
    val video = (state.mediaInfo?.get("streams") as? JsonArray)?.any { (it as? JsonObject)?.mediaText("codec_type") == "video" } == true
    val position = if (previewEnd) ((end ?: start) - 0.05).coerceAtLeast(0.0) else start
    LaunchedEffect(ref?.id, position, video) {
        preview = null; previewError = null
        if (ref != null && video && position.isFinite() && position >= 0) {
            delay(300)
            try { preview = videoPreview(controller, ref, position) }
            catch (_: CancellationException) { }
            catch (failure: Exception) { previewError = UiText.failure(failure) }
        }
    }
    Text("处理时间范围", style = MaterialTheme.typography.titleSmall)
    if (state.files["file"].orEmpty().size > 1) Text("多文件使用相同起止时间，下面预览第一份文件", style = MaterialTheme.typography.bodySmall)
    if (duration != null && duration.isFinite() && duration > 0) {
        RangeSlider(value = start.coerceIn(0.0, duration).toFloat()..(end ?: duration).coerceIn(start.coerceIn(0.0, duration), duration).toFloat(), onValueChange = { state.values["start"] = mediaNumber(it.start.toDouble()); state.values["__end"] = mediaNumber(it.endInclusive.toDouble()) }, valueRange = 0f..duration.toFloat())
        Text("媒体总时长 ${mediaDuration(duration)}", style = MaterialTheme.typography.bodySmall)
    }
    OutlinedTextField(state.values["start"] ?: "0", { state.values["start"] = it }, Modifier.fillMaxWidth(), label = { Text("起点（秒）") }, singleLine = true)
    OutlinedTextField(state.values["__end"].orEmpty(), { state.values["__end"] = it; state.values["duration"] = "" }, Modifier.fillMaxWidth(), label = { Text("终点（秒，留空到结尾）") }, singleLine = true, isError = end != null && end <= start)
    if (end != null && end > start) Text("选中 ${mediaDuration(end - start)}", style = MaterialTheme.typography.bodySmall)
    if (video) { ToggleRow("预览终点画面", previewEnd, { previewEnd = it }); preview?.let { ImageCanvas(it, Modifier.fillMaxWidth().height(210.dp)) }; InlineError(previewError) }
}

@Composable
fun RenamePreview(controller: AppController, tool: UiTool, state: ToolFormState) {
    var preview by remember { mutableStateOf<JsonObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    TextButton(onClick = { scope.launch {
        busy = true; state.values.remove("__renameApproved")
        try {
            val signature = renameSignature(state)
            preview = controller.execute("file.renamePreview", controller.arguments(tool, state)).jsonObject
            state.values["__renameApproved"] = signature
            state.error = null
        } catch (failure: Exception) { state.error = UiText.failure(failure) } finally { busy = false }
    } }, enabled = !busy) { Text(if (busy) "正在预览" else "预览全部新名称") }
    preview?.let { obj -> ResultSectionsView(listOf(ResultSection("名称预览", (obj["entries"] as? JsonArray).orEmpty().map { entry -> val row = entry.jsonObject; row.getValue("originalName").jsonPrimitive.content to row.getValue("name").jsonPrimitive.content }))) }
    Text(if (state.values["mode"] == "rename") "执行后会更改原文件名称，修改参数或文件列表后需要重新预览" else "创建改名副本，原文件保留", style = MaterialTheme.typography.bodySmall)
}

@Composable
fun NetworkDiagnosisResult(controller: AppController, result: JsonObject) {
    (result["steps"] as? JsonArray).orEmpty().forEach { item ->
        val step = item.jsonObject; val id = step.getValue("toolId").jsonPrimitive.content
        Text(controller.findTool(id)?.title ?: id, style = MaterialTheme.typography.titleMedium)
        step["error"]?.jsonPrimitive?.contentOrNull?.let { error -> InlineError(UiText.error(error)); Text(error, style = MaterialTheme.typography.bodySmall) }
        step["result"]?.let { ResultView(controller, it, id) }
    }
}

@Composable
fun HttpRequestHistory(controller: AppController, tool: UiTool, state: ToolFormState) {
    val history = controller.workspace.requestHistory
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text("请求历史（当前会话 ${history.size} 条）") }
    if (expanded) Panel(Modifier.fillMaxWidth()) {
        Text("请求只保留在本次运行内，不自动保存地址、正文和访问凭据", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { history.clear() }, enabled = history.isNotEmpty()) { Text("清空请求历史") }
        history.toList().forEach { record ->
            TextButton(onClick = { tool.fields.forEach { field -> record.arguments[field.key]?.let { value -> state.values[field.key] = if (value is JsonPrimitive) value.content else value.toString() } }; state.resetResult() }) {
                Text((record.arguments["method"]?.jsonPrimitive?.content ?: "GET") + "  " + record.arguments["url"]?.jsonPrimitive?.content.orEmpty())
            }
        }
    }
}

@Composable
fun ShareResultView(controller: AppController, result: JsonObject) {
    val scope = rememberCoroutineScope()
    var session by remember(result) { mutableStateOf(result) }
    var error by remember(result) { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var details by remember(result) { mutableStateOf(false) }
    LaunchedEffect(result) {
        while (isActive) {
            try {
                val sessions = controller.execute("transfer.sessions", jsonObject()).jsonObject.getValue("sessions").jsonArray
                session = sessions.map { it.jsonObject }.firstOrNull { it["shareId"] == result["shareId"] } ?: JsonObject(result + ("active" to JsonPrimitive(false)))
                now = System.currentTimeMillis(); error = null
                if (session["active"]?.jsonPrimitive?.booleanOrNull == false && session["state"]?.jsonPrimitive?.content != "closing") break
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = UiText.failure(failure) }
            delay(1000)
        }
    }
    val active = session["active"]?.jsonPrimitive?.booleanOrNull == true
    val receiving = session["mode"]?.jsonPrimitive?.content == "receive"
    Text(if (receiving) "接收文件" else session["name"]?.jsonPrimitive?.contentOrNull ?: "文件分享", style = MaterialTheme.typography.titleMedium)
    if (!active) Text((if (session["expiresAt"]?.jsonPrimitive?.longOrNull?.let { it <= now } == true) "会话已到期" else if (receiving) "接收已停止" else "分享已停止") + "，链接已失效")
    else TransferAddressPanel(controller, session)
    Text("${if (receiving) "已接收" else "已完成下载"} ${session["completedFiles"]?.jsonPrimitive?.content ?: "0"} ${if (receiving) "个文件" else "次"} · 到期 " + transferTime(session["expiresAt"]?.jsonPrimitive?.longOrNull), style = MaterialTheme.typography.bodySmall)
    InlineError(error)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (active) OutlinedButton(onClick = { scope.launch {
            try { controller.execute("transfer.stopShare", jsonObject("shareId" to result.getValue("shareId"))); session = JsonObject(session + ("active" to JsonPrimitive(false))) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = UiText.failure(failure) }
        } }) { Text(if (receiving) "停止接收" else "停止分享") }
        TextButton(onClick = { details = !details }) { Text(if (details) "收起详情" else "会话详情") }
    }
    if (details) {
        TransferSessionFacts(session, now)
        session["archive"]?.jsonObject?.let { archive -> Text("${archive["entries"]?.jsonPrimitive?.content} 个文件 · ZIP ${readableBytes(archive["size"]?.jsonPrimitive?.longOrNull)}", style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { controller.findTool("transfer.stopShare")?.let { controller.openTool(it) } }) { Text("管理文件分享任务") }
    }
    if (receiving && (session["files"] as? JsonArray).orEmpty().isNotEmpty()) ResultView(controller, jsonObject("files" to session.getValue("files")), "transfer.received")
}
