// 提供有期限和随机令牌的本地 HTTP 分享：单个文件按原格式直发，多个文件先流式打包成一份临时 ZIP 再开放下载
package io.kaffubox.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*

private const val MaxSessions = 32
private const val MaxEntries = 10_000
private const val MaxShareBytes = 4L * 1024 * 1024 * 1024
private const val MaxArchivedBytes = 8L * 1024 * 1024 * 1024
private const val MinimumFreeBytes = 64L * 1024 * 1024
private const val UnknownEntryBytes = 32L * 1024 * 1024
private const val ShareBuffer = 128 * 1024
private const val ArchiveMember = "payload.zip"

class TransferTools(private val tempRoot: Path? = null) : AutoCloseable {
    private val sessions = ConcurrentHashMap<String, ShareSession>()
    val activeCount: Int get() = sessions.values.count { it.active }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "KaffuShareExpiry").apply { isDaemon = true } }
    private val archivedBytes = AtomicLong()

    init {
        // 上次运行异常退出时可能留下未清理的临时 ZIP，启动时统一删除
        tempRoot?.let { root ->
            runCatching {
                Files.createDirectories(root)
                val stale = Files.list(root).use { stream -> stream.iterator().asSequence().toList() }
                stale.forEach { entry -> runCatching { deletePrivateTree(root, entry) } }
            }
        }
    }

    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("transfer.share", Workbench.Transfer, mapOf("file" to "string", "files" to "array", "lan" to "boolean", "ttlSeconds" to "integer"), capabilities = setOf(Capability.FileRead, Capability.Network)) { args, context ->
            requireArgument(activeCount < MaxSessions, "activeSessions")
            val ttl = args.int("ttlSeconds", 600); requireArgument(ttl in 10..86400, "ttlSeconds")
            val ids = (if (args.containsKey("files")) args.strings("files") else listOf(args.string("file"))).distinct()
            requireArgument(ids.size in 1..MaxEntries, "files")
            val handles = ids.map { context.files.resolve(it) }
            val session = ShareSession(randomToken(), if (args.flag("lan")) "0.0.0.0" else "127.0.0.1", context.files, tempRoot, archivedBytes)
            try {
                if (handles.size == 1) session.prepareSingle(handles.first()) else session.prepareArchive(handles, context)
                session.start()
                session.schedule(ttl, scheduler)
                sessions[session.token] = session
                session.registration = context.own(session)
            } catch (failure: Throwable) { sessions.remove(session.token); session.close(); throw failure }
            session.snapshot()
        },
        defineTool("transfer.receive", Workbench.Transfer, mapOf("lan" to "boolean", "ttlSeconds" to "integer", "maxBytes" to "integer", "maxTotalBytes" to "integer"), capabilities = setOf(Capability.FileWrite, Capability.Network)) { args, context ->
            requireArgument(activeCount < MaxSessions, "activeSessions")
            val ttl = args.int("ttlSeconds", 600); val maximum = args.long("maxBytes", 100L * 1024 * 1024); val total = args.long("maxTotalBytes", 1024L * 1024 * 1024)
            requireArgument(ttl in 10..86400 && maximum in 1..10L * 1024 * 1024 * 1024 && total in maximum..100L * 1024 * 1024 * 1024, "transferLimits")
            val session = ShareSession(randomToken(), if (args.flag("lan")) "0.0.0.0" else "127.0.0.1", context.files, tempRoot, archivedBytes, receiving = true, maxBytes = maximum, maxTotalBytes = total)
            try {
                session.start()
                session.schedule(ttl, scheduler)
                sessions[session.token] = session
                session.registration = context.own(session)
            } catch (failure: Throwable) { sessions.remove(session.token); session.close(); throw failure }
            session.snapshot()
        },
        defineTool("transfer.sessions", Workbench.Transfer, capabilities = setOf(Capability.Network)) { _, _ ->
            sessions.entries.filter { !it.value.active && System.currentTimeMillis() - it.value.expiresAt > 86400000 }.forEach { sessions.remove(it.key, it.value) }
            jsonObject("sessions" to sessions.values.sortedByDescending { it.expiresAt }.map { it.snapshot() })
        },
        defineTool("transfer.stopShare", Workbench.Transfer, mapOf("shareId" to "string"), listOf("shareId"), setOf(Capability.Network)) { args, _ ->
            val session = sessions[args.string("shareId")]
            val active = session?.active == true; session?.close(); jsonObject("stopped" to active)
        },
        defineTool("transfer.extendShare", Workbench.Transfer, mapOf("shareId" to "string", "seconds" to "integer"), listOf("shareId"), setOf(Capability.Network)) { args, _ ->
            val seconds = args.int("seconds", 600); requireArgument(seconds in 10..86400, "seconds")
            val session = sessions[args.string("shareId")] ?: throw ToolFailure("transferClosed")
            session.extend(seconds, scheduler); session.snapshot()
        },
        defineTool("transfer.stopAll", Workbench.Transfer, capabilities = setOf(Capability.Network)) { _, _ ->
            val active = sessions.values.filter { it.active }; active.forEach { it.close() }; jsonObject("stopped" to active.size)
        },
        defineTool("transfer.clearClosed", Workbench.Transfer, capabilities = setOf(Capability.Network)) { _, _ ->
            val entries = sessions.entries.filter { it.value.finished }
            entries.forEach { sessions.remove(it.key, it.value) }; jsonObject("removed" to entries.size)
        }
    )

    override fun close() { sessions.values.toList().forEach(ShareSession::close); scheduler.shutdownNow() }
}

