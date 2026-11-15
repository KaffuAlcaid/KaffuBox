// 在同一页面分区管理持续传输会话与普通下载任务，展示二维码、有效期和逐次下载记录
package io.kaffubox.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.kaffubox.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun transferTime(value: Long?): String = value?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")) } ?: "未知"
private fun remainingTime(expiry: Long?, now: Long): String {
    val seconds = (((expiry ?: now) - now).coerceAtLeast(0) + 999) / 1000
    return if (seconds >= 3600) "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分" else "${seconds / 60} 分 ${seconds % 60} 秒"
}

@Composable
fun TransferAddressPanel(controller: AppController, session: JsonObject) {
    val id = session["shareId"]?.jsonPrimitive?.contentOrNull
    val urls = (session["urls"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
    var primary by remember(id) { mutableStateOf(session["primaryUrl"]?.jsonPrimitive?.contentOrNull ?: urls.firstOrNull()) }
    var showOther by remember(id) { mutableStateOf(false) }
    var svg by remember(id) { mutableStateOf<String?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    val active = session["active"]?.jsonPrimitive?.booleanOrNull == true
    LaunchedEffect(primary, active) {
        svg = null; error = null
        val address = primary ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        try { svg = controller.execute("developer.qrCode", jsonObject("text" to address, "size" to 320)).jsonObject.getValue("svg").jsonPrimitive.content }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = UiText.failure(failure) }
    }
    if (active) {
        val address = primary
        if (address == null) Text("没有可用的局域网地址，请检查 Wi-Fi 或网线连接")
        else {
            Text(if (address.startsWith("http://127.0.0.1:")) "当前仅本机可访问，要让其他设备扫码，请开启允许同一局域网访问后重新创建分享" else if (session["mode"]?.jsonPrimitive?.content == "receive") "保持 KaffuBox 运行，另一台设备扫码后可通过浏览器上传文件" else "保持 KaffuBox 运行，同一局域网的其他设备可扫码下载文件", style = MaterialTheme.typography.bodySmall)
            svg?.let { Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) { SvgPreview(it, Modifier.size(196.dp)) } }
            SelectionContainer { Text(address, style = MaterialTheme.typography.bodySmall) }
            if (svg == null && error == null) Text("正在生成二维码")
            InlineError(error)
            val others = urls.filter { it != primary }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { controller.copy(address) }) { Text("复制链接") }
                if (others.isNotEmpty()) TextButton(onClick = { showOther = !showOther }) { Text(if (showOther) "收起其他地址" else "其他可用地址（${others.size} 个）") }
            }
            if (others.isNotEmpty()) {
                if (showOther) {
                    Text("选择与另一台设备处于同一局域网的地址，二维码会随之更新", style = MaterialTheme.typography.bodySmall)
                    others.forEach { url -> TextButton(onClick = { primary = url }) { Text("使用此地址\n$url") } }
                }
            }
        }
    } else Text("下载或上传链接已失效")
}

@Composable
fun TransferSessionFacts(session: JsonObject, now: Long) {
    val receiving = session["mode"]?.jsonPrimitive?.content == "receive"
    val active = session["active"]?.jsonPrimitive?.booleanOrNull == true
    val completed = session["completedFiles"]?.jsonPrimitive?.longOrNull ?: 0
    Text(if (receiving) "已接收 $completed 个文件" else "已完成 $completed 次下载 · 已中断 ${session["interruptedDownloads"]?.jsonPrimitive?.longOrNull ?: 0} 次")
    Text("当前连接 ${session["activeConnections"]?.jsonPrimitive?.intOrNull ?: 0} 个 · 累计${if (receiving) "接收" else "发送"} ${readableBytes(session["transferredBytes"]?.jsonPrimitive?.longOrNull)}")
    Text("创建时间  " + transferTime(session["createdAt"]?.jsonPrimitive?.longOrNull), style = MaterialTheme.typography.bodySmall)
    Text("到期时间  " + transferTime(session["expiresAt"]?.jsonPrimitive?.longOrNull), style = MaterialTheme.typography.bodySmall)
    if (active) Text("剩余有效期  " + remainingTime(session["expiresAt"]?.jsonPrimitive?.longOrNull, now), color = MaterialTheme.colorScheme.primary)
    if (session["state"]?.jsonPrimitive?.content == "closing") Text("正在等待传输连接结束，随后清理临时文件", style = MaterialTheme.typography.bodySmall)
}

