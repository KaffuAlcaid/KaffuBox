// 展示真实后台任务、执行进度、取消重试和输出结果
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.kaffubox.core.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val ActiveTaskStates = setOf(TaskState.Queued, TaskState.Running, TaskState.Cancelling)

@Composable
fun TasksPage(controller: AppController) {
    var filter by remember { mutableStateOf("all") }
    PageHeader("任务中心") { TextButton(onClick = controller::openOutputDirectory) { Text(controller.outputLocationActionLabel) }; TextButton(onClick = controller::removeFinishedTasks) { Text("清除已结束记录") } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("all" to "全部", "active" to "处理中", "completed" to "已完成", "failed" to "失败与取消").forEach { (key, label) -> FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label) }) }
    }
    val tasks = controller.tasks.filter { when (filter) { "active" -> it.state in ActiveTaskStates; "completed" -> it.state == TaskState.Succeeded; "failed" -> it.state in setOf(TaskState.Failed, TaskState.Cancelled); else -> true } }.sortedByDescending { it.createdAt }
    if (tasks.isEmpty()) Panel(Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("暂无任务"); Text("文件和媒体处理开始后，可在这里查看进度与结果", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { tasks.forEach { task -> key(task.id) { TaskCard(controller, task) } } }
}

@Composable
fun TaskCard(controller: AppController, task: TaskSnapshot, showResult: Boolean = false) {
    var expanded by remember(task.id) { mutableStateOf(showResult) }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(controller.findTool(task.toolId)?.title ?: task.toolId, style = MaterialTheme.typography.titleMedium)
                val arguments = controller.taskArguments[task.id]
                val ids = listOfNotNull((arguments?.get("file") as? JsonPrimitive)?.content) + ((arguments?.get("files") as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty())
                val names = ids.mapNotNull { runCatching { controller.files.resolve(it).ref.name }.getOrNull() }
                if (names.isNotEmpty()) Text(names.joinToString("、"), maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(task.createdAt)), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text(UiText.state(task.state), color = if (task.state == TaskState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            if (task.state in ActiveTaskStates) TextButton(onClick = { controller.host.tasks.cancel(task.id) }, enabled = task.state != TaskState.Cancelling) { Text("取消") }
            else if (task.state in setOf(TaskState.Failed, TaskState.Cancelled)) TextButton(onClick = { controller.retry(task) }) { Text("重试") }
        }
        if (task.state in ActiveTaskStates) {
            val total = task.total
            if (total != null && total > 0) { LinearProgressIndicator(progress = { (task.completed.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()); Text((task.completed * 100 / total).coerceIn(0, 100).toString() + "%", style = MaterialTheme.typography.bodySmall) }
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        task.errorCode?.let { code ->
            InlineError(UiText.failure(ToolFailure(code, task.errorDetails ?: jsonObject())))
            TextButton(onClick = { controller.errorDetails = ErrorDetails("任务失败", UiText.failure(ToolFailure(code)), code + "\n" + task.errorDetails) }) { Text("错误详情") }
        }
        task.result?.let { result ->
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起结果" else "查看结果") }
            if (expanded) ResultView(controller, result, task.toolId)
        }
    }
}
