// 提供直接可达的常用入口、分组工具目录和可返回原搜索位置的工具页导航
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kaffubox.core.*

val DirectoryGroups = linkedMapOf("all" to "全部", "Media" to "视频与音频", "Image" to "图片处理", "transfer" to "传输与共享", "Files" to "文件管理", "Text" to "文本与文档", "Developer" to "开发与网络", "Science" to "科学计算", "quick" to "日常与设备")
fun directoryGroup(tool: UiTool): String = if (tool.workbench == Workbench.Transfer) "transfer" else workspaceOf(tool)?.name ?: "quick"
fun matchingDirectoryTools(controller: AppController, group: String = "all", query: String = controller.search): List<UiTool> = controller.tools.filter { tool ->
    (group == "all" || directoryGroup(tool) == group) && (query.isBlank() || listOf(tool.title, tool.summary, tool.id, DirectoryGroups[directoryGroup(tool)].orEmpty()).any { it.contains(query.trim(), ignoreCase = true) })
}

fun AppController.returnFromTool() {
    workspace.capture(this)
    selectedToolId = null
    page = workspace.returnPage
    search = workspace.returnSearch
    selectedCategory = workspace.returnCategory
}

@Composable
fun LauncherHome(controller: AppController) {
    if (controller.search.isNotBlank()) { PageHeader("搜索结果"); DirectoryToolGrid(controller, matchingDirectoryTools(controller)); return }
    PageHeader("KaffuBox")
    val favorites = controller.preferences.favorites.mapNotNull(controller::findTool)
    val recent = controller.preferences.recentTools.mapNotNull(controller::findTool).filter { it.id !in controller.preferences.favorites }.take(6)
    Text(if (favorites.isEmpty()) "快速开始" else "常用工具", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
    DirectoryToolGrid(controller, favorites.ifEmpty { listOf("science.calculate", "media.videoToGif", "image.transform", "transfer.share", "transfer.scanCode", "daily.countdown").mapNotNull(controller::findTool) })
    if (recent.isNotEmpty()) {
        Text("最近使用", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
        DirectoryToolGrid(controller, recent)
    }
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("按用途浏览", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { controller.workspace.directoryFilter = "all"; controller.navigate(AppPage.Tools) }) { Text("全部工具") }
    }
    val groups = DirectoryGroups.filterKeys { it != "all" }.filterKeys { group -> controller.tools.any { directoryGroup(it) == group } }
    groups.entries.toList().chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { (id, title) -> TextButton(onClick = { controller.workspace.directoryFilter = id; controller.navigate(AppPage.Tools) }, modifier = Modifier.weight(1f)) { Text(title, Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null) } }
        if (row.size == 1) Spacer(Modifier.weight(1f))
    } }
}

@Composable
fun ToolDirectory(controller: AppController) {
    PageHeader(if (controller.search.isBlank()) "工具" else "搜索结果")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    if (maxWidth < 600.dp) ChoiceField("", controller.workspace.directoryFilter, DirectoryGroups.map { it.key to it.value }, { controller.workspace.directoryFilter = it }, Modifier.fillMaxWidth())
    else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DirectoryGroups.forEach { (id, title) -> FilterChip(selected = controller.workspace.directoryFilter == id, onClick = { controller.workspace.directoryFilter = id }, label = { Text(title) }) }
    }
    }
    val matches = matchingDirectoryTools(controller, controller.workspace.directoryFilter)
    if (matches.isEmpty()) Text("没有找到匹配的工具，可以换个关键词或分类", Modifier.padding(top = 16.dp))
    if (controller.workspace.directoryFilter == "all" && controller.search.isBlank()) {
        matches.groupBy(::directoryGroup).toList().sortedBy { DirectoryGroups.keys.indexOf(it.first) }.forEach { (id, tools) ->
            Text(DirectoryGroups[id].orEmpty(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
            DirectoryToolGrid(controller, tools)
        }
    } else Box(Modifier.padding(top = 12.dp)) { DirectoryToolGrid(controller, matches) }
}

@Composable
fun DirectoryToolGrid(controller: AppController, tools: List<UiTool>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 300.dp) 1 else (maxWidth.value / 210).toInt().coerceIn(2, 4)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tools.chunked(columns).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tool -> Surface(onClick = { controller.openTool(tool) }, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(workbenchIcon(tool.workbench), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(tool.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            } }
        }
    }
}

@Composable
fun navigationScrollState(controller: AppController): androidx.compose.foundation.ScrollState {
    val form = controller.selectedTool?.let(controller::form)
    val key = controller.page.name + ":" + controller.selectedToolId.orEmpty() + ":" + if (controller.page in setOf(AppPage.Home, AppPage.Tools)) controller.workspace.directoryFilter + ":" + controller.search else if (form?.values?.get("__view") == "result") "result:" + form.selectedTask.orEmpty() else "input"
    val scroll = remember(key) { androidx.compose.foundation.ScrollState(controller.workspace.scrollOffsets[key] ?: 0) }
    DisposableEffect(key, scroll) { onDispose {
        controller.workspace.scrollOffsets[key] = scroll.value
        while (controller.workspace.scrollOffsets.size > 64) controller.workspace.scrollOffsets.remove(controller.workspace.scrollOffsets.keys.first())
    } }
    return scroll
}

@Composable
fun ToolHeader(controller: AppController, tool: UiTool) {
    var switcher by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    val transfer = tool.workbench == Workbench.Transfer
    PageHeader(if (transfer) "传输与共享" else tool.title, back = controller::returnFromTool) {
        if (!transfer) IconButton(onClick = { help = true }) { Icon(Icons.Outlined.Info, "使用说明") }
        IconButton(onClick = { controller.favorite(tool.id) }) { Icon(if (tool.id in controller.preferences.favorites) Icons.Outlined.Star else Icons.Outlined.StarBorder, "收藏") }
        IconButton(onClick = { switcher = true }) { Icon(Icons.Outlined.GridView, "切换工具") }
    }
    if (transfer) TransferTabs(controller, tool)
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(tool.title) }, text = { Text(tool.summary) }, confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } })
    if (switcher) AlertDialog(onDismissRequest = { switcher = false }, title = { Text("切换工具") }, text = {
        var query by remember { mutableStateOf("") }
        Column {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("搜索工具") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                val candidates = matchingDirectoryTools(controller, if (query.isBlank()) directoryGroup(tool) else "all", query)
                candidates.forEach { candidate -> TextButton(onClick = { switcher = false; controller.openTool(candidate) }, modifier = Modifier.fillMaxWidth()) {
                    Text(candidate.title, Modifier.weight(1f)); if (candidate.id == tool.id) Icon(Icons.Outlined.Check, null)
                } }
            }
        }
    }, confirmButton = { TextButton(onClick = { switcher = false; controller.returnFromTool(); controller.workspace.directoryFilter = "all"; controller.navigate(AppPage.Tools) }) { Text("浏览全部工具") } }, dismissButton = { TextButton(onClick = { switcher = false }) { Text("关闭") } })
}

@Composable
fun TransferTabs(controller: AppController, tool: UiTool) {
    val tabs = listOf("transfer.share" to "发文件", "transfer.scanCode" to "收文件", "transfer.stopShare" to "传输记录")
    val selected = if (tool.id == "transfer.receive") "transfer.scanCode" else tool.id
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEach { (id, title) -> FilterChip(selected = id == selected, onClick = { controller.findTool(id)?.let { controller.openTool(it) } }, label = { Text(title) }, modifier = Modifier.weight(1f)) }
    }
}
