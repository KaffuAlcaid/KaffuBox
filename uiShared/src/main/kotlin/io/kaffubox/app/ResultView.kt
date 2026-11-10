// 展示可复制的工具结果、输出文件、二维码、矩阵、文本差异和函数曲线
package io.kaffubox.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import io.kaffubox.core.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.launch
import kotlin.math.abs

private val PrettyJson = Json { prettyPrint = true }
fun resultText(result: JsonElement): String {
    val obj = result as? JsonObject
    if (obj?.get("streams") is JsonArray && obj["format"] is JsonObject) return mediaInfoText(obj)
    val cells = obj?.get("cells") as? JsonArray
    if (cells != null) return cells.joinToString("\n") { row -> row.jsonArray.joinToString("\t") { it.jsonPrimitive.content } }
    for (key in listOf("text", "hash", "svg", "value")) (obj?.get(key) as? JsonPrimitive)?.let { return it.content }
    return PrettyJson.encodeToString(JsonElement.serializer(), result)
}
private val ResultLabels = mapOf("value" to "结果", "hash" to "校验摘要", "algorithm" to "算法", "date" to "日期", "days" to "相隔天数", "dayOfWeek" to "星期", "leapYear" to "闰年", "root" to "近似根", "residual" to "残差", "iterations" to "迭代次数", "converged" to "已收敛", "characters" to "字符数", "codePoints" to "码点", "lines" to "行数", "words" to "词数", "utf8Bytes" to "UTF-8 字节数", "width" to "宽度", "height" to "高度", "format" to "格式", "size" to "大小", "name" to "名称", "mimeType" to "MIME 类型", "same" to "内容相同", "equal" to "内容相同", "seconds" to "秒", "milliseconds" to "毫秒", "iso" to "UTC 时间", "url" to "访问链接", "shareId" to "分享标识", "expiresAt" to "到期时间", "total" to "合计", "addresses" to "地址", "port" to "端口", "connected" to "连接成功", "latencyMillis" to "耗时（毫秒）", "status" to "状态码", "statusCode" to "状态码", "body" to "响应内容", "headers" to "响应头", "path" to "路径", "query" to "查询参数", "host" to "主机", "scheme" to "协议", "values" to "结果列表", "rows" to "行数", "columns" to "列数", "orientation" to "方向", "extension" to "扩展名")

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
    val scope = rememberCoroutineScope()
    var raw by remember(result) { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("处理结果", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (refs.isEmpty()) {
            IconButton(onClick = { controller.copy(resultText(result)) }) { Icon(Icons.Outlined.ContentCopy, "复制结果") }
            IconButton(onClick = { controller.saveText(resultText(result), if (obj?.containsKey("svg") == true) "QrCode.svg" else "Result.txt") }) { Icon(Icons.Outlined.SaveAlt, "保存结果") }
            } else if (refs.any { it.writable }) Text("文件已保存", color = MaterialTheme.colorScheme.primary)
        }
        when {
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
                Text(if (groups.isEmpty()) "没有发现重复文件" else "发现 " + groups.size + " 组重复文件")
                groups.forEachIndexed { index, group ->
                    Text("第 " + (index + 1) + " 组", style = MaterialTheme.typography.titleSmall)
                    group.jsonArray.forEach { id ->
                        val ref = runCatching { controller.files.resolve(id.jsonPrimitive.content).ref }.getOrNull()
                        if (ref != null) Row(verticalAlignment = Alignment.CenterVertically) { Text(ref.name, Modifier.weight(1f)); TextButton(onClick = { controller.revealFile(ref) }) { Text("所在文件夹") } }
                    }
                }
            }
            toolId == "document.csvParse" && obj?.get("rows") is JsonArray -> {
                val rows = obj.getValue("rows").jsonArray
                Text(rows.size.toString() + " 行")
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    rows.forEach { row -> Row { row.jsonArray.forEach { cell -> Surface(border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) { SelectionContainer { Text(cell.jsonPrimitive.content, Modifier.width(180.dp).padding(10.dp)) } } } } }
                }
            }
            toolId == "transfer.share" && obj?.get("urls") is JsonArray -> {
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
                Text("保持 KaffuBox 运行以继续分享", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { scope.launch { try { controller.execute("transfer.stopShare", jsonObject("shareId" to obj.getValue("shareId"))); stopped = true } catch (failure: Exception) { controller.report(failure, "停止分享失败") } } }, enabled = !stopped) { Text(if (stopped) "分享已停止" else "停止分享") }
                shareCode?.let { ResultView(controller, it, "developer.qrCode") }
            }
            obj?.containsKey("svg") == true -> {
                SvgPreview(obj.getValue("svg").jsonPrimitive.content, Modifier.size(300.dp).align(Alignment.CenterHorizontally).background(Color.White))
                Text("可复制 SVG 或保存为矢量图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            obj?.containsKey("cells") == true -> {
                SelectionContainer { Text(resultText(result), Modifier.horizontalScroll(rememberScrollState()), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
            }
            refs.isNotEmpty() -> {
                refs.forEach { ref ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.InsertDriveFile, null, Modifier.size(24.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(ref.name); Text(readableBytes(ref.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { controller.openFile(ref) }) { Text("打开") }
                        TextButton(onClick = { controller.revealFile(ref) }) { Text(controller.fileLocationActionLabel) }
                    }
                    if (refs.size <= 3 && ref.looksLikeImage()) ImageFilePreview(controller, ref, 260.dp)
                }
                obj?.entries?.filter { (_, value) -> value is JsonPrimitive && value != JsonNull }?.forEach { (key, value) ->
                    val display = value.jsonPrimitive.booleanOrNull?.let { if (it) "是" else "否" } ?: value.jsonPrimitive.content
                    SelectionContainer { Text((ResultLabels[key] ?: when (key) { "detectedType" -> "检测格式"; "headerHex" -> "文件头"; "bytes" -> "字节数"; "metadataPreserved" -> "保留元数据"; "quality" -> "质量"; else -> key }) + "  " + display, style = MaterialTheme.typography.bodySmall) }
                }
            }
            obj?.containsKey("text") == true -> OutlinedTextField(obj.getValue("text").jsonPrimitive.content, {}, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp), readOnly = true)
            obj != null -> obj.entries.forEach { (key, value) ->
                if (value is JsonPrimitive) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(ResultLabels[key] ?: key, Modifier.width(125.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(if (value.booleanOrNull != null) if (value.boolean) "是" else "否" else value.content, Modifier.weight(1f)) }
                } else {
                    Text(ResultLabels[key] ?: key, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (value is JsonArray && value.all { it is JsonPrimitive }) SelectionContainer { Text(value.joinToString("\n") { it.jsonPrimitive.content }) }
                    else SelectionContainer { Text(PrettyJson.encodeToString(JsonElement.serializer(), value), Modifier.fillMaxWidth().heightIn(max = 340.dp).horizontalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
                }
            }
            else -> SelectionContainer { Text(resultText(result)) }
        }
        TextButton(onClick = { raw = !raw }) { Text(if (raw) "收起原始数据" else if (toolId == "media.probe") "查看原始 streams 与完整数据" else "查看原始数据") }
        if (raw) OutlinedTextField(PrettyJson.encodeToString(JsonElement.serializer(), result), {}, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 330.dp), readOnly = true, textStyle = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FunctionPlot(points: JsonArray) {
    val values = remember(points) { points.map { item -> item.jsonObject.getValue("x").jsonPrimitive.double to (item.jsonObject["y"] as? JsonPrimitive)?.doubleOrNull } }
    val finite = values.mapNotNull { it.second?.takeIf(Double::isFinite) }
    if (finite.isEmpty()) { InlineError("此区间内没有可绘制的有效值"); return }
    val xMin = values.first().first; val xMax = values.last().first
    val low = finite.min(); val high = finite.max(); val pad = if (high == low) maxOf(1.0, abs(low) * 0.1) else (high - low) * 0.08
    val yMin = low - pad; val yMax = high + pad
    val lineColor = MaterialTheme.colorScheme.primary; val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(300.dp)) {
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
    }
    Text("x：" + formatNumber(xMin.toString(), 6) + " 至 " + formatNumber(xMax.toString(), 6) + "    y：" + formatNumber(yMin.toString(), 6) + " 至 " + formatNumber(yMax.toString(), 6), style = MaterialTheme.typography.bodySmall)
}
