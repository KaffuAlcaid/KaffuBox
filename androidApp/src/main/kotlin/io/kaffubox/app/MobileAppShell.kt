// 提供手机首页、工具导航、搜索收藏和适合触屏的全局对话框
package io.kaffubox.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kaffubox.core.*

@Composable
fun MobileAppShell(controller: AppController) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(controller.message) { controller.message?.let { value -> snackbar.showSnackbar(value); if (controller.message == value) controller.message = null } }
    CountdownTicker(controller)
    BackHandler(controller.page != AppPage.Home) { controller.navigate(if (controller.page == AppPage.Tool) AppPage.Tools else AppPage.Home) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        if (controller.page != AppPage.Tool) NavigationBar {
            val destinations = listOf(Triple(AppPage.Home, "首页", Icons.Outlined.Home), Triple(AppPage.Tools, "工具", Icons.Outlined.GridView), Triple(AppPage.Tasks, "任务", Icons.Outlined.Checklist), Triple(AppPage.Plugins, "插件", Icons.Outlined.Extension), Triple(AppPage.Settings, "设置", Icons.Outlined.Settings))
            destinations.forEach { (page, label, icon) -> NavigationBarItem(selected = controller.page == page || (page == AppPage.Tools && controller.page == AppPage.Tool), onClick = { controller.navigate(page) }, icon = { Icon(icon, label) }, label = { Text(label) }) }
        }
    }) { inset ->
        Column(Modifier.fillMaxSize().padding(inset).imePadding().padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (controller.page in setOf(AppPage.Home, AppPage.Tools)) OutlinedTextField(controller.search, { controller.search = it }, Modifier.fillMaxWidth().padding(bottom = 14.dp), singleLine = true, placeholder = { Text("搜索工具") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { if (controller.search.isNotBlank()) IconButton(onClick = { controller.search = "" }) { Icon(Icons.Outlined.Close, "清空搜索") } })
            key(controller.page, controller.selectedToolId) {
                ScrollPage {
                    when (controller.page) {
                        AppPage.Home -> MobileHome(controller)
                        AppPage.Tools -> MobileTools(controller)
                        AppPage.Tool -> ToolPage(controller)
                        AppPage.Tasks -> TasksPage(controller)
                        AppPage.Plugins -> PluginsPage(controller)
                        AppPage.Settings -> MobileSettings(controller)
                    }
                }
            }
        }
    }
    MobileDialogs(controller)
}

private fun matchingTools(controller: AppController): List<UiTool> = controller.tools.filter { tool ->
    (controller.page == AppPage.Home || controller.selectedCategory == null || tool.workbench == controller.selectedCategory) &&
        (controller.search.isBlank() || listOf(tool.title, tool.summary, tool.id).any { it.contains(controller.search.trim(), ignoreCase = true) })
}

