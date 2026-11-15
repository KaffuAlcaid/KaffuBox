// 配置工作台新增操作、批量能力、媒体高级参数和可直接应用的内置预设
package io.kaffubox.app

import io.kaffubox.core.Workbench

object WorkspaceCatalog {
    private fun text(key: String, label: String, value: String = "", required: Boolean = false) = ToolField(key, label, FieldKind.Text, value, required = required)
    private fun number(key: String, label: String, value: String = "") = ToolField(key, label, FieldKind.Number, value)
    private fun integer(key: String, label: String, value: String = "") = ToolField(key, label, FieldKind.Integer, value)
    private fun toggle(key: String, label: String, value: Boolean = false) = ToolField(key, label, FieldKind.Toggle, value.toString())
    private fun choice(key: String, label: String, vararg values: Pair<String, String>) = ToolField(key, label, FieldKind.Choice, values.first().first, values.toList())
    private val source = ToolField("text", "输入内容", FieldKind.Paragraph, required = true)
    private val renameFields = listOf(ToolField("files", "待整理文件", FieldKind.Files, required = true), text("template", "名称模板", "{name}").copy(hint = "{name} 为原文件名，{index} 为序号，扩展名自动保留"), text("prefix", "前缀"), text("suffix", "后缀"), text("pattern", "正则查找"), text("replacement", "替换为"), toggle("numbering", "添加序号"), integer("start", "起始序号", "1"), integer("digits", "序号位数", "3"))
    val tools = listOf(
        UiTool("image.clearMetadata", "清除图片元数据", "按正确方向重新编码，移除 EXIF、GPS 等信息；PNG 保留像素与透明度", Workbench.Image, listOf(ToolField("file", "输入图片", FieldKind.File, required = true), choice("format", "输出格式", "png" to "PNG", "jpeg" to "JPEG"), text("name", "输出文件名")), "清除并保存", true, true),
        UiTool("network.diagnose", "组合网络诊断", "按步骤检查 DNS、TCP、TLS 与 HTTP，各步骤独立显示结果", Workbench.Network, listOf(text("target", "主机或 URL", required = true), integer("port", "端口（留空按 URL 判断）"), toggle("dns", "DNS 查询", true), toggle("tcp", "TCP 连接", true), toggle("tls", "TLS 证书", true), toggle("http", "HTTP 请求", true)), "开始诊断", true),
        UiTool("transfer.receive", "局域网接收", "主动开启带有效期和容量限制的浏览器文件上传", Workbench.Transfer, listOf(toggle("lan", "允许同一局域网访问", true), integer("ttlSeconds", "有效期（分钟）", "10").copy(scale = 60), integer("maxBytes", "单个文件上限（MiB）", "100").copy(scale = 1048576), integer("maxTotalBytes", "会话接收上限（MiB）", "1024").copy(scale = 1048576)), "开启接收"),
        UiTool("file.rename", "批量重命名", "预览名称后重命名原文件，或创建改名副本", Workbench.File, renameFields + choice("mode", "处理方式", "copy" to "创建改名副本", "rename" to "重命名原文件"), "执行重命名", true),
        UiTool("text.regexReplace", "正则替换", "支持捕获组、大小写与多行模式", Workbench.Text, listOf(source, text("pattern", "正则表达式", required = true), text("replacement", "替换为").copy(hint = "可用 $1、$2 引用捕获组"), toggle("ignoreCase", "忽略大小写"), toggle("multiline", "多行匹配"), toggle("literalReplacement", "按普通文字处理替换内容")), "替换"),
        UiTool("text.normalize", "换行与空白", "统一换行符、行尾空白和制表符", Workbench.Text, listOf(source, choice("newline", "换行符", "lf" to "LF", "crlf" to "CRLF", "cr" to "CR"), toggle("trimLines", "去除每行首尾空白"), toggle("trimEnd", "去除行尾空白", true), integer("tabs", "制表符替换为空格数，0 保留", "0"), toggle("finalNewline", "补上末尾换行")), "处理"),
        UiTool("text.charset", "字符编码", "文字与指定编码的 Base64 字节互转，严格检查不可表示的字符", Workbench.Text, listOf(source, choice("mode", "方向", "encode" to "文字 → Base64 字节", "decode" to "Base64 字节 → 文字"), choice("charset", "字符集", "UTF-8" to "UTF-8", "UTF-16LE" to "UTF-16LE", "UTF-16BE" to "UTF-16BE", "GB18030" to "GB18030", "GBK" to "GBK", "ISO-8859-1" to "ISO-8859-1")), "转换"),
        UiTool("document.convert", "结构化格式转换", "JSON、YAML、TOML 和 XML 节点结构互转", Workbench.Document, listOf(source, choice("from", "输入格式", "json" to "JSON", "yaml" to "YAML", "toml" to "TOML", "xml" to "XML"), choice("to", "输出格式", "yaml" to "YAML", "json" to "JSON", "toml" to "TOML", "xml" to "XML").copy(hint = "XML 使用 tag、attributes、content 保留元素和文本顺序，输出 XML 需要该结构；TOML 不支持 null，根节点必须是对象")), "转换"),
        UiTool("science.vector", "向量计算", "高精度加减、点积、三维叉积、模长与单位向量", Workbench.Science, listOf(choice("operation", "运算", "dot" to "点积", "cross" to "三维叉积", "add" to "相加", "subtract" to "相减", "norm" to "模长", "normalize" to "单位向量"), ToolField("left", "向量 A", FieldKind.Json, "[1, 2, 3]", required = true), ToolField("right", "向量 B", FieldKind.Json, "[4, 5, 6]"), integer("precision", "有效数字", "34")), "计算"),
        UiTool("science.complex", "复数计算", "高精度复数四则运算、共轭与模长", Workbench.Science, listOf(choice("operation", "运算", "add" to "相加", "subtract" to "相减", "multiply" to "相乘", "divide" to "相除", "conjugate" to "A 的共轭", "modulus" to "A 的模长"), number("realA", "A 实部", "0"), number("imaginaryA", "A 虚部", "0"), number("realB", "B 实部", "0"), number("imaginaryB", "B 虚部", "0"), integer("precision", "有效数字", "34")), "计算")
    )

