// 将工具结果转换为中文分组、带单位的字段和可复制内容，保留代码生成工具的目标格式
package io.kaffubox.app

import kotlinx.serialization.json.*
import io.kaffubox.core.CoreJson
import io.kaffubox.core.FileRef
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ResultSection(
    val title: String = "",
    val rows: List<Pair<String, String>> = emptyList(),
    val note: String? = null,
    val content: String? = null,
    val code: Boolean = false
)

private val ResultJson = Json { prettyPrint = true }
private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.items(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.objectAt(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.sizeText(key: String): String = readableBytes(text(key)?.toLongOrNull())
private fun JsonElement?.plain(): String = when (this) {
    null, JsonNull -> "未提供"
    is JsonPrimitive -> (if (isString) null else booleanOrNull)?.let { if (it) "是" else "否" } ?: content.ifEmpty { "（空文本）" }
    is JsonArray -> if (isEmpty()) "无" else joinToString("\n") { it.plain() }
    is JsonObject -> if (isEmpty()) "无" else entries.joinToString("\n") { resultLabel(it.key) + "：" + it.value.plain() }
}

private val ResultLabels = mapOf(
    "real" to "实部", "imaginary" to "虚部", "structure" to "结构", "originalName" to "原文件名", "matches" to "摘要与预期一致", "expectedHash" to "预期摘要",
    "value" to "结果", "values" to "结果列表", "text" to "文本", "title" to "标题", "hash" to "校验摘要", "algorithm" to "算法",
    "date" to "日期", "days" to "相隔天数", "dayOfWeek" to "星期", "leapYear" to "是否闰年", "root" to "近似根",
    "residual" to "代入后的函数值", "iterations" to "迭代次数", "converged" to "是否达到误差要求", "precision" to "有效数字位数", "angleMode" to "角度单位", "unit" to "单位",
    "codePoints" to "Unicode 字符数", "utf16Units" to "UTF-16 代码单元数", "utf8Bytes" to "UTF-8 大小", "lines" to "行数", "tokens" to "文字片段数", "frequency" to "词频",
    "width" to "宽度", "height" to "高度", "format" to "格式", "size" to "大小", "name" to "名称", "mimeType" to "内容类型", "equal" to "内容相同",
    "seconds" to "秒级时间戳", "milliseconds" to "毫秒级时间戳", "iso" to "UTC 时间", "urls" to "访问链接", "url" to "访问链接", "expiresAt" to "到期时间",
    "total" to "合计", "addresses" to "IP 地址", "port" to "端口", "reachable" to "连接结果", "elapsedMillis" to "连接耗时", "error" to "原因", "errors" to "读取问题",
    "status" to "HTTP 状态码", "body" to "响应正文", "headers" to "响应头", "path" to "路径", "query" to "查询参数", "host" to "主机", "scheme" to "协议", "fragment" to "片段标识",
    "rows" to "行", "columns" to "列数", "orientation" to "图像方向", "extension" to "扩展名", "bytes" to "数据大小", "detectedType" to "检测格式", "headerHex" to "文件头（十六进制）",
    "metadataPreserved" to "保留原始元数据", "quality" to "编码画质", "file" to "文件", "files" to "文件列表", "entries" to "解压文件", "entry" to "压缩包内路径",
    "interfaces" to "网络接口", "up" to "已启用", "loopback" to "本机回环接口", "mtu" to "最大传输单元（MTU）", "certificates" to "证书链", "protocol" to "协议", "cipherSuite" to "加密套件",
    "subject" to "证书主体", "issuer" to "签发者", "serial" to "序列号", "notBefore" to "有效期起点", "notAfter" to "有效期终点", "sha256" to "SHA-256 指纹",
    "platform" to "平台", "os" to "操作系统", "version" to "系统版本", "architecture" to "处理器架构", "processors" to "可用逻辑处理器数", "manufacturer" to "制造商", "model" to "型号",
    "androidVersion" to "Android 版本", "apiLevel" to "Android API 级别", "abis" to "支持的应用架构", "memory" to "系统内存", "runtimeMemory" to "本应用内存", "storage" to "存储空间", "battery" to "电池",
    "sensors" to "传感器", "vendor" to "厂商", "type" to "类型", "resolution" to "分辨率", "maximumRange" to "最大量程", "powerMilliampere" to "标称工作电流",
    "argb" to "颜色（含透明度）", "red" to "红色通道", "green" to "绿色通道", "blue" to "蓝色通道", "alpha" to "不透明度", "count" to "数量", "stopped" to "分享已停止",
    "formats" to "支持的格式", "maxPixels" to "最大像素数", "remainingMillis" to "剩余时间", "elapsed" to "计时结束", "target" to "目标时间"
)

private fun resultLabel(key: String): String = ResultLabels[key] ?: key
private fun readableTime(value: String?): String = value?.let {
    runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")) }.getOrDefault(it)
} ?: "未提供"

private fun fieldValue(key: String, value: JsonElement): String {
    if (value == JsonNull) return "未提供"
    val content = (value as? JsonPrimitive)?.contentOrNull ?: return value.plain()
    return when (key) {
        "size", "bytes", "utf8Bytes" -> readableBytes(content.toLongOrNull())
        "width", "height" -> "$content px"
        "elapsedMillis" -> (content.toDoubleOrNull()?.let(::mediaNumber) ?: content) + " 毫秒"
        "remainingMillis" -> content.toDoubleOrNull()?.let { mediaDuration(it / 1000) } ?: content
        "dayOfWeek" -> listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日").getOrNull((content.toIntOrNull() ?: 0) - 1) ?: content
        "orientation" -> mapOf("1" to "正常", "2" to "水平镜像", "3" to "旋转 180°", "4" to "垂直镜像", "5" to "转置", "6" to "顺时针旋转 90°", "7" to "横向转置", "8" to "逆时针旋转 90°")[content] ?: "未知方向（$content）"
        "format" -> mapOf("QR_CODE" to "QR 二维码", "DATA_MATRIX" to "Data Matrix 二维码", "AZTEC" to "Aztec 二维码", "PDF_417" to "PDF417 条码", "CODE_128" to "Code 128 条码", "CODE_39" to "Code 39 条码", "CODE_93" to "Code 93 条码", "EAN_13" to "EAN-13 商品条码", "EAN_8" to "EAN-8 商品条码", "UPC_A" to "UPC-A 商品条码", "UPC_E" to "UPC-E 商品条码", "png" to "PNG", "jpeg" to "JPEG", "gif" to "GIF", "bmp" to "BMP", "webp" to "WebP", "image/png" to "PNG", "image/jpeg" to "JPEG", "image/gif" to "GIF", "image/webp" to "WebP")[content] ?: content
        "angleMode" -> when (content) { "deg" -> "角度（°）"; "rad" -> "弧度（rad）"; else -> content }
        "detectedType" -> if (content == "unknown") "未识别" else content
        "days" -> "$content 天"
        "notBefore", "notAfter", "target" -> readableTime(content)
        else -> value.plain()
    }
}

private fun rows(info: JsonObject, vararg keys: String): List<Pair<String, String>> = keys.mapNotNull { key -> info[key]?.let { resultLabel(key) to fieldValue(key, it) } }

// 未知插件字段保留其原名，递归拆成可读字段和列表，避免臆测字段含义
private fun structuredSections(value: JsonElement, title: String = ""): List<ResultSection> = when (value) {
    is JsonObject -> if (value.isEmpty()) listOf(ResultSection(title, note = "没有数据")) else buildList {
        val file = if (value["id"] is JsonPrimitive && value["name"] is JsonPrimitive) runCatching { CoreJson.decodeFromJsonElement(FileRef.serializer(), value) }.getOrNull() else null
        if (file != null) {
            add(ResultSection(title.ifEmpty { "文件" }, listOf("文件名" to file.name, "文件大小" to readableBytes(file.size)) + listOfNotNull(file.mimeType?.let { "内容类型" to it })))
            return@buildList
        }
        val fields = value.filterValues { it is JsonPrimitive }
        if (fields.isNotEmpty()) add(ResultSection(title, fields.map { resultLabel(it.key) to fieldValue(it.key, it.value) }))
        value.filterValues { it !is JsonPrimitive }.forEach { (key, child) ->
            addAll(structuredSections(child, listOf(title, resultLabel(key)).filter(String::isNotEmpty).joinToString(" · ")))
        }
    }
    is JsonArray -> when {
        value.isEmpty() -> listOf(ResultSection(title, note = "没有数据"))
        value.all { it is JsonPrimitive } -> listOf(ResultSection(title, value.mapIndexed { index, item -> "第 ${index + 1} 项" to item.plain() }))
        else -> value.flatMapIndexed { index, item -> structuredSections(item, "$title · 第 ${index + 1} 项".trimStart(' ', '·')) }
    }
    else -> listOf(ResultSection(title, content = value.plain()))
}

fun readableResultSections(result: JsonElement, toolId: String = ""): List<ResultSection> {
    val info = result as? JsonObject ?: return structuredSections(result)
    return when (toolId) {
        "document.convert" -> listOf(ResultSection("转换结果", content = info.text("text").orEmpty(), code = true))
        "network.interfaces" -> {
            val interfaces = info.items("interfaces")
            listOf(ResultSection("网络接口概览", note = if (interfaces.isEmpty()) "没有读取到网络接口" else "共 ${interfaces.size} 个接口，${interfaces.count { (it as? JsonObject)?.flag("up") == true }} 个已启用\n已启用表示系统开启了接口，不代表已经连上互联网\nMTU 是接口单次传输的最大网络层数据包大小")) + interfaces.mapIndexed { index, item ->
                val device = item.jsonObject
                val name = device.text("name") ?: "接口 ${index + 1}"
                val displayName = device.text("displayName")?.takeIf(String::isNotBlank) ?: name
                ResultSection(displayName, (if (displayName == name) emptyList() else listOf("系统标识" to name)) + listOf(
                    "状态" to when (device.flag("up")) { true -> "已启用"; false -> "未启用"; null -> "未知" },
                    "接口用途" to when (device.flag("loopback")) { true -> "本机回环，用于设备内部通信"; false -> "普通网络接口"; null -> "未知" },
                    "IP 地址" to device.items("addresses").takeIf { it.isNotEmpty() }?.plain().orEmpty().ifEmpty { "未获取到 IP 地址" },
                    "最大传输单元（MTU）" to (device.text("mtu")?.toIntOrNull()?.takeIf { it > 0 }?.let { "$it 字节" } ?: "未知")
                ))
            }
        }
        "network.dns" -> listOf(ResultSection("解析到的 IP 地址", info.items("addresses").map { address ->
            (if (address.plain().contains(':')) "IPv6" else "IPv4") to address.plain()
        }, note = if (info.items("addresses").isEmpty()) "没有解析到 IP 地址" else null))
        "network.tcp" -> listOf(ResultSection("端口连接", listOf(
            "连接结果" to when (info.flag("reachable")) { true -> "TCP 连接成功"; false -> "TCP 连接失败"; null -> "未知" }
        ) + rows(info, "elapsedMillis") + listOfNotNull(info.text("error")?.let { "原因" to when (it) {
            "SocketTimeoutException" -> "连接超时"; "UnknownHostException" -> "无法解析主机地址"; "ConnectException" -> "连接被拒绝或无法建立连接"; "NoRouteToHostException" -> "无法到达目标主机"; else -> "网络连接异常（$it）"
        } })))
        "network.url" -> listOf(ResultSection("链接组成", rows(info, "scheme", "host") + listOf(
            "端口" to (info.text("port") ?: when (info.text("scheme")?.lowercase()) { "https" -> "443（HTTPS 默认）"; "http" -> "80（HTTP 默认）"; else -> "未指定" }),
            "路径" to info.text("path").orEmpty().ifEmpty { "未指定路径" },
            "片段标识" to info.text("fragment").orEmpty().ifEmpty { "无" }
        )), ResultSection("查询参数", info.items("query").map { item ->
            val query = item.jsonObject
            query.text("key").orEmpty().ifEmpty { "（空参数名）" } to query["value"].plain()
        }, note = if (info.items("query").isEmpty()) "此链接没有查询参数" else null))
        "network.http" -> httpSections(info)
        "network.tls" -> listOf(ResultSection("TLS 连接", rows(info, "protocol", "cipherSuite"), note = "连接已通过系统证书信任与主机名校验")) + info.items("certificates").mapIndexed { index, cert ->
            ResultSection(if (index == 0) "服务端证书" else "证书链 · 第 ${index + 1} 张", rows(cert.jsonObject, "subject", "issuer", "serial", "notBefore", "notAfter", "sha256"))
        }
        "device.system" -> deviceSections(info)
        "device.sensors" -> sensorSections(info)
        "file.metadata" -> metadataSections(info)
        "file.unzip" -> listOf(ResultSection("解压结果", rows(info, "bytes"), note = if (info.items("entries").isEmpty()) "压缩包中没有可解压的文件" else "共 ${info.items("entries").size} 个文件，集中保存到输出位置")) + info.items("entries").map { item ->
            val entry = item.jsonObject
            val file = entry.objectAt("file")
            ResultSection(file.text("name") ?: "解压文件", listOf("压缩包内路径" to entry["entry"].plain(), "保存文件名" to file["name"].plain(), "文件大小" to file.sizeText("size")))
        }
        "text.statistics" -> listOf(ResultSection("文本统计", rows(info, "codePoints", "utf16Units", "utf8Bytes", "lines", "tokens"), note = "Unicode 字符数按码点统计，组合字符和部分表情可能占多个码点\n文字片段按连续字母或数字划分，转为小写后计数，中文不做分词"), ResultSection("出现频次", info.objectAt("frequency").map { it.key to "${it.value.plain()} 次" }, note = if (info.objectAt("frequency").isEmpty()) "没有可统计的文字片段" else "按次数从高到低排列，最多列出 1000 项"))
        "developer.jwt" -> listOf(ResultSection("解码结果", note = "未验证签名，以下内容仅为解码结果，不能据此确认令牌真实有效")) + jwtSections(info["header"] ?: JsonNull, "令牌头部") + jwtSections(info["payload"] ?: JsonNull, "令牌载荷")
        "developer.yamlToJson", "developer.tomlToJson" -> listOf(ResultSection("JSON", content = ResultJson.encodeToString(JsonElement.serializer(), info["value"] ?: JsonNull), code = true))
        "document.markdownToHtml" -> listOf(ResultSection("HTML", content = info.text("html").orEmpty(), code = true))
        "science.root" -> listOf(ResultSection("求根结果", rows(info, "root", "residual", "iterations"), note = when (info.flag("converged")) { true -> "已达到设定的函数值误差要求"; false -> "尚未达到设定的函数值误差要求，请调整区间或误差容限"; null -> "未提供收敛状态" }))
        "science.timezone" -> listOf(ResultSection("各时区时间", info.objectAt("values").map { (zone, value) -> zone to runCatching { ZonedDateTime.parse(value.jsonPrimitive.content).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")) }.getOrDefault(value.plain()) }))
        "developer.timestamp" -> listOf(ResultSection("日期与时间戳", listOf("本地时间" to readableTime(info.text("iso")), "UTC 时间" to (info.text("iso") ?: "未知")) + rows(info, "seconds", "milliseconds"), note = "时间戳从 1970-01-01 00:00:00 UTC 起算，本地时间包含时区偏移"))
        "file.compare" -> listOf(ResultSection(note = when (info.flag("equal")) { true -> "两个文件的内容完全相同"; false -> "两个文件的内容不同"; null -> "未提供比较结果" }))
        "transfer.stopShare" -> listOf(ResultSection(note = if (info.flag("stopped") == true) "文件分享已停止" else "没有找到正在运行的这次分享，可能已经停止或过期"))
        "transfer.share" -> listOf(ResultSection("文件分享", listOfNotNull(
            "访问链接" to ((info.text("primaryUrl") ?: info.items("urls").firstOrNull()?.plain()).orEmpty().ifEmpty { "没有可用的局域网地址" }),
            "分享文件" to (info.items("files").size.toString() + " 个"),
            (info["archive"] as? JsonObject)?.let { archive -> "临时 ZIP" to (archive["name"]?.plain() + "（" + archive.sizeText("size") + "）") },
            info.text("downloads")?.takeIf { it != "0" }?.let { "已开始下载" to "$it 次" },
            "到期时间" to (info.text("expiresAt")?.toLongOrNull()?.let { readableTime(Instant.ofEpochMilli(it).toString()) } ?: "未知")
        ), note = if (info.flag("active") == true) "保持 KaffuBox 运行以继续分享，链接使用 HTTP 传输\n到期、主动停止或应用退出后关闭连接并删除临时文件" else "分享已结束，连接已关闭，临时文件已删除"))
        "daily.dice" -> listOf(ResultSection("掷骰结果", info.items("values").mapIndexed { index, value -> "第 ${index + 1} 颗" to "${value.plain()} 点" } + ("合计" to "${info["total"].plain()} 点")))
        "daily.draw" -> listOf(ResultSection("抽签结果", info.items("values").mapIndexed { index, value -> "第 ${index + 1} 项" to value.plain() }, note = if (info.items("values").isEmpty()) "没有抽中任何项目" else null))
        "developer.uuid", "text.unicode" -> if (info["values"] is JsonArray) listOf(ResultSection(if (toolId == "developer.uuid") "UUID" else "Unicode 码点", content = info.items("values").joinToString("\n") { it.jsonPrimitive.content }, code = true)) else textSections(info, toolId)
        else -> if (info["text"] is JsonPrimitive || info["hash"] is JsonPrimitive || info["value"] is JsonPrimitive) textSections(info, toolId) else structuredSections(info)
    }
}

private fun textSections(info: JsonObject, toolId: String): List<ResultSection> {
    val key = listOf("text", "hash", "value").firstOrNull { info[it] is JsonPrimitive && info[it] != JsonNull } ?: return structuredSections(info)
    val value = info[key]!!.jsonPrimitive
    if (!value.isString && value.booleanOrNull != null) return structuredSections(info)
    val rest = info.filterKeys { it != key && !(key == "text" && it == "value") }
    val details = if (toolId == "text.lines") listOf(ResultSection(rows = listOf("整理后行数" to info["count"].plain()))) else if (rest.isEmpty()) emptyList() else structuredSections(JsonObject(rest))
    return details + ResultSection(
        if (key == "hash") "校验摘要" else "输出内容", content = info.text(key).orEmpty(),
        code = toolId.startsWith("developer.") || key == "hash" || toolId == "document.csvWrite"
    )
}

private fun httpSections(info: JsonObject): List<ResultSection> {
    val status = info.text("status")?.toIntOrNull()
    val meaning = mapOf(200 to "请求成功", 201 to "已创建", 204 to "无响应正文", 301 to "永久重定向", 302 to "临时重定向", 304 to "资源未修改", 307 to "临时重定向", 308 to "永久重定向", 400 to "请求有误", 401 to "需要身份验证", 403 to "拒绝访问", 404 to "未找到资源", 405 to "请求方法不受支持", 408 to "请求超时", 429 to "请求过于频繁", 500 to "服务端内部错误", 502 to "网关错误", 503 to "服务暂不可用", 504 to "网关超时")[status]
        ?: when (status) { in 100..199 -> "提示信息"; in 200..299 -> "请求成功"; in 300..399 -> "重定向或缓存响应"; in 400..499 -> "请求未被接受"; in 500..599 -> "服务端错误"; else -> "未知状态" }
    val headers = info.objectAt("headers")
    val binary = info.text("bodyBase64") != null
    val body = info.text("body").orEmpty()
    val parsedBody = runCatching { ResultJson.parseToJsonElement(body) }.getOrNull()
    val displayedBody = parsedBody?.let { ResultJson.encodeToString(JsonElement.serializer(), it) } ?: body
    return listOf(ResultSection("响应概览", listOf("HTTP 状态" to "${status ?: "未知"} · $meaning", "已读取大小" to info.sizeText("bytes"), "读取情况" to if (info.flag("truncated") == true) "达到读取上限，仅保留部分内容" else "已读取完整响应")),
        ResultSection("响应头", headers.map { it.key to it.value.plain() }, note = if (headers.isEmpty()) "没有响应头" else null),
        ResultSection(if (binary) "响应正文（Base64）" else if (parsedBody != null) "响应正文（JSON 格式化）" else "响应正文", content = if (binary) info.text("bodyBase64").orEmpty() else displayedBody, code = true,
            note = if (binary) "内容无法按 UTF-8 文本解码，以下为 Base64 编码，可能是二进制文件或其他字符编码" else if (info.text("body").isNullOrEmpty()) "响应正文为空" else null))
}

private fun deviceSections(info: JsonObject): List<ResultSection> = buildList {
    add(ResultSection("设备与系统", rows(info, "manufacturer", "model", "os", "version", "androidVersion", "apiLevel", "architecture", "abis", "processors")))
    if (info["runtimeMemory"] is JsonObject) {
        val memory = info.objectAt("runtimeMemory")
        add(ResultSection("本应用 Java 内存", listOf("已分配堆内存" to memory.sizeText("allocated"), "已分配中的空闲量" to memory.sizeText("free"), "堆内存上限" to memory.sizeText("maximum")), note = "这里显示 KaffuBox 的 Java 堆内存，不是电脑的物理内存或应用总占用"))
    }
    if (info["memory"] is JsonObject) {
        val memory = info.objectAt("memory")
        add(ResultSection("系统内存", listOf("总内存" to memory.sizeText("total"), "可用内存" to memory.sizeText("available"), "内存状态" to when (memory.flag("lowMemory")) { true -> "系统报告内存不足"; false -> "正常"; null -> "未知" })))
    }
    when (val storage = info["storage"]) {
        is JsonArray -> {
            if (storage.isEmpty()) add(ResultSection("存储空间", note = "没有读取到可访问的存储空间"))
            storage.forEachIndexed { index, item -> val disk = item.jsonObject
                add(ResultSection(disk.text("name")?.takeIf(String::isNotBlank) ?: "存储空间 ${index + 1}", listOf("文件系统" to disk["type"].plain(), "总容量" to disk.sizeText("total"), "当前可用" to disk.sizeText("usable"))))
            }
        }
        is JsonObject -> add(ResultSection("应用所在存储空间", listOf("总容量" to storage.sizeText("total"), "当前可用" to storage.sizeText("available")), note = "统计应用所在的存储分区"))
        else -> Unit
    }
    if (info["battery"] is JsonObject) {
        val battery = info.objectAt("battery")
        add(ResultSection("电池", listOf("剩余电量" to (battery.text("capacityPercent")?.let { "$it%" } ?: "未获取到电量"), "充电状态" to when (battery.flag("charging")) { true -> "正在充电"; false -> "未在充电"; null -> "未知" })))
    }
}

private fun sensorSections(info: JsonObject): List<ResultSection> {
    val sensors = info.items("sensors")
    val types = mapOf(
        "accelerometer" to ("加速度" to "m/s²"), "accelerometer_uncalibrated" to ("未校准加速度" to "m/s²"), "gravity" to ("重力" to "m/s²"), "linear_acceleration" to ("线性加速度" to "m/s²"),
        "gyroscope" to ("陀螺仪" to "rad/s"), "gyroscope_uncalibrated" to ("未校准陀螺仪" to "rad/s"), "magnetic_field" to ("磁场" to "μT"), "magnetic_field_uncalibrated" to ("未校准磁场" to "μT"),
        "light" to ("环境光" to "lx"), "pressure" to ("气压" to "hPa"), "proximity" to ("距离" to "cm"), "ambient_temperature" to ("环境温度" to "°C"), "relative_humidity" to ("相对湿度" to "%"),
        "step_counter" to ("步数计数器" to "步"), "step_detector" to ("步伐检测" to ""), "rotation_vector" to ("旋转向量" to ""), "game_rotation_vector" to ("游戏旋转向量" to ""), "geomagnetic_rotation_vector" to ("地磁旋转向量" to ""), "significant_motion" to ("显著运动检测" to ""), "heart_rate" to ("心率" to "次/分钟")
    )
    return listOf(ResultSection("传感器概览", note = if (sensors.isEmpty()) "没有读取到可用传感器" else "共 ${sensors.size} 个传感器，以下为硬件报告的规格，不是实时测量值")) + sensors.mapIndexed { index, item ->
        val sensor = item.jsonObject
        val type = sensor.text("type").orEmpty()
        val known = if (type.startsWith("android.sensor.")) types[type.removePrefix("android.sensor.")] else null
        fun measurement(key: String): String = sensor.text(key)?.let { value -> value + (known?.second?.takeIf(String::isNotEmpty)?.let { " $it" } ?: "（厂商报告值）") } ?: "未提供"
        ResultSection(sensor.text("name") ?: "传感器 ${index + 1}", listOf("类型" to (known?.first ?: type.ifEmpty { "未知" }), "厂商" to sensor["vendor"].plain(), "分辨率" to measurement("resolution"), "最大量程" to measurement("maximumRange"), "标称工作电流" to (sensor.text("powerMilliampere")?.let { "$it mA" } ?: "未提供")))
    }
}

private val MetadataLabels = mapOf(
    "Image Width" to "图像宽度", "Image Height" to "图像高度", "Image Description" to "图像说明", "Make" to "设备制造商", "Model" to "设备型号", "Orientation" to "图像方向", "X Resolution" to "水平分辨率", "Y Resolution" to "垂直分辨率", "Resolution Unit" to "分辨率单位",
    "Software" to "处理软件", "Date/Time" to "修改时间", "Date/Time Original" to "拍摄时间", "Date/Time Digitized" to "数字化时间", "Artist" to "作者", "Copyright" to "版权", "Exposure Time" to "曝光时间", "F-Number" to "光圈", "Exposure Program" to "曝光程序", "ISO Speed Ratings" to "ISO 感光度", "Exif Version" to "EXIF 版本",
    "Shutter Speed Value" to "快门速度", "Aperture Value" to "光圈值", "Exposure Bias Value" to "曝光补偿", "Metering Mode" to "测光模式", "Flash" to "闪光灯", "Focal Length" to "焦距", "Focal Length 35" to "35 mm 等效焦距", "Lens Model" to "镜头型号", "Lens Make" to "镜头制造商", "White Balance Mode" to "白平衡", "Color Space" to "色彩空间",
    "Exif Image Width" to "EXIF 图像宽度", "Exif Image Height" to "EXIF 图像高度", "GPS Latitude" to "纬度", "GPS Longitude" to "经度", "GPS Altitude" to "海拔", "GPS Latitude Ref" to "纬度方向", "GPS Longitude Ref" to "经度方向", "GPS Time-Stamp" to "GPS 时间", "GPS Date Stamp" to "GPS 日期",
    "File Name" to "文件名", "File Size" to "文件大小", "File Modified Date" to "文件修改时间", "Detected File Type Name" to "检测到的格式", "Detected File Type Long Name" to "格式说明", "Detected MIME Type" to "内容类型", "Expected File Name Extension" to "建议扩展名", "Compression Type" to "压缩方式", "Data Precision" to "数据精度", "Number of Components" to "颜色分量数", "Bits Per Sample" to "采样位数",
    "Color Type" to "颜色类型", "Filter Method" to "过滤方式", "Interlace Method" to "扫描方式", "Palette Size" to "调色板颜色数", "Background Color" to "背景色", "Image Gamma" to "图像伽马值", "Pixels Per Unit X" to "水平像素密度", "Pixels Per Unit Y" to "垂直像素密度", "Unit Specifier" to "像素密度单位", "Last Modification Time" to "最后修改时间", "Textual Data" to "附加文本", "Alpha" to "透明通道", "Bits Per Pixel" to "每像素位数", "Component 1" to "颜色分量 1", "Component 2" to "颜色分量 2", "Component 3" to "颜色分量 3", "Component 4" to "颜色分量 4"
)

private fun metadataValue(name: String, value: JsonElement?): String {
    val text = value.plain()
    if (name in setOf("Image Width", "Image Height", "Exif Image Width", "Exif Image Height")) return if (text.toLongOrNull() != null) "$text px" else text.replace("pixels", "px")
    if (name in setOf("Bits Per Sample", "Bits Per Pixel", "Data Precision") && text.toIntOrNull() != null) return "$text 位"
    return when (name) {
        "Color Type" -> mapOf("Greyscale" to "灰度", "True Color" to "真彩色", "Indexed Color" to "索引色", "Greyscale with Alpha" to "灰度，含透明通道", "True Color with Alpha" to "真彩色，含透明通道")[text] ?: text
        "Filter Method" -> if (text == "Adaptive") "自适应" else text
        "Interlace Method" -> mapOf("No Interlace" to "非隔行扫描", "Adam7 Interlace" to "Adam7 隔行扫描")[text] ?: text
        "White Balance Mode" -> mapOf("Auto white balance" to "自动白平衡", "Manual white balance" to "手动白平衡")[text] ?: text
        "Orientation" -> mapOf("Top, left side (Horizontal / normal)" to "正常", "Top, right side (Mirror horizontal)" to "水平镜像", "Bottom, right side (Rotate 180)" to "旋转 180°", "Bottom, left side (Mirror vertical)" to "垂直镜像", "Right side, top (Rotate 90 CW)" to "顺时针旋转 90°", "Left side, bottom (Rotate 270 CW)" to "逆时针旋转 90°")[text] ?: text
        else -> text
    }
}

private fun metadataSections(info: JsonObject): List<ResultSection> = info.items("directories").map { item ->
    val directory = item.jsonObject
    val name = directory.text("type") ?: "元数据"
    ResultSection(mapOf("Exif IFD0" to "EXIF · 基本信息", "Exif SubIFD" to "EXIF · 拍摄参数", "Exif Thumbnail" to "EXIF · 缩略图", "GPS" to "GPS · 位置信息", "File Type" to "文件格式", "File" to "文件信息", "ICC Profile" to "ICC · 色彩配置", "PNG-IHDR" to "PNG · 图像信息", "PNG-pHYs" to "PNG · 像素密度", "PNG-tEXt" to "PNG · 附加文本", "JPEG" to "JPEG · 图像信息")[name] ?: name,
        directory.items("tags").map { tag -> val field = tag.jsonObject; val label = field.text("name") ?: "未命名属性"
            (MetadataLabels[label] ?: label) to metadataValue(label, field["value"])
        }, note = directory.items("errors").takeIf { it.isNotEmpty() }?.let { "部分信息读取失败\n" + it.plain() } ?: if (directory.items("tags").isEmpty()) "此分组没有可读取的属性" else null)
}.ifEmpty { listOf(ResultSection("元数据", note = "没有读取到元数据")) }

private fun jwtSections(value: JsonElement, title: String): List<ResultSection> {
    if (value !is JsonObject) return structuredSections(value, title)
    val labels = mapOf("alg" to "签名算法", "typ" to "令牌类型", "kid" to "密钥标识", "iss" to "签发者", "sub" to "主题", "aud" to "接收方", "exp" to "声明的过期时间", "nbf" to "声明的生效时间", "iat" to "声明的签发时间", "jti" to "令牌标识")
    return value.flatMap { (key, child) ->
        val label = labels[key]?.let { "$it（$key）" } ?: key
        val timestamp = if (key in setOf("exp", "nbf", "iat")) (child as? JsonPrimitive)?.longOrNull else null
        if (timestamp != null) listOf(ResultSection(title, listOf(label to runCatching { readableTime(Instant.ofEpochSecond(timestamp).toString()) + "（$timestamp 秒）" }.getOrDefault(child.plain()))))
        else if (child is JsonPrimitive || child is JsonArray && child.all { it is JsonPrimitive }) listOf(ResultSection(title, listOf(label to child.plain())))
        else structuredSections(child, "$title · $label")
    }.groupBy { it.title }.map { (name, sections) -> ResultSection(name, sections.flatMap { it.rows }, note = sections.mapNotNull { it.note }.takeIf { it.isNotEmpty() }?.joinToString("\n")) }.ifEmpty { listOf(ResultSection(title, note = "没有字段")) }
}

fun resultText(result: JsonElement, toolId: String = "", fileName: (String) -> String = { "文件引用 $it" }): String {
    val info = result as? JsonObject
    if (info?.get("streams") is JsonArray && info["format"] is JsonObject) return mediaInfoText(info)
    if (toolId in setOf("developer.yamlToJson", "developer.tomlToJson")) return ResultJson.encodeToString(JsonElement.serializer(), info?.get("value") ?: JsonNull)
    if (info?.get("cells") is JsonArray) return info.items("cells").joinToString("\n") { row -> row.jsonArray.joinToString("\t") { it.jsonPrimitive.content } }
    if (toolId == "document.csvParse" && info != null) return info.items("rows").joinToString("\n") { row -> row.jsonArray.joinToString("\t") { cell ->
        val text = cell.jsonPrimitive.content
        if (text.any { it in "\t\r\n\"" }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    } }
    if (info?.get("points") is JsonArray) return "x\ty\n" + info.items("points").joinToString("\n") { item -> item.jsonObject["x"].plain() + "\t" + item.jsonObject["y"].plain() }
    if (toolId == "text.diff" && info != null) return (if (info.flag("equal") == true) "两份文本相同" else "文本差异（+ 新增，− 删除）") + "\n" + info.items("changes").joinToString("\n") { item ->
        val change = item.jsonObject
        (when (change.text("kind")) { "insert" -> "+ "; "delete" -> "− "; else -> "  " }) + change.text("text").orEmpty()
    }
    if (toolId == "file.duplicates" && info != null) return info.items("groups").mapIndexed { index, group -> "第 ${index + 1} 组\n" + group.jsonArray.joinToString("\n") { fileName(it.jsonPrimitive.content) } }.joinToString("\n\n").ifEmpty { "没有发现重复文件" }
    if (toolId in setOf("developer.uuid", "text.unicode") && info?.get("values") is JsonArray) return info.items("values").joinToString("\n") { it.jsonPrimitive.content }
    for (key in listOf("text", "hash", "svg", "html")) (info?.get(key) as? JsonPrimitive)?.takeIf { it != JsonNull }?.let { return if (!it.isString && it.booleanOrNull != null) it.plain() else it.content }
    if (info?.size == 1) (info["value"] as? JsonPrimitive)?.takeIf { it != JsonNull }?.let { return if (!it.isString && it.booleanOrNull != null) it.plain() else it.content }
    return readableResultSections(result, toolId).joinToString("\n\n") { section -> listOfNotNull(section.title.takeIf(String::isNotEmpty), section.note, section.rows.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.first + "：" + it.second }, section.content).joinToString("\n") }
}

fun resultFileName(toolId: String, result: JsonElement): String = when {
    toolId == "document.convert" -> "Result." + ((result as? JsonObject)?.text("format") ?: "txt")
    result is JsonObject && result.containsKey("svg") -> "QrCode.svg"
    toolId in setOf("developer.json", "developer.yamlToJson", "developer.tomlToJson") -> "Result.json"
    toolId == "developer.jsonToYaml" -> "Result.yaml"
    toolId == "developer.xmlFormat" -> "Result.xml"
    toolId == "document.markdownToHtml" -> "Result.html"
    toolId == "document.csvParse" || toolId == "science.sampleFunction" || result is JsonObject && result.containsKey("cells") -> "Result.tsv"
    else -> "Result.txt"
}
