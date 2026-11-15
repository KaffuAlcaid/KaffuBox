// 按选择文件、创建分享、展示二维码的顺序组织发送与浏览器上传，折叠非必填设置
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.kaffubox.core.*

@Composable
fun TransferSetupPage(controller: AppController, tool: UiTool) {
    val state = controller.form(tool)
    val receiving = tool.id == "transfer.receive"
    val task = controller.tasks.firstOrNull { it.id == state.selectedTask }
    val result = state.result ?: task?.result
    var editing by remember(tool.id) { mutableStateOf(result == null) }
    var settings by remember(tool.id) { mutableStateOf(false) }
    if (receiving) ReceiveMethodTabs(controller, true)
    if (!editing && result != null) {
        Panel(Modifier.fillMaxWidth()) { ShareResultView(controller, result as kotlinx.serialization.json.JsonObject) }
        TextButton(onClick = { editing = true }) { Text(if (receiving) "再开一个接收会话" else "再发一份文件") }
        return
    }
    Panel(Modifier.fillMaxWidth()) {
        if (receiving) {
            Text("让其他设备通过浏览器上传", style = MaterialTheme.typography.titleMedium)
            Text("开启后会显示二维码，对方扫码选择文件即可发送到这里", style = MaterialTheme.typography.bodySmall)
        } else {
            FileField(controller, tool, tool.fields.first(), state, showPreview = false)
            if (state.files.values.flatten().size > 1) Text("多个文件会打包成一份临时 ZIP，原文件保留", style = MaterialTheme.typography.bodySmall)
        }
        Text((if (state.values["lan"].toBoolean()) "同一局域网可用" else "仅本机可用") + " · 有效期 ${state.values["ttlSeconds"] ?: "10"} 分钟", style = MaterialTheme.typography.bodySmall)
        InlineError(state.error)
        val running = state.busy || task?.state in ActiveTaskStates
        Button(onClick = { editing = false; controller.runForm(tool) }, modifier = Modifier.fillMaxWidth(), enabled = !running && (receiving || state.files.values.any { it.isNotEmpty() })) {
            Text(if (running) "正在准备" else if (receiving) "开启接收并显示二维码" else "生成分享二维码")
        }
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { if (task != null) controller.host.tasks.cancel(task.id) else state.job?.cancel() }) { Text("取消") }
        }
        task?.takeIf { it.state == TaskState.Failed }?.let { InlineError(UiText.error(it.errorCode.orEmpty(), it.errorDetails ?: io.kaffubox.core.jsonObject())) }
        TextButton(onClick = { settings = !settings }) { Text(if (settings) "收起设置" else if (receiving) "接收设置" else "分享设置") }
        if (settings) ToolFields(controller, tool, state, exclude = tool.fields.filter { it.kind in setOf(FieldKind.File, FieldKind.Files) }.map { it.key }.toSet())
    }
}

@Composable
fun ReceiveMethodTabs(controller: AppController, uploading: Boolean) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !uploading, onClick = { controller.findTool("transfer.scanCode")?.let { controller.openTool(it) } }, label = { Text("扫码下载") })
        FilterChip(selected = uploading, onClick = { controller.findTool("transfer.receive")?.let { controller.openTool(it) } }, label = { Text("让对方上传") })
    }
}
