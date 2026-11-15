// 组织桌面侧栏、工具搜索、收藏、分类导航及全局操作反馈
package io.kaffubox.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kaffubox.core.*
import kotlinx.coroutines.delay

@Composable
fun AppShell(controller: AppController) {
    val snackbar = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(controller.message) { controller.message?.let { value -> snackbar.showSnackbar(value, duration = SnackbarDuration.Short); if (controller.message == value) controller.message = null } }
    Surface(color = MaterialTheme.colorScheme.background) {
        FileDropArea({ controller.acceptFiles(it) }, Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) false else when (event.key) {
                Key.F -> { controller.workspace.directoryFilter = "all"; controller.navigate(AppPage.Tools); controller.selectedCategory = null; controller.launchWork { delay(80); searchFocus.requestFocus() }; true }
                Key.Enter -> { controller.selectedTool?.takeIf { it.id !in setOf("science.calculate", "science.unit", "science.matrix", "developer.encoding", "daily.countdown", "file.rename", "transfer.share", "transfer.receive", "transfer.scanCode", "transfer.stopShare") }?.let { controller.runForm(it) }; true }
                else -> false
            }
        }) {
            Surface(Modifier.width(94.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxHeight().padding(vertical = 20.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(Modifier.size(48.dp), color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(16.dp)) { Box(contentAlignment = Alignment.Center) { Text("K", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 25.sp) } }
                    Spacer(Modifier.height(24.dp))
                    NavButton("首页", Icons.Outlined.Home, controller.page == AppPage.Home) { controller.navigate(AppPage.Home) }
                    NavButton("工具", Icons.Outlined.GridView, controller.page in setOf(AppPage.Tools, AppPage.Tool)) { controller.selectedCategory = null; controller.navigate(AppPage.Tools) }
                    NavButton("任务", Icons.Outlined.Checklist, controller.page == AppPage.Tasks, controller.tasks.count { it.state in ActiveTaskStates }) { controller.navigate(AppPage.Tasks) }
                    NavButton("插件", Icons.Outlined.Extension, controller.page == AppPage.Plugins) { controller.navigate(AppPage.Plugins) }
                    Spacer(Modifier.weight(1f))
                    NavButton("设置", Icons.Outlined.Settings, controller.page == AppPage.Settings) { controller.navigate(AppPage.Settings) }
                    Text("0.1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                }
            }
            Scaffold(modifier = Modifier.weight(1f), containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }) { inset ->
                Column(Modifier.fillMaxSize().padding(inset).padding(horizontal = 28.dp, vertical = 24.dp)) {
                    if (controller.page in setOf(AppPage.Home, AppPage.Tools)) {
                        OutlinedTextField(controller.search, { controller.search = it }, Modifier.fillMaxWidth().focusRequester(searchFocus).padding(bottom = 22.dp), singleLine = true, placeholder = { Text("搜索工具") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = if (controller.search.isNotEmpty()) ({ IconButton(onClick = { controller.search = "" }) { Icon(Icons.Outlined.Close, "清空搜索") } }) else null, shape = RoundedCornerShape(14.dp))
                    }
                    key(controller.page, controller.selectedToolId) {
                        if (controller.page == AppPage.Tool) FocusedToolPage(controller) else ScrollPage(scroll = navigationScrollState(controller)) {
                            when (controller.page) {
                                AppPage.Home -> HomePage(controller)
                                AppPage.Tools -> ToolsPage(controller)
                                AppPage.Tool -> ToolPage(controller)
                                AppPage.Tasks -> TasksPage(controller)
                                AppPage.Plugins -> PluginsPage(controller)
                                AppPage.Settings -> SettingsPage(controller)
                            }
                        }
                    }
                }
            }
        }
        }
        AppDialogs(controller)
        CountdownReminder(controller)
    }
}

@Composable
private fun NavButton(label: String, icon: ImageVector, selected: Boolean, count: Int = 0, action: () -> Unit) {
    Surface(onClick = action, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(15.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) { Icon(icon, null, Modifier.size(23.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomePage(controller: AppController) = LauncherHome(controller)

@Composable
private fun ToolsPage(controller: AppController) = ToolDirectory(controller)
@Composable
private fun AppDialogs(controller: AppController) {
    controller.installPreview?.let { PluginInstallDialog(controller, it) }
    controller.errorDetails?.let { error ->
        var details by remember(error) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { controller.errorDetails = null }, title = { Text(error.title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error.message)
                TextButton(onClick = { details = !details }) { Text(if (details) "收起详情" else "查看详情") }
                if (details) OutlinedTextField(error.details, {}, readOnly = true, modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp))
            }
        }, confirmButton = { TextButton(onClick = { controller.errorDetails = null }) { Text("关闭") } }, dismissButton = { TextButton(onClick = { controller.copy(error.details) }) { Text("复制详情") } })
    }
    if (controller.requestExit) AlertDialog(onDismissRequest = { controller.requestExit = false }, title = { Text("还有任务或倒计时正在进行") }, text = { Text("退出会取消未完成的任务和倒计时，已生成的完整结果会保留") }, confirmButton = { Button(onClick = controller::cancelAndExit) { Text("取消任务并退出") } }, dismissButton = { TextButton(onClick = { controller.requestExit = false }) { Text("继续处理") } })
    if (controller.fileToolPicker.isNotEmpty()) AlertDialog(onDismissRequest = { controller.fileToolPicker = emptyList() }, title = { Text("选择处理工具") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text("已添加 " + controller.fileToolPicker.size + " 个文件", Modifier.padding(bottom = 8.dp))
            compatibleFileTools(controller.tools, controller.fileToolPicker).forEach { tool ->
                TextButton(onClick = { controller.openTool(tool, controller.fileToolPicker); controller.fileToolPicker = emptyList() }, modifier = Modifier.fillMaxWidth()) { Text(tool.title, Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null) }
            }
        }
    }, confirmButton = { TextButton(onClick = { controller.fileToolPicker = emptyList() }) { Text("取消") } })
}
