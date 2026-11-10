// 管理插件安装预览、权限授权、热启停、卸载、历史版本与开发者公钥
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.kaffubox.core.*

@Composable
fun PluginsPage(controller: AppController) {
    var action by remember { mutableStateOf<Pair<String, InstalledPlugin>?>(null) }
    var cancelRunning by remember { mutableStateOf(false) }
    var keyDialog by remember { mutableStateOf(false) }
    var keyId by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    PageHeader("插件") {
        TextButton(onClick = { keyDialog = true }, enabled = !controller.pluginBusy) { Text("开发者公钥") }
        Button(onClick = { controller.choosePlugin() }, enabled = !controller.pluginBusy) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("安装插件") }
    }
    if (controller.pluginBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 16.dp))
    if (controller.plugins.isEmpty()) Panel(Modifier.fillMaxWidth()) { Text("还没有安装插件"); Text("选择 .kbx 安装包以添加工具，启用、停用和更新即时生效", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        controller.plugins.forEach { plugin ->
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Extension, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(plugin.manifest.name ?: plugin.manifest.id, style = MaterialTheme.typography.titleMedium)
                        Text(plugin.manifest.version + " · " + plugin.manifest.tools.size + " 个工具", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(plugin.enabled, onCheckedChange = { enable -> action = (if (enable) "enable" else "disable") to plugin; cancelRunning = false }, enabled = !controller.pluginBusy)
                }
                plugin.manifest.description?.let { Text(it) }
                Text(if (plugin.signedBy != null) "签名开发者  " + plugin.signedBy else "未签名插件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                plugin.lastError?.let { InlineError(UiText.error(it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { controller.choosePlugin(plugin.manifest.id) }, enabled = !controller.pluginBusy) { Text("更新") }
                    TextButton(onClick = {
                        try { version = controller.host.plugins.versions(plugin.manifest.id).firstOrNull { it != plugin.manifest.version }.orEmpty(); action = "rollback" to plugin }
                        catch (failure: Exception) { controller.report(failure, "读取历史版本失败") }
                    }, enabled = !controller.pluginBusy) { Text("版本回退") }
                    TextButton(onClick = { action = "uninstall" to plugin; cancelRunning = false }, enabled = !controller.pluginBusy) { Text("卸载") }
                }
            }
        }
    }
    action?.let { (kind, plugin) ->
        val title = when (kind) { "enable" -> "启用插件"; "disable" -> "停用插件"; "rollback" -> "版本回退"; else -> "卸载插件" }
        AlertDialog(onDismissRequest = { action = null }, title = { Text(title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(plugin.manifest.name ?: plugin.manifest.id)
                when (kind) {
                    "enable" -> PluginPermissions(plugin.manifest)
                    "rollback" -> {
                        val versions = remember(plugin) { controller.host.plugins.versions(plugin.manifest.id).filter { it != plugin.manifest.version } }
                        if (versions.isEmpty()) Text("没有可回退的版本") else ChoiceField("历史版本", version, versions.map { it to it }, { version = it })
                        Text("新任务使用选定版本，正在执行的任务继续使用原版本")
                    }
                    else -> {
                        Text(if (kind == "uninstall") "移除插件及其工具，已生成的结果会保留" else "停止接受新任务，正在执行的任务默认继续完成")
                        if (controller.tasks.any { it.providerId == plugin.manifest.id && it.state in ActiveTaskStates }) ToggleRow("同时取消此插件的进行中任务", cancelRunning, { cancelRunning = it })
                    }
                }
            }
        }, confirmButton = { Button(onClick = {
            when (kind) { "enable" -> controller.enablePlugin(plugin); "disable" -> controller.disablePlugin(plugin, cancelRunning); "rollback" -> controller.rollbackPlugin(plugin, version); else -> controller.uninstallPlugin(plugin, cancelRunning) }
            action = null
        }, enabled = kind != "rollback" || version.isNotEmpty()) { Text(if (kind == "enable") "授权并启用" else title) } }, dismissButton = { TextButton(onClick = { action = null }) { Text("取消") } })
    }
    if (keyDialog) AlertDialog(onDismissRequest = { keyDialog = false }, title = { Text("开发者公钥") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("导入插件开发者提供的 RSA 公钥后，才能验证该开发者的签名插件")
            OutlinedTextField(keyId, { keyId = it }, label = { Text("开发者标识 keyId") }, singleLine = true)
            val signers = controller.host.plugins.trustedSigners()
            Text(if (signers.isEmpty()) "尚未导入公钥" else "已信任  " + signers.joinToString("、"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { Button(onClick = { controller.importPublicKey(keyId); keyDialog = false }, enabled = keyId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { Text("选择公钥文件") } }, dismissButton = { TextButton(onClick = { keyDialog = false }) { Text("关闭") } })
}

@Composable
fun PluginPermissions(manifest: PluginManifest) {
    Text(if (manifest.permissions.isEmpty()) "无需额外权限" else "此插件需要以下权限", style = MaterialTheme.typography.titleSmall)
    manifest.permissions.forEach { Text("• " + UiText.permission(it)) }
    if (manifest.dependencies.isNotEmpty()) Text("依赖插件  " + manifest.dependencies.entries.joinToString { it.key + " ≥ " + it.value })
}

@Composable
fun PluginInstallDialog(controller: AppController, preview: PluginInstallPreview) {
    var enable by remember(preview) { mutableStateOf(true) }
    AlertDialog(onDismissRequest = { if (!controller.pluginBusy) controller.installPreview = null }, title = { Text(if (preview.updateOf == null) "安装插件" else "更新插件") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text((preview.manifest.name ?: preview.manifest.id) + "  " + preview.manifest.version, style = MaterialTheme.typography.titleMedium)
            preview.manifest.description?.let { Text(it) }
            Text("包含 " + preview.manifest.tools.size + " 个工具")
            Text(preview.signer?.let { "签名标识  $it，安装时验证签名" } ?: "此安装包未签名", color = MaterialTheme.colorScheme.onSurfaceVariant)
            PluginPermissions(preview.manifest)
            if (preview.updateOf == null) ToggleRow("安装后授权并启用", enable, { enable = it })
            if (controller.pluginBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { Button(onClick = { controller.installPlugin(preview, enable) }, enabled = !controller.pluginBusy) { Text(if (preview.updateOf == null) "安装" else "更新") } }, dismissButton = { TextButton(onClick = { controller.installPreview = null }, enabled = !controller.pluginBusy) { Text("取消") } })
}