    fun enrich(tool: UiTool): UiTool {
        val fields = tool.fields.toMutableList()
        if (tool.id == "file.hash") fields += text("expectedHash", "预期摘要（可选）").copy(hint = "填写后自动比对，不区分十六进制字母大小写")
        if (tool.id in setOf("format.mediaConvert", "media.extractAudio")) {
            val at = fields.indexOfFirst { it.key == "format" }
            if (at >= 0) fields[at] = fields[at].copy(choices = (fields[at].choices + listOf("m4a" to "M4A", "aac" to "AAC", "ogg" to "OGG")).distinctBy { it.first })
        }
        if (tool.id in setOf("format.mediaConvert", "media.edit", "media.extractAudio")) {
            val extra = listOf(text("videoEncoder", "视频编码器").copy(hint = "留空自动选择，可用 libx264、libx265、libvpx-vp9，需与容器兼容"), text("audioEncoder", "音频编码器").copy(hint = "留空自动选择，可用 aac、libmp3lame、libopus、libvorbis、flac、pcm_s16le、pcm_s24le"), integer("width", "宽度（偶数 px）"), integer("height", "高度（偶数 px，留空按比例）"), number("fps", "帧率"), integer("audioBitrate", "音频比特率（kbps）"), integer("sampleRate", "采样率（Hz）"), integer("channels", "声道数"), number("volumeDb", "音量增益（dB）", "0"), toggle("normalizeAudio", "响度标准化"), number("loudness", "目标响度（LUFS）", "-16"), toggle("stripMetadata", "移除媒体元数据"))
            fields += extra.filter { item -> fields.none { it.key == item.key } && !(tool.id == "media.extractAudio" && item.key in setOf("videoEncoder", "width", "height", "fps")) }
        }
        if (tool.id in setOf("format.mediaConvert", "media.extractAudio")) {
            val videoFormats = setOf("mp4", "mov", "mkv", "webm", "webp", "apng")
            val audioFormats = setOf("mp4", "mov", "mkv", "webm", "mp3", "m4a", "aac", "wav", "flac", "ogg")
            for (index in fields.indices) {
                val field = fields[index]
                val formats = when (field.key) {
                    "videoEncoder" -> setOf("mp4", "mov", "mkv", "webm")
                    "width", "height", "fps" -> videoFormats
                    "audioBitrate" -> audioFormats - setOf("wav", "flac")
                    "audioEncoder", "sampleRate", "channels", "volumeDb", "normalizeAudio", "loudness" -> audioFormats
                    else -> null
                }
                if (formats != null) fields[index] = field.copy(visibleWhen = mapOf("format" to formats))
            }
        }
        return tool.copy(fields = fields, batch = tool.batch || (tool.fields.count { it.kind == FieldKind.File } == 1 && tool.id !in setOf("transfer.share", "transfer.scanCode", "image.sampleColor", "media.extractSubtitles")))
    }

    fun presets(toolId: String): List<ToolPreset> = when (toolId) {
        "image.transform" -> listOf(ToolPreset("网页图片 · JPEG 1600 px", toolId, mapOf("format" to "jpeg", "width" to "1600", "quality" to "85")), ToolPreset("透明图片 · PNG", toolId, mapOf("format" to "png", "width" to "", "height" to "")))
        "format.mediaConvert" -> listOf(ToolPreset("通用视频 · H.264 1080p", toolId, mapOf("format" to "mp4", "videoEncoder" to "libx264", "width" to "1920", "quality" to "23", "audioEncoder" to "aac", "audioBitrate" to "192")), ToolPreset("音频 · MP3 192 kbps", toolId, mapOf("format" to "mp3", "audioBitrate" to "192", "audioEncoder" to "libmp3lame", "videoEncoder" to "", "width" to "")), ToolPreset("无损音频 · FLAC", toolId, mapOf("format" to "flac", "audioBitrate" to "", "videoEncoder" to "", "audioEncoder" to "flac", "width" to "")))
        "media.videoToGif" -> listOf(ToolPreset("紧凑 GIF · 480 px 12 fps", toolId, mapOf("sizeMode" to "custom", "width" to "480", "fps" to "12")))
        else -> emptyList()
    }
}
