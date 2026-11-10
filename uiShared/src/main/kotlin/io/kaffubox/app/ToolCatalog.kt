// 为真实可用的工具配置中文名称、参数类型与默认值，并提供分类和插件工具描述
package io.kaffubox.app

import io.kaffubox.core.ToolDescriptor
import io.kaffubox.core.Workbench
import kotlinx.serialization.json.*

enum class FieldKind { Text, Paragraph, Integer, Number, Toggle, Choice, File, Files, Json, Lines }

data class ToolField(
    val key: String,
    val label: String,
    val kind: FieldKind = FieldKind.Text,
    val initial: String = "",
    val choices: List<Pair<String, String>> = emptyList(),
    val required: Boolean = false,
    val hint: String = "",
    val scale: Long = 1,
    val sliderRange: IntRange? = null,
    val sliderLabels: Pair<String, String> = "" to "",
    val visibleWhen: Map<String, Set<String>> = emptyMap(),
    val sendToTool: Boolean = true
) {
    fun isVisible(values: Map<String, String>): Boolean = visibleWhen.all { (key, choices) -> values[key] in choices }
}

data class UiTool(
    val id: String,
    val title: String,
    val summary: String,
    val workbench: Workbench,
    val fields: List<ToolField> = emptyList(),
    val action: String = "开始处理",
    val queued: Boolean = false,
    val batch: Boolean = false,
    val provider: String = "builtin"
)

object ToolCatalog {
    val categories = linkedMapOf(
        Workbench.Format to "格式转换", Workbench.Media to "媒体编辑", Workbench.Image to "图像工作台",
        Workbench.File to "文件工作台", Workbench.Document to "文档工作台", Workbench.Text to "文本工作台",
        Workbench.Developer to "开发者工具", Workbench.Network to "网络工具", Workbench.Device to "设备工具",
        Workbench.Transfer to "传输与共享", Workbench.Science to "科学计算", Workbench.Daily to "日常工具"
    )
    private val hidden = setOf("science.calculatorCatalog", "science.unitCatalog", "image.formats", "media.engines")
    private fun text(key: String = "text", label: String = "输入内容", required: Boolean = true) = ToolField(key, label, FieldKind.Paragraph, required = required)
    private fun number(key: String, label: String, value: String = "", required: Boolean = false) = ToolField(key, label, FieldKind.Number, value, required = required)
    private fun integer(key: String, label: String, value: String = "", required: Boolean = false) = ToolField(key, label, FieldKind.Integer, value, required = required)
    private fun toggle(key: String, label: String, value: Boolean = false) = ToolField(key, label, FieldKind.Toggle, value.toString())
    private fun choice(key: String, label: String, vararg options: Pair<String, String>, initial: String = options.first().first) = ToolField(key, label, FieldKind.Choice, initial, options.toList())
    private fun file(key: String = "file", label: String = "输入文件") = ToolField(key, label, FieldKind.File, required = true)
    private fun files() = ToolField("files", "输入文件", FieldKind.Files, required = true)
    private val output = ToolField("name", "输出文件名", hint = "留空时根据原文件自动命名")
    private val hashChoice = choice("algorithm", "算法", "SHA-256" to "SHA-256", "SHA-512" to "SHA-512", "SHA-1" to "SHA-1", "MD5" to "MD5")
    private val imageFormat = choice("format", "输出格式", "png" to "PNG", "jpeg" to "JPEG", "bmp" to "BMP", "gif" to "GIF")
    private val videoCompression = integer("quality", "视频压缩强度", "23").copy(
        sliderRange = 0..51,
        sliderLabels = "画质优先" to "体积优先",
        hint = "控制视频画质与文件体积的取舍，数值越小画质越好、文件通常越大，数值越大压缩越强、文件通常越小\n这是编码质量参数 CRF，默认 23，不是压缩百分比"
    )
    private val imageQuality = integer("quality", "JPEG 画质", "90").copy(
        sliderRange = 1..100, sliderLabels = "体积优先" to "画质优先", visibleWhen = mapOf("format" to setOf("jpeg")),
        hint = "数值越高画质越好、文件通常越大，默认 90，只对 JPEG 生效"
    )
    private val clip = listOf(number("start", "起点（秒）", "0"), number("duration", "片段时长（秒）").copy(hint = "留空处理到文件结尾"))