private fun randomToken(): String = ByteArray(24).also(SecureRandom()::nextBytes).toHex()

private class Payload(val name: String, val size: Long?, val type: String, val open: () -> InputStream)

private data class ArchiveInfo(val name: String, val size: Long, val entries: Int)

private class ShareSession(
    val token: String,
    private val bindAddress: String,
    private val files: FileBroker?,
    private val tempRoot: Path?,
    private val archivedBytes: AtomicLong,
    private val receiving: Boolean = false,
    private val maxBytes: Long = 0,
    private val maxTotalBytes: Long = 0
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val cleaned = AtomicBoolean()
    private val expiryLock = Any()
    private val createdAt = System.currentTimeMillis()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16), { runnable -> Thread(runnable, "KaffuShareClient").apply { isDaemon = true } })
    private val addresses = if (bindAddress == "127.0.0.1") listOf(bindAddress) else NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { adapter -> adapter.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress || it.isLinkLocalAddress }.map { adapter to it } }
        .sortedWith(compareBy<Pair<NetworkInterface, Inet4Address>> { it.second.isLinkLocalAddress }.thenBy { (adapter, _) ->
            val name = (adapter.name + " " + adapter.displayName.orEmpty()).lowercase(Locale.ROOT)
            adapter.isVirtual || adapter.isPointToPoint || listOf("virtual", "vmware", "vmnet", "vbox", "hyper-v", "vethernet", "tailscale", "wireguard").any(name::contains)
        })
        .mapNotNull { it.second.hostAddress }.distinct()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var boundPort = 0
    @Volatile var expiresAt: Long = 0L
        private set
    @Volatile var registration: AutoCloseable? = null
    @Volatile private var expiry: ScheduledFuture<*>? = null
    @Volatile private var lastError: String? = null
    @Volatile private var displayName: String? = null

    private var file: FileHandle? = null
    @Volatile private var archive: ArchiveInfo? = null
    @Volatile private var archiveDirectory: Path? = null
    private var quotaBytes = 0L
    private val inputs = CopyOnWriteArrayList<JsonElement>()

    private val outputRoot = files?.outputDirectory
    private val transferred = AtomicLong()
    private val reservedBytes = AtomicLong()
    private val completed = AtomicLong()
    private val downloads = AtomicLong()
    private val interrupted = AtomicLong()
    private val downloadHistory = mutableListOf<DownloadAttempt>()
    private val received = CopyOnWriteArrayList<FileRef>()
    val active: Boolean get() = !closed.get()
    val finished: Boolean get() = cleaned.get()

    private class DownloadAttempt(val id: Long, val name: String, val peer: String, val total: Long?) {
        val startedAt = System.currentTimeMillis()
        val bytes = AtomicLong()
        @Volatile var endedAt: Long? = null
        @Volatile var state = "running"
        @Volatile var error: String? = null
        fun snapshot() = jsonObject("id" to id, "name" to name, "peer" to peer, "totalBytes" to total, "bytes" to bytes.get(), "startedAt" to startedAt, "endedAt" to endedAt, "state" to state, "error" to error)
    }

    fun prepareSingle(handle: FileHandle) {
        file = handle
        displayName = handle.ref.name
        inputs += jsonObject("name" to handle.ref.name, "size" to handle.ref.size)
    }

    suspend fun prepareArchive(handles: List<FileHandle>, context: ToolContext) {
        val root = tempRoot ?: throw ToolFailure("temporaryDirectoryUnavailable")
        val directory = root.resolve(token)
        Files.createDirectories(directory)
        archiveDirectory = directory
        val known = handles.sumOf { it.ref.size ?: 0L }
        requireArgument(known <= MaxShareBytes, "files")
        val estimate = known + handles.count { it.ref.size == null } * UnknownEntryBytes + MinimumFreeBytes
        reserveQuota(estimate)
        runCatching { Files.getFileStore(directory) }.getOrNull()?.let { store ->
            if (store.usableSpace < known + MinimumFreeBytes) throw ToolFailure("diskSpaceInsufficient")
        }
        val name = archiveName(handles)
        val target = directory.resolve(ArchiveMember)
        val temporary = directory.resolve("$ArchiveMember.part")
        val used = mutableSetOf<String>()
        var total = 0L
        try {
            Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { raw ->
                ZipOutputStream(BufferedOutputStream(raw, ShareBuffer)).use { zip ->
                    zip.setLevel(Deflater.BEST_SPEED)
                    handles.forEachIndexed { index, handle ->
                        context.checkpoint(index.toLong(), handles.size.toLong(), "packing")
                        zip.putNextEntry(ZipEntry(uniqueEntry(zipEntryName(handle.ref.name), used)))
                        handle.openInput().use { source ->
                            val buffer = ByteArray(ShareBuffer)
                            while (true) {
                                context.checkpoint()
                                val count = source.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MaxShareBytes) throw ToolFailure("shareTooLarge")
                                zip.write(buffer, 0, count)
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(temporary) }
            throw failure
        }
        val size = Files.size(target)
        archive = ArchiveInfo(name, size, handles.size)
        adjustQuota(estimate, size)
        handles.forEach { inputs += jsonObject("name" to it.ref.name, "size" to it.ref.size) }
        displayName = name
    }

    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, 0), 16)
        server = socket
        boundPort = socket.localPort
        Thread({
            try {
                while (!closed.get()) {
                    val client = socket.accept(); clients += client
                    try { workers.execute { try { serve(client) } catch (failure: Exception) { lastError = if (failure is ToolFailure) failure.code else failure.javaClass.simpleName } finally { clients.remove(client); try { client.close() } catch (_: Exception) { } } } }
                    catch (_: RejectedExecutionException) { clients.remove(client); busy(client) }
                }
            } catch (_: java.io.IOException) { } finally { close() }
        }, "KaffuShareAccept").apply { isDaemon = true; start() }
    }

    fun schedule(ttlSeconds: Int, scheduler: ScheduledExecutorService) = synchronized(expiryLock) {
        armExpiry(System.currentTimeMillis() + ttlSeconds * 1000L, scheduler)
    }

    fun extend(seconds: Int, scheduler: ScheduledExecutorService) = synchronized(expiryLock) {
        if (!active || expiresAt <= System.currentTimeMillis()) throw ToolFailure("transferClosed")
        armExpiry(minOf(expiresAt + seconds * 1000L, System.currentTimeMillis() + 86400000L), scheduler)
    }

    private fun armExpiry(deadline: Long, scheduler: ScheduledExecutorService) {
        if (!active) throw ToolFailure("transferClosed")
        expiry?.cancel(false)
        expiresAt = deadline
        expiry = scheduler.schedule({ synchronized(expiryLock) { if (expiresAt == deadline) close() } }, (deadline - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    fun snapshot(): JsonObject {
        val urls = if (boundPort > 0) addresses.map { "http://$it:$boundPort/$token" } else emptyList()
        val fields = mutableListOf<Pair<String, Any?>>(
            "shareId" to token,
            "mode" to if (receiving) "receive" else "send",
            "name" to displayName,
            "active" to active,
            "state" to if (active) "active" else if (finished) "closed" else "closing",
            "createdAt" to createdAt,
            "expiresAt" to expiresAt,
            "activeConnections" to clients.size,
            "urls" to urls,
            "primaryUrl" to urls.firstOrNull(),
            "transferredBytes" to transferred.get(),
            "completedFiles" to completed.get(),
            "downloads" to downloads.get(),
            "interruptedDownloads" to interrupted.get(),
            "downloadHistory" to synchronized(downloadHistory) { downloadHistory.map { it.snapshot() } },
            "files" to if (receiving) received.map { CoreJson.encodeToJsonElement(it) } else inputs.toList(),
            "error" to lastError
        )
        if (receiving) { fields += "maxBytes" to maxBytes; fields += "maxTotalBytes" to maxTotalBytes }
        archive?.let { fields += "archive" to jsonObject("name" to it.name, "size" to it.size, "entries" to it.entries) }
        return jsonObject(*fields.toTypedArray())
    }

    private fun busy(client: Socket) {
        runCatching {
            client.soTimeout = 2000
            client.getOutputStream().run {
                write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nRetry-After: 5\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
        }
        runCatching { client.close() }
    }

    private fun reserveQuota(bytes: Long) {
        while (true) {
            val current = archivedBytes.get()
            if (current + bytes > MaxArchivedBytes) throw ToolFailure("shareQuotaExceeded")
            if (archivedBytes.compareAndSet(current, current + bytes)) { quotaBytes = bytes; return }
        }
    }

    private fun adjustQuota(reserved: Long, actual: Long) {
        archivedBytes.addAndGet(actual - reserved); quotaBytes = actual
    }

    private fun payload(): Payload {
        val info = archive
        if (info != null) {
            val path = archiveDirectory!!.resolve(ArchiveMember)
            return Payload(info.name, Files.size(path), "application/zip") { Files.newInputStream(path) }
        }
        val handle = files?.resolve(file!!.ref.id) ?: file!!
        return Payload(handle.ref.name, handle.ref.size, "application/octet-stream") { handle.openInput() }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        if (!socket.inetAddress.isLoopbackAddress && !socket.inetAddress.isSiteLocalAddress && !socket.inetAddress.isLinkLocalAddress) return
        val input = BufferedInputStream(socket.getInputStream())
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val character = input.read(); if (character < 0) return
            header.append(character.toChar()); if (header.length > 8192) return
        }
        val request = header.toString().substringBefore("\r\n").split(' ')
        val output = BufferedOutputStream(socket.getOutputStream(), ShareBuffer)
        if (receiving) { receive(request, header.toString(), input, output); return }
        if (request.size != 3 || request[0] !in setOf("GET", "HEAD") || request[1] != "/$token") {
            output.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)); output.flush(); return
        }
        val payload = payload()
        val encodedName = URLEncoder.encode(payload.name, "UTF-8").replace("+", "%20")
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: ").append(payload.type).append("\r\n")
            append("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n")
            append("Content-Disposition: attachment; filename*=UTF-8''").append(encodedName).append("\r\n")
            payload.size?.let { append("Content-Length: ").append(it).append("\r\n") }
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        if (request[0] == "GET") {
            val attempt = DownloadAttempt(downloads.incrementAndGet(), payload.name, socket.inetAddress.hostAddress.orEmpty(), payload.size)
            synchronized(downloadHistory) {
                while (downloadHistory.size >= 100) { val at = downloadHistory.indexOfFirst { it.state != "running" }; if (at < 0) break; downloadHistory.removeAt(at) }
                downloadHistory += attempt
            }
            try {
                payload.open().use { source ->
                    val buffer = ByteArray(ShareBuffer)
                    while (true) {
                        if (closed.get()) throw ToolFailure("transferClosed")
                        val count = source.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count); transferred.addAndGet(count.toLong()); attempt.bytes.addAndGet(count.toLong())
                    }
                }
                if (payload.size != null && attempt.bytes.get() != payload.size) throw java.io.EOFException()
                output.flush()
                completed.incrementAndGet(); attempt.state = "completed"
            } catch (failure: Exception) {
                interrupted.incrementAndGet(); attempt.error = if (failure is ToolFailure) failure.code else failure.javaClass.simpleName; attempt.state = "interrupted"
                throw failure
            } finally { attempt.endedAt = System.currentTimeMillis() }
        } else {
            output.flush()
        }
    }

    private fun receive(request: List<String>, header: String, input: InputStream, output: OutputStream) {
        fun respond(code: Int, body: String, type: String = "text/plain; charset=utf-8") {
            val bytes = body.toByteArray(Charsets.UTF_8)
            output.write("HTTP/1.1 $code Response\r\nConnection: close\r\nContent-Type: $type\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)); output.write(bytes); output.flush()
        }
        if (request.size != 3 || request[1] != "/$token" || closed.get()) { respond(404, "链接无效或已关闭"); return }
        if (request[0] == "GET") {
            respond(200, """<!doctype html><html lang="zh"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>KaffuBox 文件接收</title><style>body{font:16px system-ui;max-width:640px;margin:40px auto;padding:20px}button,input{font:inherit;margin:12px 0}progress{width:100%}pre{white-space:pre-wrap}</style><h1>发送文件到 KaffuBox</h1><p>单个文件上限 ${maxBytes / 1024 / 1024} MiB，文件名不含路径</p><input id="files" type="file" multiple><br><button id="send">发送所选文件</button><progress id="progress" max="100" value="0"></progress><pre id="status"></pre><script>document.getElementById('send').onclick=async()=>{const b=document.getElementById('send');b.disabled=true;try{for(const f of document.getElementById('files').files){if(f.size>$maxBytes)throw Error(f.name+' 超过大小限制');await new Promise((resolve,reject)=>{const x=new XMLHttpRequest();x.open('PUT',location.pathname);x.setRequestHeader('X-File-Name',encodeURIComponent(f.name));x.upload.onprogress=e=>{if(e.lengthComputable)document.getElementById('progress').value=e.loaded/e.total*100};x.onload=()=>x.status==201?resolve():reject(Error(x.responseText));x.onerror=()=>reject(Error('连接中断'));x.send(f)});document.getElementById('status').textContent+=f.name+' 已发送\n'}}catch(e){document.getElementById('status').textContent+='失败：'+e.message+'\n'}finally{b.disabled=false}};</script></html>""", "text/html; charset=utf-8"); return
        }
        if (request[0] != "PUT") { respond(405, "不支持此请求方式"); return }
        val headers = header.lineSequence().drop(1).filter { it.contains(':') }.map { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }.toList()
        if (headers.count { it.first == "content-length" } != 1 || headers.any { it.first == "transfer-encoding" }) { respond(411, "必须提供单一文件大小"); return }
        val length = headers.first { it.first == "content-length" }.second.toLongOrNull()
        if (length == null || length !in 0..maxBytes) { respond(413, "文件超过大小限制"); return }
        val name = try { safeFileName(URLDecoder.decode(headers.firstOrNull { it.first == "x-file-name" }?.second ?: "Received.bin", "UTF-8")) } catch (_: Exception) { respond(400, "文件名无效"); return }
        if (reservedBytes.addAndGet(length) > maxTotalBytes) { reservedBytes.addAndGet(-length); respond(413, "会话接收容量已达上限"); return }
        var success = false
        try {
            val broker = files ?: throw ToolFailure("fileOpenFailed")
            OutputSession(broker, outputRoot ?: throw ToolFailure("outputDirectoryNotWritable"), "unique", emptyList()).use { outputSession ->
                val ref = outputSession.createOutput(name)
                outputSession.resolve(ref.id).openOutput!!.invoke().use { sink ->
                    var remaining = length; val buffer = ByteArray(65536)
                    while (remaining > 0) {
                        if (closed.get() || System.currentTimeMillis() >= expiresAt) throw ToolFailure("transferClosed")
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (count < 0) throw java.io.EOFException()
                        sink.write(buffer, 0, count); transferred.addAndGet(count.toLong()); remaining -= count
                    }
                }
                if (closed.get()) throw ToolFailure("transferClosed")
                outputSession.commit(); received += broker.resolve(ref.id).ref; completed.incrementAndGet(); success = true
            }
            respond(201, "文件已保存")
        } catch (failure: Exception) { lastError = if (failure is ToolFailure) failure.code else failure.javaClass.simpleName; runCatching { respond(400, "接收失败，未保存未完成的文件") } }
        finally { if (!success) reservedBytes.addAndGet(-length) }
    }

    override fun close() {
        synchronized(expiryLock) {
            if (!closed.compareAndSet(false, true)) return
            expiry?.cancel(false)
        }
        try { server?.close() } catch (_: Exception) { }
        clients.forEach { try { it.close() } catch (_: Exception) { } }
        workers.shutdownNow()
        if (runCatching { workers.awaitTermination(2, TimeUnit.SECONDS) }.getOrDefault(false)) finishCleanup()
        else Thread({
            while (!workers.isTerminated) runCatching { workers.awaitTermination(1, TimeUnit.SECONDS) }
            finishCleanup()
        }, "KaffuShareCleanup").apply { isDaemon = true; start() }
    }

    private fun finishCleanup() {
        // 只有所有下载和上传处理退出后才释放临时 ZIP，避免仍在读取时删除
        removeArchive()
        clients.clear()
        if (quotaBytes > 0) { archivedBytes.addAndGet(-quotaBytes); quotaBytes = 0 }
        runCatching { registration?.close() }
        cleaned.set(true)
    }

    private fun removeArchive() {
        val directory = archiveDirectory ?: return
        val root = tempRoot ?: return
        runCatching { deletePrivateTree(root, directory) }.onFailure { lastError = it.javaClass.simpleName }
    }
}

