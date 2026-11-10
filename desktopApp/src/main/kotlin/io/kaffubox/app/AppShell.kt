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
    LaunchedEffect(controller.message) { controller.message?.let { value -> snackbar.showSnackbar(value, duration = SnackbarDuration.Short); if (controller.message == value) controller.message = null } }
    CountdownTicker(controller)
    Surface(color = MaterialTheme.colorScheme.background) {
        FileDropArea({ controller.acceptFiles(it) }, Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
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
                        OutlinedTextField(controller.search, { controller.search = it }, Modifier.fillMaxWidth().padding(bottom = 22.dp), singleLine = true, placeholder = { Text("搜索工具") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = if (controller.search.isNotEmpty()) ({ IconButton(onClick = { controller.search = "" }) { Icon(Icons.Outlined.Close, "清空搜索") } }) else null, shape = RoundedCornerShape(14.dp))
                    }
                    key(controller.page, controller.selectedToolId) {
                        ScrollPage {
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
private fun HomePage(controller: AppController) {
    if (controller.search.isNotBlank()) { PageHeader("搜索结果"); ToolGrid(controller, filteredTools(controller)); return }
    PageHeader("KaffuBox") { Text("让小事更顺手", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    val favorite = controller.preferences.favorites.mapNotNull(controller::findTool)
    Text("常用工具", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
    if (favorite.isEmpty()) Panel(Modifier.fillMaxWidth()) { Text("点击工具上的星标，将它加入常用工具") } else ToolGrid(controller, favorite)
    Text("工作台", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 26.dp, bottom = 12.dp))
    ResponsiveGrid(ToolCatalog.categories.entries.toList(), minWidth = 205) { (category, title) ->
        Surface(onClick = { controller.selectedCategory = category; controller.navigate(AppPage.Tools) }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(workbenchIcon(category), null, Modifier.size(27.dp), tint = MaterialTheme.colorScheme.primary)
                Column { Text(title, style = MaterialTheme.typography.titleSmall); Text(controller.tools.count { it.workbench == category }.toString() + " 个工具", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun filteredTools(controller: AppController): List<UiTool> {
    val query = controller.search.trim()
    return controller.tools.filter { tool -> (controller.selectedCategory == null || controller.page == AppPage.Home || tool.workbench == controller.selectedCategory) && (query.isEmpty() || listOf(tool.title, tool.summary, tool.id, ToolCatalog.categories[tool.workbench].orEmpty()).any { it.contains(query, ignoreCase = true) }) }
}

@Composable
private fun ToolsPage(controller: AppController) {
    PageHeader(controller.selectedCategory?.let { ToolCatalog.categories[it] } ?: "全部工具")
    ChoiceField("分类", controller.selectedCategory?.name ?: "all", listOf("all" to "全部工具") + ToolCatalog.categories.map { it.key.name to it.value }, { controller.selectedCategory = if (it == "all") null else Workbench.valueOf(it) }, Modifier.width(260.dp).padding(bottom = 18.dp))
    ToolGrid(controller, filteredTools(controller))
}

@Composable
private fun ToolGrid(controller: AppController, tools: List<UiTool>) {
    if (tools.isEmpty()) { Panel(Modifier.fillMaxWidth()) { Text("没有找到匹配的工具"); Text("试试名称、用途或其他关键词", color = MaterialTheme.colorScheme.onSurfaceVariant) }; return }
    ResponsiveGrid(tools, minWidth = 250) { tool ->
        Surface(onClick = { controller.openTool(tool) }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().heightIn(min = 136.dp).padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(workbenchIcon(tool.workbench), null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { controller.favorite(tool.id) }, modifier = Modifier.size(28.dp)) { Icon(if (tool.id in controller.preferences.favorites) Icons.Outlined.Star else Icons.Outlined.StarBorder, "收藏", Modifier.size(19.dp), tint = if (tool.id in controller.preferences.favorites) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) }
                }
                Text(tool.title, style = MaterialTheme.typography.titleSmall)
                Text(tool.summary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun <T> ResponsiveGrid(items: List<T>, minWidth: Int, content: @Composable (T) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth.value / minWidth).toInt().coerceIn(1, 4)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item -> Box(Modifier.weight(1f)) { content(item) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

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
    if (controller.requestExit) AlertDialog(onDismissRequest = { controller.requestExit = false }, title = { Text("还有任务正在处理") }, text = { Text("退出会取消未完成的任务，已生成的完整结果会保留") }, confirmButton = { Button(onClick = controller::cancelAndExit) { Text("取消任务并退出") } }, dismissButton = { TextButton(onClick = { controller.requestExit = false }) { Text("继续处理") } })
    if (controller.fileToolPicker.isNotEmpty()) AlertDialog(onDismissRequest = { controller.fileToolPicker = emptyList() }, title = { Text("选择处理工具") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text("已添加 " + controller.fileToolPicker.size + " 个文件", Modifier.padding(bottom = 8.dp))
            controller.tools.filter { it.fields.any { field -> field.kind in setOf(FieldKind.File, FieldKind.Files) } }.forEach { tool ->
                TextButton(onClick = { controller.openTool(tool, controller.fileToolPicker); controller.fileToolPicker = emptyList() }, modifier = Modifier.fillMaxWidth()) { Text(tool.title, Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null) }
            }
        }
    }, confirmButton = { TextButton(onClick = { controller.fileToolPicker = emptyList() }) { Text("取消") } })
}
