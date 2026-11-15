// 将核心状态、权限与错误代码映射为简洁的中文操作文案
package io.kaffubox.app

import io.kaffubox.core.*
import kotlinx.serialization.json.*

object UiText {
    fun toolTitle(id: String): String = if (id == "transfer.download") "文件下载" else id
    fun state(state: TaskState): String = when (state) {
        TaskState.Queued -> "等待中"
        TaskState.Running -> "处理中"
        TaskState.Cancelling -> "正在取消"
        TaskState.Succeeded -> "已完成"
        TaskState.Failed -> "失败"
        TaskState.Cancelled -> "已取消"
    }
    fun permission(capability: Capability): String = when (capability) {
        Capability.FileRead -> "读取你选择的文件"
        Capability.FileWrite -> "创建处理结果"
        Capability.FileManage -> "重命名已选择的原文件"
        Capability.Network -> "访问网络"
        Capability.DeviceRead -> "读取设备信息"
        Capability.NativeProcess -> "调用本地处理引擎"
    }
    fun error(code: String, details: JsonObject = JsonObject(emptyMap())): String = when (code) {
        "regexTimeout" -> "正则匹配耗时过长，请缩短输入或调整表达式"
        "renameUnsupported" -> "当前平台不支持重命名原文件，请选择创建改名副本"
        "outputAlreadyExists" -> "输出文件已存在，可更换名称或调整同名文件策略"
        "outputWouldReplaceInput" -> "输出位置与输入文件相同，请更换目录或名称"
        "xmlStructureRequired" -> "输出 XML 需要包含 tag、attributes、content 的节点结构，可先从 XML 转换获得"
        "tomlRootObjectRequired" -> "TOML 的根节点必须是对象"
        "transferClosed" -> "传输会话已关闭或过期"
        "downloadUrlInvalid" -> "请输入完整的 HTTP 或 HTTPS 地址，地址中不能包含用户名和密码"
        "downloadHttpError" -> "服务器返回 HTTP " + (details["status"]?.jsonPrimitive?.contentOrNull ?: "错误")
        "downloadRedirect" -> "下载地址发生跳转，请重新读取信息并确认新地址"
        "downloadTooLarge" -> "文件超过本次下载大小上限，请调整上限后重试"
        "downloadIncomplete" -> "连接结束时文件不完整，未保存未完成的内容"
        "temporaryDirectoryUnavailable" -> "无法创建临时分享目录，请检查应用的存储空间"
        "diskSpaceInsufficient" -> "临时目录剩余空间不足，请清理后再试"
        "shareQuotaExceeded" -> "正在进行的分享占用了过多临时空间，请先停止部分分享"
        "shareTooLarge" -> "分享内容超过 4 GiB，请分批分享"
        "EOFException" -> "文件传输完成前连接已中断，未完成的文件已清理"
        "SocketException" -> "网络连接已关闭或不可用"
        "UnknownHostException" -> "无法解析主机地址"
        "SocketTimeoutException" -> "网络连接或读取超时"
        "ConnectException" -> "无法建立连接，请检查主机和端口"
        "SSLHandshakeException", "SSLException" -> "TLS 握手或证书校验失败"
        "argumentRequired", "stringRequired", "integerRequired", "numberRequired", "finiteNumberRequired" -> "请补全参数，并检查数值格式"
        "argumentInvalid", "integerOutOfRange" -> "参数超出允许范围，请检查后重试"
        "fileReferenceUnknown" -> "文件引用已失效，请重新选择文件"
        "fileRequired", "fileOpenFailed" -> "无法读取此文件，请检查文件是否仍然存在"
        "directoryNotSupported" -> "请选择文件，当前暂不支持导入整个文件夹"
        "inputTooLarge", "protocolMessageTooLarge" -> "内容过大，请减少输入后重试"
        "fileNameInvalid", "fileNameReserved" -> "文件名无效，请避开特殊字符与系统保留名称"
        "nameExtension" -> "文件扩展名与输出格式不一致"
        "outputDirectoryNotWritable" -> "无法保存到此位置，请更换输出目录"
        "permissionDenied" -> "缺少所需授权，请检查插件权限"
        "toolNotFound" -> "工具已停用或移除"
        "taskStillRunning" -> "请先等待任务结束或取消任务"
        "mediaEngineFailed" -> "媒体处理未完成，请查看详细信息"
        "mediaNoVideo" -> "这个文件没有可读取的视频画面"
        "mediaNoAudio" -> "这个文件没有音轨"
        "mediaNoSubtitles" -> "没有找到可提取的文字字幕"
        "mediaDurationUnavailable" -> "无法读取视频时长，请更换文件后重试"
        "audioCodecUnsupported" -> "此音轨无法直接导出为 M4A，可使用桌面版转码"
        "mediaFormatUnsupported", "audioFormatUnsupported", "imageFormatUnsupported", "metadataFormatUnsupported" -> "当前不支持这个文件格式"
        "imageDecodeFailed", "imageEncodeFailed" -> "图片处理失败，请检查文件是否完整"
        "targetSizeUnreachable" -> "当前尺寸无法达到目标体积，可允许缩小尺寸后重试"
        "codeNotFound" -> "没有识别到二维码或条码"
        "archiveSizeLimit" -> "解压内容超过设定上限"
        "csvInvalid", "csvUnterminatedQuote" -> "表格格式有误，请检查分隔符和引号"
        "tomlInvalid" -> "TOML 格式有误"
        "stringKeyRequired" -> "对象的键名必须是文字"
        "mathSyntax", "mathOperandRequired" -> "请补全表达式并检查运算符"
        "mathClosingParenthesis" -> "缺少右括号"
        "mathDivideByZero" -> "不能除以 0"
        "mathDomain" -> "此运算超出实数范围"
        "mathIntegerRequired" -> "此运算需要整数"
        "mathArgumentRange", "mathArgumentCount" -> "函数参数的数量或范围不正确"
        "mathVariableUnknown" -> "没有识别变量 " + (details["name"]?.jsonPrimitive?.contentOrNull ?: "")
        "mathFunctionUnknown" -> "没有识别这个函数"
        "mathMagnitudeLimit", "mathAngleLimit" -> "数值超出可计算范围"
        "mathOperationLimit", "mathDepthLimit" -> "表达式过于复杂，请适当拆分"
        "matrixSingular" -> "矩阵不可逆，无法得到唯一解"
        "matrixRequired", "matrixRowInvalid", "matrixCellInvalid" -> "请检查矩阵中的数值和行列数"
        "unitUnknown" -> "没有识别这个单位"
        "temperatureBelowAbsoluteZero" -> "温度不能低于绝对零度"
        "pluginSignatureRequired" -> "此插件没有签名，默认不允许安装"
        "pluginSignerUntrusted" -> "尚未信任此开发者，请先导入对应公钥"
        "pluginSignatureInvalid", "pluginContentMismatch" -> "插件校验未通过，请重新获取安装包"
        "pluginManifestInvalid", "pluginManifestMissing", "pluginEntryMissing" -> "插件包不完整或清单格式有误"
        "pluginVersionConflict", "pluginVersionExists" -> "此版本已存在，请使用新的版本号"
        "pluginUpgradeRequired" -> "请选择更新版本，回退请使用版本回退功能"
        "pluginApiIncompatible" -> "插件与当前应用版本不兼容"
        "pluginPlatformUnsupported" -> "此插件不支持当前平台"
        "pluginRuntimeUnavailable" -> "此插件所需的运行时尚未接入"
        "pluginNewPermissions" -> "新版本申请了额外权限，请先停用插件后重新安装并授权"
        "pluginDependencyMissing" -> "请先安装并启用所需的依赖插件"
        "pluginDependencyCycle" -> "插件存在循环依赖"
        "pluginHasDependents" -> "其他已启用插件依赖此插件，请先停用相关插件"
        "pluginVersionMissing" -> "没有找到这个历史版本"
        "pluginNotFound" -> "插件已被移除"
        "pluginIdentityMismatch" -> "请选择同一插件的新版本"
        "publicKeyInvalid", "signatureKeyTooSmall" -> "请选择有效的 RSA 公钥"
        "scriptBudgetExceeded" -> "插件执行超时或运算量过大"
        "scriptWorkerStopped", "scriptServiceStopped" -> "插件运行进程已结束"
        "scriptFailed", "scriptHostFailed" -> "插件执行失败"
        "toolRecursion" -> "工具调用发生循环"
        else -> "处理未完成，请查看详细信息"
    }
    fun failure(failure: Throwable): String = when (failure) {
        is ToolFailure -> error(failure.code, failure.details)
        is java.nio.file.AccessDeniedException, is SecurityException -> "没有访问权限，请选择其他文件或目录"
        is java.nio.file.NoSuchFileException -> "文件已移动或删除"
        is java.net.UnknownHostException -> "无法解析域名，请检查地址和网络"
        is java.net.SocketTimeoutException -> "连接超时，请稍后重试"
        is javax.net.ssl.SSLException -> "TLS 验证失败，请检查服务端证书"
        is kotlinx.serialization.SerializationException -> "输入格式不正确，请检查内容"
        is java.security.spec.InvalidKeySpecException -> "不是有效的 RSA 公钥"
        is IllegalArgumentException -> "输入内容或参数格式不正确"
        else -> "操作未完成，请查看详细信息"
    }
}

fun readableBytes(value: Long?): String {
    if (value == null) return "大小未知"
    if (value < 1024) return "$value B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var bytes = value.toDouble() / 1024
    var index = 0
    while (bytes >= 1024 && index < units.lastIndex) { bytes /= 1024; index++ }
    return "%.2f %s".format(java.util.Locale.ROOT, bytes, units[index])
}