    private val tools: List<UiTool> = listOf(
        UiTool("science.calculate", "科学计算器", "符号键盘、函数、记忆与高精度计算", Workbench.Science),
        UiTool("science.unit", "单位换算", "长度、温度、质量、数据容量等换算", Workbench.Science),
        UiTool("science.matrix", "矩阵计算", "加减乘、转置、求逆、秩与线性方程", Workbench.Science),
        UiTool("science.root", "数值求根", "在指定区间内求解 f(x) = 0", Workbench.Science, listOf(ToolField("expression", "函数表达式", initial = "x^2-2", required = true), number("lower", "区间下限", "0", true), number("upper", "区间上限", "2", true), number("tolerance", "误差容限", "0.0000000001")), "求解"),
        UiTool("science.sampleFunction", "函数绘图", "查看函数在指定区间内的变化", Workbench.Science, listOf(ToolField("expression", "函数表达式", initial = "sin(x)", required = true), number("start", "起点", "-6.28", true), number("end", "终点", "6.28", true), integer("count", "采样点数", "401")), "绘图"),
        UiTool("science.date", "日期计算", "计算日期间隔或推算目标日期", Workbench.Science, listOf(ToolField("date", "起始日期", initial = java.time.LocalDate.now().toString(), required = true), ToolField("until", "结束日期", hint = "填写后计算两个日期相隔的天数"), integer("days", "增加天数", "0")), "计算"),
        UiTool("science.timezone", "时区对照", "查看同一时刻在不同地区的时间", Workbench.Science, listOf(ToolField("instant", "时间", hint = "留空使用当前时间，也可输入 ISO 8601 时间"), ToolField("zones", "时区，每行一项", FieldKind.Lines, "Asia/Shanghai\nAsia/Taipei\nUTC\nAmerica/New_York", required = true)), "转换"),
        UiTool("developer.encoding", "编码转换", "Base64、Base32、Hex 与 URL 双向转换", Workbench.Developer),
        UiTool("developer.json", "JSON 格式化", "格式化、压缩与排序 JSON 键名", Workbench.Developer, listOf(text(), toggle("pretty", "缩进显示", true), toggle("sortKeys", "按键名排序")), "格式化"),
        UiTool("developer.hash", "文本哈希", "计算文本的校验摘要", Workbench.Developer, listOf(text(), hashChoice), "计算"),
        UiTool("developer.jwt", "JWT 解码", "查看令牌中的头部和载荷，不进行验签", Workbench.Developer, listOf(text("token", "JWT")), "解码"),
        UiTool("developer.uuid", "UUID 生成", "批量生成随机 UUID", Workbench.Developer, listOf(integer("count", "数量", "1")), "生成"),
        UiTool("developer.radix", "进制转换", "在 2 至 36 进制之间转换整数", Workbench.Developer, listOf(ToolField("value", "数值", required = true), integer("from", "原进制", "10", true), integer("to", "目标进制", "16", true)), "转换"),
        UiTool("developer.timestamp", "时间戳", "时间戳与日期时间互相转换", Workbench.Developer, listOf(ToolField("value", "时间戳或 ISO 时间", hint = "留空使用当前时间"), choice("unit", "时间戳单位", "milliseconds" to "毫秒", "seconds" to "秒")), "转换"),
        UiTool("developer.password", "密码生成", "使用安全随机数生成密码", Workbench.Developer, listOf(integer("length", "长度", "24"), ToolField("alphabet", "可用字符", hint = "留空使用字母、数字与符号")), "生成"),
        UiTool("developer.qrCode", "生成二维码", "把文字、链接等内容转换成二维码", Workbench.Developer, listOf(text(), integer("size", "图片宽度（px）", "320")), "生成"),
        UiTool("developer.yamlToJson", "YAML 转 JSON", "解析 YAML 并转换为 JSON", Workbench.Developer, listOf(text()), "转换"),
        UiTool("developer.jsonToYaml", "JSON 转 YAML", "将 JSON 转换成 YAML", Workbench.Developer, listOf(text()), "转换"),
        UiTool("developer.tomlToJson", "TOML 转 JSON", "解析 TOML 并转换为 JSON", Workbench.Developer, listOf(text()), "转换"),
        UiTool("developer.xmlFormat", "XML 格式化", "整理 XML 结构与缩进", Workbench.Developer, listOf(text()), "格式化"),
        UiTool("text.statistics", "字数与词频", "统计字符、行数和词频", Workbench.Text, listOf(text()), "统计"),
        UiTool("text.lines", "文本整理", "去空行、去重、排序与合并行", Workbench.Text, listOf(text(), toggle("trim", "去除行首行尾空白", true), toggle("removeEmpty", "去除空行", true), toggle("deduplicate", "去除重复行", true), choice("sort", "排序方式", "none" to "保持原顺序", "ascending" to "升序", "descending" to "降序", "numeric" to "按数值排序"), ToolField("separator", "连接符", hint = "留空使用换行")), "整理文本"),
        UiTool("text.replace", "批量替换", "替换文本中的指定内容", Workbench.Text, listOf(text(), ToolField("find", "查找", required = true), ToolField("replacement", "替换为"), toggle("ignoreCase", "忽略大小写")), "替换"),
        UiTool("text.unicode", "Unicode 工具", "查看码点、转义与规范化文本", Workbench.Text, listOf(text(), choice("mode", "处理方式", "codePoints" to "查看码点", "escape" to "Unicode 转义", "unescape" to "还原转义", "NFC" to "NFC 规范化", "NFD" to "NFD 规范化", "NFKC" to "NFKC 规范化", "NFKD" to "NFKD 规范化")), "转换"),
        UiTool("text.diff", "文本比较", "逐行查看两份文本的差异", Workbench.Text, listOf(text("left", "原始文本"), text("right", "另一份文本")), "比较"),
        UiTool("document.csvParse", "读取 CSV / TSV", "把表格文本解析为结构化数据", Workbench.Document, listOf(text(), choice("delimiter", "分隔符", "," to "逗号", "\t" to "制表符", ";" to "分号")), "解析"),
        UiTool("document.csvWrite", "生成 CSV / TSV", "将二维数据导出为表格文本", Workbench.Document, listOf(ToolField("rows", "二维 JSON 数组", FieldKind.Json, "[[\"名称\",\"数量\"],[\"示例\",1]]", required = true), choice("delimiter", "分隔符", "," to "逗号", "\t" to "制表符", ";" to "分号")), "生成"),
        UiTool("document.markdownToHtml", "Markdown 转 HTML", "将 Markdown 转换为 HTML", Workbench.Document, listOf(text()), "转换"),
        UiTool("document.htmlText", "提取网页文本", "从 HTML 中提取正文文字", Workbench.Document, listOf(text("html", "HTML 内容")), "提取"),
        UiTool("file.hash", "文件校验", "流式计算文件哈希", Workbench.File, listOf(file(), hashChoice), "计算", true, true),
        UiTool("file.inspect", "文件类型", "查看文件信息与实际格式", Workbench.File, listOf(file()), "读取信息"),
        UiTool("file.metadata", "元数据查看", "读取 EXIF 等图像与媒体元数据", Workbench.File, listOf(file()), "读取"),
        UiTool("file.compare", "文件比较", "逐字节比较两个文件", Workbench.File, listOf(file("left", "文件 A"), file("right", "文件 B")), "比较", true),
        UiTool("file.duplicates", "重复文件", "按文件内容查找重复项", Workbench.File, listOf(files()), "查找", true),
        UiTool("file.split", "文件分割", "按指定大小分成多个文件", Workbench.File, listOf(file(), ToolField("partBytes", "每份大小（MiB）", FieldKind.Integer, "10", required = true, scale = 1048576)), "分割", true),
        UiTool("file.join", "文件合并", "按列表顺序连接文件内容", Workbench.File, listOf(files(), output.copy(required = true, initial = "Merged.bin")), "合并", true),
        UiTool("file.zip", "ZIP 压缩", "将所选文件打包为 ZIP", Workbench.File, listOf(files(), output.copy(initial = "Archive.zip")), "压缩", true),
        UiTool("file.unzip", "ZIP 解压", "提取文件并保留压缩包内的路径信息", Workbench.File, listOf(file(), ToolField("maxBytes", "最大解压体积（MiB）", FieldKind.Integer, "128", scale = 1048576)), "解压", true),
        UiTool("media.probe", "媒体信息", "查看视频、音频与字幕轨道", Workbench.Media, listOf(file()), "读取信息"),
        UiTool("media.videoToGif", "视频转 GIF", "选取片段并调整尺寸与帧率", Workbench.Format, listOf(file(), choice("sizeMode", "输出尺寸", "original" to "自适应原视频", "custom" to "自定义宽度").copy(sendToTool = false, hint = "自适应保留原视频尺寸，自定义宽度时高度按原比例调整"), integer("width", "宽度（px）", "480", true).copy(visibleWhen = mapOf("sizeMode" to setOf("custom")), hint = "支持 16 至 4096 px，高度自动计算"), integer("fps", "帧率", "15")) + clip + output, "开始转换", true, true),
        UiTool("format.mediaConvert", "媒体格式转换", "转换音频、视频与动图格式", Workbench.Format, listOf(file(), choice("format", "输出格式", "mp4" to "MP4", "mkv" to "MKV", "webm" to "WebM", "mov" to "MOV", "mp3" to "MP3", "wav" to "WAV", "flac" to "FLAC", "ogg" to "OGG", "webp" to "WebP 动图", "apng" to "APNG"), videoCompression.copy(visibleWhen = mapOf("format" to setOf("mp4", "mkv", "webm", "mov")))) + clip + output, "开始转换", true, true),
        UiTool("media.extractAudio", "提取音频", "从视频中导出音轨", Workbench.Media, listOf(file(), choice("format", "输出格式", "mp3" to "MP3", "wav" to "WAV", "flac" to "FLAC")) + clip + output, "提取音频", true, true),
        UiTool("media.edit", "视频处理", "裁剪、旋转、缩放、变速与音量调整", Workbench.Media, listOf(file()) + clip + listOf(number("speed", "播放速度", "1"), choice("rotate", "旋转", "0" to "不旋转", "90" to "顺时针 90°", "180" to "180°", "270" to "逆时针 90°"), integer("width", "输出宽度（px）").copy(hint = "留空保留处理后的原始宽度，填写后高度按比例调整，宽度需为偶数"), videoCompression, toggle("mute", "移除声音"), toggle("normalizeAudio", "音量标准化"), output), "开始处理", true, true),
        UiTool("media.extractFrame", "提取视频帧", "保存指定时刻的画面", Workbench.Media, listOf(file(), number("start", "时间（秒）", "0"), output), "提取画面", true),
        UiTool("media.extractSubtitles", "提取字幕", "自动查找视频中的字幕，选择后导出为 SRT 文件", Workbench.Media, listOf(file(), integer("index", "字幕轨道", required = true), output), "提取字幕", true),
        UiTool("image.info", "图片信息", "查看尺寸、格式和 EXIF 方向", Workbench.Image, listOf(file()), "读取信息"),
        UiTool("image.transform", "图片处理", "压缩、裁剪、缩放、旋转、打码与水印", Workbench.Image, listOf(file(), imageFormat.copy(hint = "PNG 可保留透明背景，JPEG 会把透明区域填为白色"), integer("width", "输出宽度（px）").copy(hint = "只填写宽或高时按原比例缩放，全部留空保留原尺寸"), integer("height", "输出高度（px）"), choice("rotate", "旋转", "0" to "不旋转", "90" to "顺时针 90°", "180" to "180°", "270" to "逆时针 90°"), imageQuality, ToolField("targetBytes", "目标体积（KiB）", FieldKind.Integer, scale = 1024, hint = "留空不限制体积，1 MiB = 1024 KiB"), toggle("allowResize", "达不到目标体积时允许缩小图片"), toggle("grayscale", "转为灰度"), output), "处理图片", true, true),
        UiTool("image.stitch", "拼接长图", "按顺序横向或纵向拼接图片", Workbench.Image, listOf(files(), choice("direction", "拼接方向", "vertical" to "纵向", "horizontal" to "横向"), integer("gap", "间距（px）", "0"), ToolField("background", "背景色", initial = "#FFFFFFFF"), imageFormat, output), "拼接图片", true),
        UiTool("image.sampleColor", "图片取色", "点击或拖动图片取色，查看放大像素与颜色值", Workbench.Image, listOf(file(), integer("x", "横坐标", "0", true), integer("y", "纵坐标", "0", true)), "取色"),
        UiTool("image.readCode", "识别二维码与条码", "从图片中读取编码内容", Workbench.Image, listOf(file()), "识别"),
        UiTool("network.url", "URL 分析", "查看链接的路径、参数与片段", Workbench.Network, listOf(ToolField("url", "URL", required = true)), "分析"),
        UiTool("network.dns", "DNS 查询", "查询域名对应的 IP 地址", Workbench.Network, listOf(ToolField("host", "域名", required = true)), "查询"),
        UiTool("network.tcp", "端口检测", "检查 TCP 端口是否可连接", Workbench.Network, listOf(ToolField("host", "主机或 IP", required = true), integer("port", "端口", "443", true), ToolField("timeoutMillis", "超时（秒）", FieldKind.Integer, "5", scale = 1000)), "检测"),
        UiTool("network.http", "HTTP 请求", "发送请求并查看响应内容", Workbench.Network, listOf(ToolField("url", "请求地址", required = true), choice("method", "方法", "GET" to "GET", "POST" to "POST", "PUT" to "PUT", "DELETE" to "DELETE", "HEAD" to "HEAD", "OPTIONS" to "OPTIONS"), ToolField("headers", "请求头（JSON）", FieldKind.Json, "{}"), text("body", "请求体", false), ToolField("timeoutMillis", "超时（秒）", FieldKind.Integer, "10", scale = 1000)), "发送请求"),
        UiTool("network.tls", "TLS 证书", "验证连接并查看服务端证书", Workbench.Network, listOf(ToolField("host", "域名", required = true), integer("port", "端口", "443")), "查看证书"),
        UiTool("network.interfaces", "网络接口", "查看当前设备的网络接口和地址", Workbench.Network, action = "刷新"),
        UiTool("device.system", "设备信息", "查看系统、处理器数量与存储空间", Workbench.Device, action = "刷新"),
        UiTool("device.sensors", "传感器", "查看手机传感器与量程", Workbench.Device, action = "刷新"),
        UiTool("transfer.share", "临时文件分享", "生成带有效期的本地 HTTP 下载链接", Workbench.Transfer, listOf(file(), toggle("lan", "允许同一局域网访问", true), ToolField("ttlSeconds", "有效期（分钟）", FieldKind.Integer, "10", scale = 60)), "创建分享"),
        UiTool("transfer.stopShare", "停止文件分享", "关闭指定的临时分享链接", Workbench.Transfer, listOf(ToolField("shareId", "分享标识", required = true)), "停止分享"),
        UiTool("daily.draw", "随机抽签", "从候选列表中随机抽取", Workbench.Daily, listOf(ToolField("items", "候选项，每行一项", FieldKind.Lines, required = true), integer("count", "抽取数量", "1"), toggle("replacement", "允许重复抽取")), "抽取"),
        UiTool("daily.dice", "掷骰子", "自定义骰子数量和面数", Workbench.Daily, listOf(integer("count", "骰子数量", "1"), integer("sides", "每颗骰子的面数", "6")), "掷骰"),
        UiTool("daily.countdown", "倒计时", "设置时长并查看剩余时间", Workbench.Daily)
    )

