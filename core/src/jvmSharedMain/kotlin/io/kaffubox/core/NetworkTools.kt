// 实现受限 HTTP 请求、DNS 查询、TCP 连通性、TLS 证书与 URL 分析
package io.kaffubox.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.*
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object NetworkTools {
    private val network = setOf(Capability.Network)
    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("network.url", Workbench.Network, mapOf("url" to "string"), listOf("url")) { args, _ ->
            val uri = URI(args.string("url"))
            val query = uri.rawQuery?.split('&')?.filter(String::isNotEmpty)?.map { part ->
                val values = part.split('=', limit = 2)
                jsonObject("key" to URLDecoder.decode(values[0], "UTF-8"), "value" to URLDecoder.decode(values.getOrElse(1) { "" }, "UTF-8"))
            } ?: emptyList()
            jsonObject("scheme" to uri.scheme, "host" to uri.host, "port" to uri.port.takeIf { it >= 0 }, "path" to uri.path, "query" to query, "fragment" to uri.fragment)
        },
        defineTool("network.dns", Workbench.Network, mapOf("host" to "string"), listOf("host"), network) { args, _ ->
            val host = args.string("host"); requireArgument(host.length in 1..253, "host")
            val addresses = runInterruptible(Dispatchers.IO) { InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct() }
            jsonObject("addresses" to addresses)
        },
        defineTool("network.tcp", Workbench.Network, mapOf("host" to "string", "port" to "integer", "timeoutMillis" to "integer"), listOf("host", "port"), network) { args, _ ->
            val port = args.int("port"); val timeout = args.int("timeoutMillis", 5000)
            requireArgument(port in 1..65535 && timeout in 100..30_000, "portOrTimeout")
            withContext(Dispatchers.IO) {
                val start = System.nanoTime()
                try { Socket().use { it.connect(InetSocketAddress(args.string("host"), port), timeout) }; jsonObject("reachable" to true, "elapsedMillis" to (System.nanoTime() - start) / 1_000_000.0) }
                catch (exception: java.io.IOException) { jsonObject("reachable" to false, "error" to exception.javaClass.simpleName, "elapsedMillis" to (System.nanoTime() - start) / 1_000_000.0) }
            }
        },
        defineTool("network.http", Workbench.Network, mapOf("url" to "string", "method" to "string", "headers" to "object", "body" to "string", "timeoutMillis" to "integer"), listOf("url"), network) { args, context ->
            val uri = URI(args.string("url")); requireArgument(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null, "url")
            val method = args.string("method", "GET").uppercase(java.util.Locale.ROOT)
            requireArgument(method in setOf("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS"), "method")
            val timeout = args.int("timeoutMillis", 10_000); requireArgument(timeout in 100..60_000, "timeoutMillis")
            val maxBytes = args.int("maxBytes", 2_000_000); requireArgument(maxBytes in 1..16_000_000, "maxBytes")
            withContext(Dispatchers.IO) {
                val connection = uri.toURL().openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = method; connection.instanceFollowRedirects = false
                    connection.connectTimeout = timeout; connection.readTimeout = timeout
                    args.objectValue("headers").forEach { (key, value) ->
                        val content = value.jsonPrimitive.content
                        requireArgument(key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && !content.contains('\r') && !content.contains('\n'), "headers")
                        connection.setRequestProperty(key, content)
                    }
                    args.optionalString("body")?.let { body ->
                        requireArgument(method !in setOf("GET", "HEAD"), "body")
                        connection.doOutput = true
                        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    val code = connection.responseCode
                    val stream = if (code >= 400) connection.errorStream else connection.inputStream
                    val output = java.io.ByteArrayOutputStream()
                    var truncated = false
                    stream?.use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            context.checkpoint(); val count = input.read(buffer); if (count < 0) break
                            val room = maxBytes - output.size()
                            output.write(buffer, 0, minOf(count, room))
                            if (count > room) { truncated = true; break }
                        }
                    }
                    val bytes = output.toByteArray()
                    val body = try { strictUtf8(bytes) } catch (_: java.nio.charset.CharacterCodingException) { null }
                    jsonObject("status" to code, "headers" to connection.headerFields.filterKeys { it != null }, "body" to body, "bodyBase64" to if (body == null) Base64.getEncoder().encodeToString(bytes) else null, "bytes" to bytes.size, "truncated" to truncated)
                } finally { connection.disconnect() }
            }
        },
        defineTool("network.tls", Workbench.Network, mapOf("host" to "string", "port" to "integer"), listOf("host"), network) { args, _ ->
            val host = args.string("host"); val port = args.int("port", 443); requireArgument(port in 1..65535, "port")
            withContext(Dispatchers.IO) {
                Socket().use { transport ->
                    transport.connect(InetSocketAddress(host, port), 10_000)
                    ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(transport, host, port, true) as SSLSocket).use { socket ->
                        socket.soTimeout = 10_000
                        socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        socket.startHandshake()
                        jsonObject("protocol" to socket.session.protocol, "cipherSuite" to socket.session.cipherSuite,
                            "certificates" to socket.session.peerCertificates.filterIsInstance<X509Certificate>().map { cert ->
                                jsonObject("subject" to cert.subjectX500Principal.name, "issuer" to cert.issuerX500Principal.name, "serial" to cert.serialNumber.toString(16), "notBefore" to cert.notBefore.toInstant().toString(), "notAfter" to cert.notAfter.toInstant().toString(), "sha256" to MessageDigest.getInstance("SHA-256").digest(cert.encoded).toHex())
                            })
                    }
                }
            }
        },
        defineTool("network.interfaces", Workbench.Network, capabilities = setOf(Capability.DeviceRead)) { _, _ ->
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { item ->
                jsonObject("name" to item.name, "up" to item.isUp, "loopback" to item.isLoopback, "addresses" to item.inetAddresses.toList().mapNotNull { it.hostAddress }, "mtu" to item.mtu)
            }
            jsonObject("interfaces" to interfaces)
        }
    )
}