private fun archiveName(handles: List<FileHandle>): String {
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(LocalDateTime.now())
    return archiveStem(handles.firstOrNull()?.ref?.name ?: "Share") + "-" + stamp + ".zip"
}

private fun archiveStem(name: String): String {
    val cleaned = name.substringBeforeLast('.', name).map { if (it.code < 32 || it in "/\\:*?\"<>|") '_' else it }.joinToString("").trim().trimEnd('.', ' ')
    return cleaned.take(80).ifEmpty { "Share" }
}

// 压缩包内条目只去掉路径分隔符和控制字符，避免用户文件因宿主保留名等原因导致整个分享失败
private fun zipEntryName(name: String): String {
    val cleaned = name.map { if (it.code < 32 || it in "/\\") '_' else it }.joinToString("").trim().trimEnd('.', ' ')
    val base = cleaned.ifEmpty { "File" }
    if (base.length <= 150) return base
    val dot = base.lastIndexOf('.')
    return if (dot > 0) base.take(140) + base.substring(dot, minOf(base.length, dot + 10)) else base.take(150)
}

private fun uniqueEntry(name: String, used: MutableSet<String>): String {
    if (used.add(name.lowercase(Locale.ROOT))) return name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val extension = if (dot > 0) name.substring(dot) else ""
    var index = 1
    while (true) {
        val candidate = "$stem ($index)$extension"
        if (used.add(candidate.lowercase(Locale.ROOT))) return candidate
        index++
    }
}
