// 以自适应中文分组展示工具结果，提供复制、保存、文件预览和可展开的原始数据
package io.kaffubox.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.kaffubox.core.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.launch
import kotlin.math.abs

private val PrettyJson = Json { prettyPrint = true }

private fun fileResults(result: JsonElement): List<FileRef> = when (result) {
    is JsonObject -> {
        val ref = if (result["id"] is JsonPrimitive && result["name"] is JsonPrimitive) runCatching { CoreJson.decodeFromJsonElement(FileRef.serializer(), result) }.getOrNull() else null
        if (ref != null) listOf(ref) else result.values.flatMap(::fileResults)
    }
    is JsonArray -> result.flatMap(::fileResults)
    else -> emptyList()
}

@Composable
fun ResultView(controller: AppController, result: JsonElement, toolId: String = "") {
    val obj = result as? JsonObject
    val refs = remember(result) { fileResults(result).distinctBy { it.id } }
    val copyText = remember(result, toolId) { resultText(result, toolId) { id -> runCatching { controller.files.resolve(id).ref.name }.getOrDefault("文件引用已失效") } }
    val scope = rememberCoroutineScope()
    var raw by remember(result, toolId) { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("处理结果", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { controller.copy(copyText) }) { Icon(Icons.Outlined.ContentCopy, "复制结果") }
            IconButton(onClick = { controller.saveText(copyText, resultFileName(toolId, result)) }) { Icon(Icons.Outlined.SaveAlt, "保存结果") }
        }
        if (refs.any { it.writable }) Text("文件已保存", color = MaterialTheme.colorScheme.primary)
        if (toolId == "file.hash") (obj?.get("matches") as? JsonPrimitive)?.booleanOrNull?.let { matches -> Text(if (matches) "校验通过，摘要与预期一致" else "校验不一致，请检查文件或预期摘要", color = if (matches) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        when {
            toolId == "network.diagnose" && obj != null -> NetworkDiagnosisResult(controller, obj)
            toolId == "media.probe" && obj != null -> MediaInfoView(obj)
            obj?.containsKey("points") == true -> FunctionPlot(obj.getValue("points").jsonArray)
            toolId == "text.diff" && obj?.get("changes") is JsonArray -> {
                Text(if (obj["equal"]?.jsonPrimitive?.boolean == true) "两份文本相同" else "绿色为新增，红色为删除", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    obj.getValue("changes").jsonArray.forEach { item ->
                        val change = item.jsonObject; val kind = change.getValue("kind").jsonPrimitive.content
                        val color = when (kind) { "insert" -> MaterialTheme.colorScheme.primaryContainer; "delete" -> MaterialTheme.colorScheme.errorContainer; else -> MaterialTheme.colorScheme.surface }
                        Row(Modifier.fillMaxWidth().background(color).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text((change["leftLine"]?.jsonPrimitive?.content ?: "") + " / " + (change["rightLine"]?.jsonPrimitive?.content ?: ""), Modifier.width(70.dp), style = MaterialTheme.typography.bodySmall)
                            Text(when (kind) { "insert" -> "+"; "delete" -> "−"; else -> " " }, Modifier.width(18.dp))
                            SelectionContainer { Text(change.getValue("text").jsonPrimitive.content) }
                        }
                    }
                }
            }
            toolId == "file.duplicates" && obj?.get("groups") is JsonArray -> {
                val groups = obj.getValue("groups").jsonArray
                val selected = remember(result) { mutableStateMapOf<String, FileRef>() }
                Text(if (groups.isEmpty()) "没有发现重复文件" else "发现 " + groups.size + " 组重复文件")
                groups.forEachIndexed { index, group ->
                    Text("第 " + (index + 1) + " 组", style = MaterialTheme.typography.titleSmall)
                    group.jsonArray.forEach { id ->
                        val ref = runCatching { controller.files.resolve(id.jsonPrimitive.content).ref }.getOrNull()
                        if (ref != null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(ref.id in selected, { if (it) selected[ref.id] = ref else selected.remove(ref.id) }); Text(ref.name, Modifier.weight(1f)); TextButton(onClick = { controller.revealFile(ref) }) { Text(controller.fileLocationActionLabel) } }
                    }
                }
                if (selected.isNotEmpty()) WorkspaceResultActions(controller, jsonObject("files" to CoreJson.encodeToJsonElement(selected.values.toList())), toolId)
            }
            toolId == "document.csvParse" && obj?.get("rows") is JsonArray -> {
                val rows = obj.getValue("rows").jsonArray
                Text(rows.size.toString() + " 行")
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    rows.forEach { row -> Row { row.jsonArray.forEach { cell -> Surface(border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) { SelectionContainer { Text(cell.jsonPrimitive.content, Modifier.width(180.dp).padding(10.dp)) } } } } }
                }
            }
            toolId == "transfer.share" && obj != null -> ShareResultView(controller, obj)
            toolId == "transfer.receive" && obj?.get("urls") is JsonArray -> {
                var stopped by remember(result) { mutableStateOf(false) }
                var shareCode by remember(result) { mutableStateOf<JsonElement?>(null) }
                obj.getValue("urls").jsonArray.forEach { url ->
                    SelectionContainer { Text(url.jsonPrimitive.content) }
                    Row {
                        TextButton(onClick = { controller.copy(url.jsonPrimitive.content) }) { Text("复制链接") }
                        TextButton(onClick = { scope.launch { try { shareCode = controller.execute("developer.qrCode", jsonObject("text" to url.jsonPrimitive.content, "size" to 320)) } catch (failure: Exception) { controller.report(failure, "生成二维码失败") } } }) { Text("显示二维码") }
                    }
                }
                obj["expiresAt"]?.jsonPrimitive?.longOrNull?.let { expiry -> Text("到期时间  " + java.time.Instant.ofEpochMilli(expiry).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")), style = MaterialTheme.typography.bodySmall) }
                Text("保持 KaffuBox 运行，在同一局域网的浏览器打开链接即可上传", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { scope.launch { try { controller.execute("transfer.stopShare", jsonObject("shareId" to obj.getValue("shareId"))); stopped = true } catch (failure: Exception) { controller.report(failure, "关闭会话失败") } } }, enabled = !stopped) { Text(if (stopped) "会话已关闭" else "关闭此会话") }
                shareCode?.let { ResultView(controller, it, "developer.qrCode") }
            }
            obj?.containsKey("svg") == true -> {
                SvgPreview(obj.getValue("svg").jsonPrimitive.content, Modifier.size(300.dp).align(Alignment.CenterHorizontally).background(Color.White))
                Text("可复制 SVG 或保存为矢量图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            obj?.containsKey("cells") == true -> {
                SelectionContainer { Text(copyText, Modifier.horizontalScroll(rememberScrollState()), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
            }
            refs.isNotEmpty() -> {
                if (toolId == "file.unzip") Text("已解压 ${refs.size} 个文件，文件集中保存到输出位置，原路径显示在各文件下方", color = MaterialTheme.colorScheme.onSurfaceVariant)
                refs.forEach { ref ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.InsertDriveFile, null, Modifier.size(24.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) { SelectionContainer { Text(ref.name) }; Text(readableBytes(ref.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        (obj?.get("entries") as? JsonArray)?.firstOrNull { (it as? JsonObject)?.get("file")?.let { file -> (file as? JsonObject)?.get("id") == JsonPrimitive(ref.id) } == true }?.jsonObject?.get("entry")?.jsonPrimitive?.contentOrNull?.let { path ->
                            SelectionContainer { Text("压缩包内路径：$path", style = MaterialTheme.typography.bodySmall) }
                        }
                        Row(Modifier.align(Alignment.End)) {
                        TextButton(onClick = { controller.openFile(ref) }) { Text("打开") }
                        TextButton(onClick = { controller.revealFile(ref) }) { Text(controller.fileLocationActionLabel) }
                        }
                    }
                    if (refs.size <= 3 && ref.looksLikeImage()) ImageFilePreview(controller, ref, 260.dp)
                }
                val details = obj?.filterKeys { it !in setOf("file", "files", "entries", "layout") }.orEmpty()
                if (details.isNotEmpty()) ResultSectionsView(remember(details) { readableResultSections(JsonObject(details)) })
            }
            else -> ResultSectionsView(remember(result, toolId) { readableResultSections(result, toolId) })
        }
        WorkspaceResultActions(controller, result, toolId)
        TextButton(onClick = { raw = !raw }) { Text(if (raw) "收起原始数据" else if (toolId == "media.probe") "查看原始 streams 与完整数据" else "查看原始数据") }
        if (raw) OutlinedTextField(PrettyJson.encodeToString(JsonElement.serializer(), result), {}, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 330.dp), readOnly = true, textStyle = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ResultSectionsView(sections: List<ResultSection>) {
    var visible by remember(sections) { mutableStateOf(20) }
    sections.take(visible).forEachIndexed { index, section ->
        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        if (section.title.isNotEmpty()) Text(section.title, style = MaterialTheme.typography.titleSmall)
        section.note?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } }
        var rowCount by remember(section) { mutableStateOf(40) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 440.dp
            val labelWidth = (maxWidth * 0.3f).coerceAtMost(180.dp)
            Column(verticalArrangement = Arrangement.spacedBy(if (narrow) 12.dp else 8.dp)) {
                section.rows.take(rowCount).forEach { (label, value) ->
                    if (narrow) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ResultFieldValue(value, Modifier.fillMaxWidth())
                    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(label, Modifier.width(labelWidth), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ResultFieldValue(value, Modifier.weight(1f))
                    }
                }
            }
        }
        if (section.rows.size > rowCount) TextButton(onClick = { rowCount += 40 }) { Text("显示更多字段（剩余 ${section.rows.size - rowCount} 项）") }
        section.content?.let { content ->
            if (content.isEmpty()) Text("输出为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else OutlinedTextField(content, {}, Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 360.dp), readOnly = true,
                textStyle = if (section.code) MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) else MaterialTheme.typography.bodyMedium)
        }
    }
    if (sections.size > visible) TextButton(onClick = { visible += 20 }) { Text("显示更多分组（剩余 ${sections.size - visible} 组）") }
}

@Composable
private fun ResultFieldValue(value: String, modifier: Modifier = Modifier) {
    SelectionContainer(modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) { Text(value) }
}

@Composable
private fun FunctionPlot(points: JsonArray) {
    val all = remember(points) { points.map { item -> item.jsonObject.getValue("x").jsonPrimitive.double to (item.jsonObject["y"] as? JsonPrimitive)?.doubleOrNull } }
    if (all.size < 2) { InlineError("至少需要两个采样点"); return }
    var center by remember(points) { mutableStateOf((all.first().first + all.last().first) / 2) }
    var zoom by remember(points) { mutableStateOf(1.0) }
    var picked by remember(points) { mutableStateOf<Pair<Double, Double?>?>(null) }
    val span = (all.last().first - all.first().first) / zoom
    val from = (center - span / 2).coerceIn(all.first().first, all.last().first - span)
    val values = all.filter { it.first in from..(from + span) }
    if (values.size < 2) { InlineError("当前范围采样不足，请提高采样点数"); return }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { center = (center - span / 4).coerceAtLeast(all.first().first + span / 2) }) { Text("向左") }
        TextButton(onClick = { zoom = (zoom * 2).coerceAtMost(minOf(16.0, (all.size - 1) / 2.0).coerceAtLeast(1.0)); picked?.let { center = it.first } }) { Text("放大") }
        TextButton(onClick = { zoom = (zoom / 2).coerceAtLeast(1.0) }) { Text("缩小") }
        TextButton(onClick = { center = (center + span / 4).coerceAtMost(all.last().first - span / 2) }) { Text("向右") }
        TextButton(onClick = { zoom = 1.0; center = (all.first().first + all.last().first) / 2; picked = null }) { Text("重置视图") }
    }
    val finite = values.mapNotNull { it.second?.takeIf(Double::isFinite) }
    if (finite.isEmpty()) { InlineError("此区间内没有可绘制的有效值"); return }
    val xMin = values.first().first; val xMax = values.last().first
    val low = finite.min(); val high = finite.max(); val pad = if (high == low) maxOf(1.0, abs(low) * 0.1) else (high - low) * 0.08
    val yMin = low - pad; val yMax = high + pad
    val lineColor = MaterialTheme.colorScheme.primary; val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(300.dp).pointerInput(values) { detectTapGestures { point -> val x = xMin + point.x / size.width * (xMax - xMin); picked = values.minByOrNull { abs(it.first - x) } } }) {
        fun px(x: Double) = ((x - xMin) / (xMax - xMin) * size.width).toFloat()
        fun py(y: Double) = (size.height - (y - yMin) / (yMax - yMin) * size.height).toFloat()
        for (i in 0..6) { val x = size.width * i / 6; val y = size.height * i / 6; drawLine(grid, Offset(x, 0f), Offset(x, size.height)); drawLine(grid, Offset(0f, y), Offset(size.width, y)) }
        if (0.0 in xMin..xMax) drawLine(lineColor.copy(alpha = 0.4f), Offset(px(0.0), 0f), Offset(px(0.0), size.height))
        if (0.0 in yMin..yMax) drawLine(lineColor.copy(alpha = 0.4f), Offset(0f, py(0.0)), Offset(size.width, py(0.0)))
        val path = Path(); var connected = false; var previous = 0.0
        values.forEach { (x, y) ->
            if (y == null || !y.isFinite()) connected = false
            else { if (!connected || abs(y - previous) > (yMax - yMin) * 0.8) path.moveTo(px(x), py(y)) else path.lineTo(px(x), py(y)); connected = true; previous = y }
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
        picked?.let { (x, y) -> if (y != null && x in xMin..xMax) drawCircle(lineColor, 5.dp.toPx(), Offset(px(x), py(y))) }
    }
    Text("x：" + formatNumber(xMin.toString(), 6) + " 至 " + formatNumber(xMax.toString(), 6) + "    y：" + formatNumber(yMin.toString(), 6) + " 至 " + formatNumber(yMax.toString(), 6), style = MaterialTheme.typography.bodySmall)
    Text(picked?.let { "采样点 x = ${formatNumber(it.first.toString(), 12)}，y = ${it.second?.let { value -> formatNumber(value.toString(), 12) } ?: "此处无定义"}" } ?: "点击曲线查看邻近采样点，放大只改变视图，不增加计算精度", style = MaterialTheme.typography.bodySmall)
}
