// 提供有期限和随机令牌的本地 HTTP 文件分享，归属插件的分享随热卸载关闭
package io.kaffubox.core

import java.io.BufferedInputStream
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class TransferTools : AutoCloseable {
    private val sessions = ConcurrentHashMap<String, ShareSession>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "KaffuShareExpiry").apply { isDaemon = true } }

    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("transfer.share", Workbench.Transfer, mapOf("file" to "string", "lan" to "boolean", "ttlSeconds" to "integer"), listOf("file"), setOf(Capability.FileRead, Capability.Network)) { args, context ->
            val ttl = args.int("ttlSeconds", 600); requireArgument(ttl in 10..86400, "ttlSeconds")
            val handle = context.files.resolve(args.string("file"))
            val token = ByteArray(24).also(SecureRandom()::nextBytes).toHex()
            val bind = if (args.flag("lan")) "0.0.0.0" else "127.0.0.1"
            val session = ShareSession(token, handle, bind) { sessions.remove(token) }
            sessions[token] = session
            session.registration = context.own(session)
            session.expiry = scheduler.schedule({ session.close() }, ttl.toLong(), TimeUnit.SECONDS)
            session.start()
            val addresses = if (bind == "127.0.0.1") listOf(bind) else NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.distinct()
            jsonObject("shareId" to token, "urls" to addresses.map { "http://$it:${session.port}/$token" }, "expiresAt" to System.currentTimeMillis() + ttl * 1000L, "encrypted" to false)
        },
        defineTool("transfer.stopShare", Workbench.Transfer, mapOf("shareId" to "string"), listOf("shareId"), setOf(Capability.Network)) { args, _ ->
            val session = sessions[args.string("shareId")]
            session?.close(); jsonObject("stopped" to (session != null))
        }
    )

    override fun close() { sessions.values.toList().forEach(ShareSession::close); scheduler.shutdownNow() }
}

private class ShareSession(
    private val token: String,
    private val file: FileHandle,
    bindAddress: String,
    private val onClose: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bindAddress, 0), 16) }
    val port: Int get() = server.localPort
    @Volatile var expiry: ScheduledFuture<*>? = null
    @Volatile var registration: AutoCloseable? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8), { runnable -> Thread(runnable, "KaffuShareClient").apply { isDaemon = true } })

    fun start() {
        Thread({
            try {
                while (!closed.get()) {
                    val socket = server.accept(); clients += socket
                    try { workers.execute { try { serve(socket) } catch (_: Exception) { } finally { clients.remove(socket); try { socket.close() } catch (_: Exception) { } } } }
                    catch (_: RejectedExecutionException) { clients.remove(socket); socket.close() }
                }
            } catch (_: java.io.IOException) { } finally { close() }
        }, "KaffuShareAccept").apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val input = BufferedInputStream(socket.getInputStream())
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val character = input.read(); if (character < 0) return
            header.append(character.toChar()); if (header.length > 8192) return
        }
        val request = header.toString().substringBefore("\r\n").split(' ')
        val output = socket.getOutputStream()
        if (request.size != 3 || request[0] !in setOf("GET", "HEAD") || request[1] != "/$token") {
            output.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)); return
        }
        val encodedName = URLEncoder.encode(file.ref.name, "UTF-8").replace("+", "%20")
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n")
            append("Content-Disposition: attachment; filename*=UTF-8''$encodedName\r\n")
            file.ref.size?.let { append("Content-Length: $it\r\n") }
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        if (request[0] == "GET") file.openInput().use { source ->
            val buffer = ByteArray(64 * 1024)
            while (!closed.get()) { val count = source.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
        }
        output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        expiry?.cancel(false)
        try { server.close() } catch (_: Exception) { }
        clients.forEach { try { it.close() } catch (_: Exception) { } }
        workers.shutdownNow(); registration?.close(); onClose()
    }
}