@Composable
private fun MobileHome(controller: AppController) {
    PageHeader(if (controller.search.isBlank()) "KaffuBox" else "搜索结果")
    if (controller.search.isNotBlank()) { MobileToolGrid(controller, matchingTools(controller)); return }
    Text("常用工具", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
    MobileToolGrid(controller, controller.preferences.favorites.mapNotNull(controller::findTool))
    Text("工作台", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
    ToolCatalog.categories.entries.toList().chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { (category, title) ->
                Surface(onClick = { controller.selectedCategory = category; controller.navigate(AppPage.Tools) }, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(workbenchIcon(category), null, tint = MaterialTheme.colorScheme.primary)
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(controller.tools.count { it.workbench == category }.toString() + " 个工具", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileTools(controller: AppController) {
    PageHeader("工具")
    ChoiceField("分类", controller.selectedCategory?.name ?: "all", listOf("all" to "全部工具") + ToolCatalog.categories.map { it.key.name to it.value }, { controller.selectedCategory = if (it == "all") null else Workbench.valueOf(it) }, Modifier.fillMaxWidth().padding(bottom = 14.dp))
    MobileToolGrid(controller, matchingTools(controller))
}

@Composable
private fun MobileToolGrid(controller: AppController, tools: List<UiTool>) {
    if (tools.isEmpty()) { Panel(Modifier.fillMaxWidth()) { Text("没有找到工具"); Text("可以换个关键词，或给常用工具加上星标", color = MaterialTheme.colorScheme.onSurfaceVariant) }; return }
    tools.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { tool ->
                Surface(onClick = { controller.openTool(tool) }, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(12.dp).heightIn(min = 118.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(workbenchIcon(tool.workbench), null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { controller.favorite(tool.id) }, modifier = Modifier.size(30.dp)) { Icon(if (tool.id in controller.preferences.favorites) Icons.Outlined.Star else Icons.Outlined.StarBorder, "收藏", Modifier.size(20.dp)) }
                        }
                        Text(tool.title, style = MaterialTheme.typography.titleSmall)
                        Text(tool.summary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun MobileSettings(controller: AppController) {
    var unsigned by remember { mutableStateOf(false) }
    val settings = controller.preferences
    PageHeader("设置")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel(Modifier.fillMaxWidth(), "外观") { ChoiceField("主题", settings.theme, listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"), { controller.updatePreferences(settings.copy(theme = it)) }) }
        Panel(Modifier.fillMaxWidth(), "工具与文件") {
            ToggleRow("记住工具选项", settings.rememberParameters, { controller.updatePreferences(settings.copy(rememberParameters = it, toolOptions = if (it) settings.toolOptions else emptyMap())) })
            Text("保留格式、数值与开关选项，文本、密码和所选文件只在当前会话使用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("处理结果先保存在应用内，点击结果旁的“保存到…”可以导出文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToggleRow("任务完成时通知", settings.notifyCompleted, { controller.updatePreferences(settings.copy(notifyCompleted = it)); if (it) controller.requestNotifications?.invoke() })
        }
        Panel(Modifier.fillMaxWidth(), "插件开发") {
            ToggleRow("允许未签名插件", settings.allowUnsignedPlugins, { if (it) unsigned = true else controller.updatePreferences(settings.copy(allowUnsignedPlugins = false)) })
            Text("重新启动应用后生效", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Panel(Modifier.fillMaxWidth(), "关于") {
            Text("KaffuBox  0.1.0")
            Text("使用 Android 系统运行时与媒体解码能力", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (unsigned) AlertDialog(onDismissRequest = { unsigned = false }, title = { Text("允许未签名插件") }, text = { Text("未签名插件无法验证开发者身份，适用于你自己开发或已确认来源的插件") }, confirmButton = { Button(onClick = { controller.updatePreferences(settings.copy(allowUnsignedPlugins = true)); unsigned = false }) { Text("允许") } }, dismissButton = { TextButton(onClick = { unsigned = false }) { Text("取消") } })
}

@Composable
private fun MobileDialogs(controller: AppController) {
    controller.installPreview?.let { PluginInstallDialog(controller, it) }
    controller.errorDetails?.let { error ->
        var details by remember(error) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { controller.errorDetails = null }, title = { Text(error.title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(error.message); TextButton(onClick = { details = !details }) { Text(if (details) "收起详情" else "查看详情") }; if (details) OutlinedTextField(error.details, {}, readOnly = true, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) } }, confirmButton = { TextButton(onClick = { controller.errorDetails = null }) { Text("关闭") } }, dismissButton = { TextButton(onClick = { controller.copy(error.details) }) { Text("复制详情") } })
    }
    if (controller.fileToolPicker.isNotEmpty()) AlertDialog(onDismissRequest = { controller.fileToolPicker = emptyList() }, title = { Text("选择处理工具") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text("已导入 " + controller.fileToolPicker.size + " 个文件")
            controller.tools.filter { it.fields.any { field -> field.kind in setOf(FieldKind.File, FieldKind.Files) } }.forEach { tool -> TextButton(onClick = { controller.openTool(tool, controller.fileToolPicker); controller.fileToolPicker = emptyList() }, modifier = Modifier.fillMaxWidth()) { Text(tool.title, Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null) } }
        }
    }, confirmButton = { TextButton(onClick = { controller.fileToolPicker = emptyList() }) { Text("取消") } })
}