    fun resolve(descriptors: List<ToolDescriptor>): List<UiTool> {
        val known = tools.associateBy(UiTool::id)
        return descriptors.filter { it.id !in hidden }.map { descriptor ->
            known[descriptor.id] ?: UiTool(descriptor.id, descriptor.displayName ?: descriptor.id.substringAfterLast('.'), descriptor.description ?: "由插件提供", descriptor.workbench, schemaFields(descriptor), queued = descriptor.providerId != "builtin", provider = descriptor.providerId)
        }.sortedWith(compareBy<UiTool> { it.workbench.ordinal }.thenBy { it.title })
    }

    private fun schemaFields(descriptor: ToolDescriptor): List<ToolField> {
        val required = (descriptor.inputSchema["required"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
        val properties = (descriptor.inputSchema["properties"] as? JsonObject).orEmpty()
        if (properties.isEmpty()) return listOf(ToolField("__rawArguments", "输入参数（JSON）", FieldKind.Json, "{}", required = true))
        return properties.map { (key, value) ->
            val schema = value as? JsonObject ?: JsonObject(emptyMap())
            val type = schema["type"]?.jsonPrimitive?.contentOrNull
            val title = schema["title"]?.jsonPrimitive?.contentOrNull ?: key
            val options = (schema["enum"] as? JsonArray)?.map { it.jsonPrimitive.content to it.jsonPrimitive.content }.orEmpty()
            val kind = if (options.isNotEmpty() && type == "string") FieldKind.Choice else when (type) { "boolean" -> FieldKind.Toggle; "number" -> FieldKind.Number; "integer" -> FieldKind.Integer; "object", "array" -> FieldKind.Json; else -> FieldKind.Text }
            val default = schema["default"]?.let { if (it is JsonPrimitive) it.content else it.toString() } ?: options.firstOrNull()?.first.orEmpty()
            ToolField(key, title, kind, default, options, required = key in required, hint = schema["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }
}
