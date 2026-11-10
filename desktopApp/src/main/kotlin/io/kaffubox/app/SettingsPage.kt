// 提供外观、输出目录、工具参数保存、通知与插件开发模式设置
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsPage(controller: AppController) {
    val settings = controller.preferences
    var unsignedDialog by remember { mutableStateOf(false) }
    PageHeader("设置")
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel(Modifier.fillMaxWidth(), "外观") {
            ChoiceField("主题", settings.theme, listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"), { controller.updatePreferences(settings.copy(theme = it)) })
        }
        Panel(Modifier.fillMaxWidth(), "文件与任务") {
            Text("输出文件夹", style = MaterialTheme.typography.titleSmall)
            Text(controller.files.outputRoot.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = controller::chooseOutputDirectory) { Text("更改位置") }
                TextButton(onClick = controller::openOutputDirectory) { Text("打开文件夹") }
            }
            ToggleRow("记住工具选项", settings.rememberParameters, { controller.updatePreferences(settings.copy(rememberParameters = it, toolOptions = if (it) settings.toolOptions else emptyMap())) })
            Text("保存格式、数值和开关选项，文本、文件、密码与请求内容仅在当前会话保留", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToggleRow("任务完成时通知", settings.notifyCompleted, { controller.updatePreferences(settings.copy(notifyCompleted = it)) })
        }
        Panel(Modifier.fillMaxWidth(), "媒体引擎") {
            Text("FFmpeg  " + (controller.ffmpeg ?: "未找到"), style = MaterialTheme.typography.bodySmall)
            Text("ffprobe  " + (controller.ffprobe ?: "未找到"), style = MaterialTheme.typography.bodySmall)
        }
        Panel(Modifier.fillMaxWidth(), "插件开发") {
            ToggleRow("允许安装未签名插件", settings.allowUnsignedPlugins, { enabled -> if (enabled) unsignedDialog = true else controller.updatePreferences(settings.copy(allowUnsignedPlugins = false)) })
            Text("此选项在重新启动后生效", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Panel(Modifier.fillMaxWidth(), "关于") {
            Text("KaffuBox  0.1.0")
            Text("Windows 工具合集", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("配置与插件位置  " + controller.dataRoot, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (unsignedDialog) AlertDialog(onDismissRequest = { unsignedDialog = false }, title = { Text("允许未签名插件") }, text = { Text("未签名插件无法验证开发者身份，适用于你自己开发或已确认来源的插件") }, confirmButton = { Button(onClick = { controller.updatePreferences(settings.copy(allowUnsignedPlugins = true)); unsignedDialog = false }) { Text("允许，重启后生效") } }, dismissButton = { TextButton(onClick = { unsignedDialog = false }) { Text("取消") } })
}