@Composable
fun TransferManagerPage(controller: AppController) {
    var sessions by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var filter by remember { mutableStateOf("active") }
    var section by remember { mutableStateOf("shares") }
    var more by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var stopAll by remember { mutableStateOf(false) }
    var qrSession by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() { sessions = controller.execute("transfer.sessions", jsonObject()).jsonObject.getValue("sessions").jsonArray.map { it.jsonObject }; now = System.currentTimeMillis() }
    fun action(id: String, arguments: JsonObject = jsonObject()) { scope.launch {
        busy = true; error = null
        try { controller.execute(id, arguments); refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = UiText.failure(failure) }
        finally { busy = false }
    } }
    LaunchedEffect(Unit) {
        while (isActive) {
            try { refresh() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = UiText.failure(failure) }
            delay(1000)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = section == "shares", onClick = { section = "shares" }, label = { Text("分享与接收") })
        FilterChip(selected = section == "downloads", onClick = { section = "downloads" }, label = { Text("文件下载") })
    }
    InlineError(error)
    if (section == "downloads") {
        val downloads = controller.tasks.filter { it.toolId == "transfer.download" }.sortedByDescending { it.createdAt }
        if (downloads.isEmpty()) Text("还没有下载记录", Modifier.padding(vertical = 18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { downloads.forEach { task -> key(task.id) { TaskCard(controller, task, showResult = false) } } }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            ChoiceField("", filter, listOf("active" to "运行中", "closed" to "已结束", "all" to "全部会话"), { filter = it }, Modifier.weight(1f))
            Box {
                TextButton(onClick = { more = true }) { Text("更多") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("关闭全部会话") }, onClick = { more = false; stopAll = true }, enabled = !busy && sessions.any { it["active"]?.jsonPrimitive?.booleanOrNull == true })
                    DropdownMenuItem(text = { Text("清理已结束记录") }, onClick = { more = false; action("transfer.clearClosed") }, enabled = !busy && sessions.any { it["state"]?.jsonPrimitive?.content == "closed" })
                }
            }
        }
        val visible = sessions.filter { filter == "all" || (it["state"]?.jsonPrimitive?.content != "closed") == (filter == "active") }
        if (visible.isEmpty()) Text(if (filter == "active") "没有正在运行的分享或接收" else "没有符合条件的记录", Modifier.padding(vertical = 18.dp))
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            visible.forEach { session -> key(session.getValue("shareId").jsonPrimitive.content) {
                val id = session.getValue("shareId").jsonPrimitive.content
                val active = session["active"]?.jsonPrimitive?.booleanOrNull == true
                val receiving = session["mode"]?.jsonPrimitive?.content == "receive"
                var details by remember { mutableStateOf(false) }
                var history by remember { mutableStateOf(false) }
                var files by remember { mutableStateOf(false) }
                var minutes by remember { mutableStateOf("10") }
                Panel(Modifier.fillMaxWidth(), spacing = 8.dp) {
                    Text((if (receiving) "接收文件" else session["name"]?.jsonPrimitive?.contentOrNull ?: "文件分享") + " · " + if (active) "运行中" else if (session["state"]?.jsonPrimitive?.content == "closing") "关闭中" else "已结束", style = MaterialTheme.typography.titleMedium)
                    Text("已完成 ${session["completedFiles"]?.jsonPrimitive?.content ?: "0"} ${if (receiving) "个文件" else "次下载"} · 当前 ${session["activeConnections"]?.jsonPrimitive?.content ?: "0"} 个连接 · 累计${if (receiving) "接收" else "发送"} ${readableBytes(session["transferredBytes"]?.jsonPrimitive?.longOrNull)}", style = MaterialTheme.typography.bodySmall)
                    if (active) Text("剩余 " + remainingTime(session["expiresAt"]?.jsonPrimitive?.longOrNull, now), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (active) {
                            OutlinedButton(onClick = { qrSession = id }) { Text("二维码") }
                            TextButton(onClick = { session["primaryUrl"]?.jsonPrimitive?.contentOrNull?.let(controller::copy) }) { Text("复制链接") }
                            TextButton(onClick = { action("transfer.stopShare", jsonObject("shareId" to id)) }, enabled = !busy) { Text(if (receiving) "停止接收" else "停止分享") }
                        }
                        TextButton(onClick = { details = !details }) { Text(if (details) "收起详情" else "详情") }
                    }
                    if (details) {
                        TransferSessionFacts(session, now)
                        if (active) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(minutes, { minutes = it }, Modifier.weight(1f), label = { Text("延长有效期（分钟）") }, singleLine = true)
                                OutlinedButton(onClick = { action("transfer.extendShare", jsonObject("shareId" to id, "seconds" to minutes.toInt() * 60)) }, enabled = !busy && minutes.toIntOrNull() in 1..1440) { Text("延长") }
                            }
                            Text("从原到期时间增加，剩余有效期最多 24 小时", style = MaterialTheme.typography.bodySmall)
                        }
                        val items = (session["files"] as? JsonArray).orEmpty()
                        if (items.isNotEmpty()) {
                            TextButton(onClick = { files = !files }) { Text(if (files) "收起文件列表" else "查看 ${items.size} 个文件") }
                            if (files) {
                                if (receiving) ResultView(controller, jsonObject("files" to items), "transfer.received")
                                else items.forEach { item -> Text(item.jsonObject.getValue("name").jsonPrimitive.content + "  " + readableBytes(item.jsonObject["size"]?.jsonPrimitive?.longOrNull)) }
                            }
                        }
                        val attempts = (session["downloadHistory"] as? JsonArray).orEmpty()
                        if (!receiving && attempts.isNotEmpty()) {
                            TextButton(onClick = { history = !history }) { Text(if (history) "收起下载记录" else "查看逐次下载记录") }
                            if (history) {
                                Text("显示最近 100 次下载，累计统计包含全部下载；完成表示服务端已发送完整内容", style = MaterialTheme.typography.bodySmall)
                                attempts.reversed().forEach { item ->
                                    val download = item.jsonObject
                                    val status = download["state"]?.jsonPrimitive?.content
                                    Text("第 ${download["id"]?.jsonPrimitive?.content} 次 · " + when (status) { "completed" -> "已完成"; "interrupted" -> "已中断"; else -> "正在发送" }, style = MaterialTheme.typography.titleSmall)
                                    Text((download["peer"]?.jsonPrimitive?.content ?: "") + " · " + readableBytes(download["bytes"]?.jsonPrimitive?.longOrNull) + " / " + readableBytes(download["totalBytes"]?.jsonPrimitive?.longOrNull), style = MaterialTheme.typography.bodySmall)
                                    Text(transferTime(download["startedAt"]?.jsonPrimitive?.longOrNull) + (download["endedAt"]?.jsonPrimitive?.longOrNull?.let { " → " + transferTime(it) } ?: ""), style = MaterialTheme.typography.bodySmall)
                                    val total = download["totalBytes"]?.jsonPrimitive?.longOrNull
                                    if (status == "running" && total != null && total > 0) LinearProgressIndicator(progress = { ((download["bytes"]?.jsonPrimitive?.longOrNull ?: 0).toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                    download["error"]?.jsonPrimitive?.contentOrNull?.let { Text(UiText.error(it), style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        session["error"]?.jsonPrimitive?.contentOrNull?.let { InlineError("最近一次传输提示：" + UiText.error(it)) }
                    }
                }
            } }
        }
    }
    qrSession?.let { selected ->
        val session = sessions.firstOrNull { it["shareId"]?.jsonPrimitive?.content == selected }
        AlertDialog(onDismissRequest = { qrSession = null }, title = { Text("链接与二维码") }, text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session != null) TransferAddressPanel(controller, session) else Text("会话已结束")
            }
        }, confirmButton = { TextButton(onClick = { qrSession = null }) { Text("关闭") } })
    }
    if (stopAll) AlertDialog(onDismissRequest = { stopAll = false }, title = { Text("关闭全部传输会话") }, text = { Text("正在进行的下载和上传会中断，所有分享与上传链接都会失效，已接收完成的文件仍保留") }, confirmButton = { TextButton(onClick = { stopAll = false; action("transfer.stopAll") }) { Text("确认关闭") } }, dismissButton = { TextButton(onClick = { stopAll = false }) { Text("返回") } })
}
