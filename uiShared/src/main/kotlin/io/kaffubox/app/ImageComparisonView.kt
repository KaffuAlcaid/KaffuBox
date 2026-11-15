// 将已完成的图像任务与其原始输入对应，支持逐项查看处理前后预览
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.kaffubox.core.*
import kotlinx.serialization.json.*

@Composable
fun ImageComparisonView(controller: AppController, tasks: List<TaskSnapshot>) {
    val pairs = tasks.filter { it.state == TaskState.Succeeded }.mapNotNull { task ->
        val inputId = controller.taskArguments[task.id]?.get("file")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val source = runCatching { controller.files.resolve(inputId).ref }.getOrNull() ?: return@mapNotNull null
        val output = task.result?.let(::resultFiles)?.firstOrNull { it.looksLikeImage() } ?: return@mapNotNull null
        Triple(task.id, source, output)
    }
    if (pairs.isEmpty()) return
    var selected by remember { mutableStateOf(pairs.first().first) }
    val pair = pairs.firstOrNull { it.first == selected } ?: pairs.first()
    Panel(Modifier.fillMaxWidth(), "处理前后对照") {
        if (pairs.size > 1) ChoiceField("选择图片", pair.first, pairs.map { it.first to it.second.name }, { selected = it })
        BoxWithConstraints {
            if (maxWidth >= 540.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { Text("处理前 · ${readableBytes(pair.second.size)}"); ImageFilePreview(controller, pair.second, 220.dp) }
                Column(Modifier.weight(1f)) { Text("处理后 · ${readableBytes(pair.third.size)}"); ImageFilePreview(controller, pair.third, 220.dp) }
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("处理前 · ${readableBytes(pair.second.size)}"); ImageFilePreview(controller, pair.second, 180.dp)
                Text("处理后 · ${readableBytes(pair.third.size)}"); ImageFilePreview(controller, pair.third, 180.dp)
            }
        }
    }
}
