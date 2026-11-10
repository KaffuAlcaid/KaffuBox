// 定义双端导航、工具表单、错误详情和插件安装预览状态
package io.kaffubox.app

import androidx.compose.runtime.*
import io.kaffubox.core.*
import kotlinx.coroutines.Job
import kotlinx.serialization.json.*
import java.nio.file.Path

enum class AppPage { Home, Tools, Tool, Tasks, Plugins, Settings }

class ToolFormState(tool: UiTool) {
    val values = mutableStateMapOf<String, String>().apply { tool.fields.forEach { put(it.key, it.initial) } }
    val files = mutableStateMapOf<String, List<FileRef>>()
    var result by mutableStateOf<JsonElement?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var job: Job? = null
    var selectedTask by mutableStateOf<String?>(null)
    var mediaInfo by mutableStateOf<JsonObject?>(null)
    var mediaError by mutableStateOf<String?>(null)
    var probing by mutableStateOf(false)
    fun resetResult() { job?.cancel(); busy = false; result = null; error = null; selectedTask = null; mediaInfo = null; mediaError = null }
}

data class ErrorDetails(val title: String, val message: String, val details: String)
data class PluginInstallPreview(val path: Path, val manifest: PluginManifest, val signer: String?, val updateOf: String? = null)
