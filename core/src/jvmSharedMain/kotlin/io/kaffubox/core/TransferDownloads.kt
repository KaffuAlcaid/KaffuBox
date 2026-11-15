// 复用图片识码能力，按用户确认的 HTTP 地址读取文件信息并通过任务系统下载到受控输出目录
package io.kaffubox.core

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import kotlinx.serialization.json.*

fun transferUri(text: String): URI {
    val uri = try { URI(text.trim()) } catch (_: Exception) { throw ToolFailure("downloadUrlInvalid") }
    requireArgument(uri.toString().length <= 8192, "url")
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.port !in -1..65535 || uri.port == 0) throw ToolFailure("downloadUrlInvalid")
    return URI(uri.toASCIIString().substringBefore('#'))
}

object TransferDownloads {
    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("transfer.scanCode", Workbench.Transfer, mapOf("file" to "string"), listOf("file"), setOf(Capability.FileRead)) { args, context ->
            context.invoke("image.readCode", args)
        },
        defineTool("transfer.inspectDownload", Workbench.Transfer, mapOf("url" to "string"), listOf("url"), setOf(Capability.Network)) { args, context ->
            context.checkpoint()
            val url = transferUri(args.string("url"))
            val connection = connect(url, "HEAD")
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val next = connection.getHeaderField("Location") ?: throw ToolFailure("downloadHttpError", jsonObject("status" to status))
                    jsonObject("url" to url.toASCIIString(), "redirectUrl" to transferUri(url.resolve(next).toString()).toASCIIString(), "status" to status)
                } else {
                    if (status !in 200..299 && status !in setOf(405, 501)) throw ToolFailure("downloadHttpError", jsonObject("status" to status))
                    jsonObject("url" to url.toASCIIString(), "name" to fileName(connection, url), "size" to connection.contentLengthLong.takeIf { status in 200..299 && it >= 0 }, "mimeType" to connection.contentType?.substringBefore(';'), "etag" to connection.getHeaderField("ETag"), "lastModified" to connection.lastModified.takeIf { it > 0 }, "metadataAvailable" to (status in 200..299))
                }
            } finally { connection.disconnect() }
        },
        defineTool("transfer.download", Workbench.Transfer, mapOf("url" to "string", "name" to "string", "maxBytes" to "integer", "etag" to "string"), listOf("url"), setOf(Capability.Network, Capability.FileWrite)) { args, context ->
            val url = transferUri(args.string("url"))
            val maximum = args.long("maxBytes", 1024L * 1024 * 1024); requireArgument(maximum in 1..10L * 1024 * 1024 * 1024, "maxBytes")
            val connection = connect(url, "GET")
            args.optionalString("etag")?.let { connection.setRequestProperty("If-Match", it) }
            try {
                context.checkpoint(0, null, "downloading")
                val status = connection.responseCode
                if (status in 300..399) throw ToolFailure("downloadRedirect")
                if (status != 200) throw ToolFailure("downloadHttpError", jsonObject("status" to status))
                val size = connection.contentLengthLong.takeIf { it >= 0 }
                if (size != null && size > maximum) throw ToolFailure("downloadTooLarge")
                val name = args.optionalString("name")?.takeIf { it.isNotBlank() }?.let(::safeFileName) ?: fileName(connection, url)
                val root = context.files.outputDirectory ?: throw ToolFailure("outputDirectoryNotWritable")
                OutputSession(context.files, root, "unique", emptyList()).use { output ->
                    val ref = output.createOutput(name, connection.contentType?.substringBefore(';'))
                    var total = 0L
                    connection.inputStream.use { input -> output.resolve(ref.id).openOutput!!.invoke().use { sink ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            context.checkpoint(total, size, "downloading")
                            val count = input.read(buffer); if (count < 0) break
                            total += count
                            if (total > maximum) throw ToolFailure("downloadTooLarge")
                            sink.write(buffer, 0, count)
                        }
                    } }
                    if (size != null && size != total) throw ToolFailure("downloadIncomplete")
                    context.checkpoint(total, size, "downloading")
                    output.commit()
                    jsonObject("file" to CoreJson.encodeToJsonElement(context.files.resolve(ref.id).ref), "bytes" to total)
                }
            } finally { connection.disconnect() }
        }
    )

    private fun connect(uri: URI, method: String) = (uri.toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = method; instanceFollowRedirects = false; connectTimeout = 10000; readTimeout = 10000
        setRequestProperty("Accept-Encoding", "identity"); setRequestProperty("User-Agent", "KaffuBox")
    }

    private fun fileName(connection: HttpURLConnection, uri: URI): String {
        val disposition = connection.getHeaderField("Content-Disposition").orEmpty()
        val encoded = Regex("(?i)(?:^|;)\\s*filename\\*=UTF-8'[^']*'([^;]+)").find(disposition)?.groupValues?.get(1)?.trim()?.trim('"')
        val quoted = Regex("(?i)(?:^|;)\\s*filename=(?:\"([^\"]+)\"|([^;]+))").find(disposition)
        val name = encoded?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() }
            ?: quoted?.let { it.groupValues[1].ifEmpty { it.groupValues[2].trim() } }
            ?: uri.path.substringAfterLast('/').takeIf { it.isNotBlank() && it.length <= 150 } ?: "DownloadedFile"
        val safe = name.substringAfterLast('/').substringAfterLast('\\').take(150)
        return runCatching { safeFileName(safe) }.getOrDefault("DownloadedFile")
    }
}
