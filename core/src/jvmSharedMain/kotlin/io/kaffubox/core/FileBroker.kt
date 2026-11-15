// 通过不可猜测的引用管理已授权文件，隔离路径并限制内存读取和输出命名
package io.kaffubox.core

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class FileHandle(
    val ref: FileRef,
    val openInput: () -> InputStream,
    val openOutput: (() -> OutputStream)? = null,
    val localPath: String? = null,
    val refresh: (() -> FileRef)? = null
)

abstract class FileBroker {
    private val handles = ConcurrentHashMap<String, FileHandle>()
    private val random = SecureRandom()

    protected fun nextId(): String = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    protected fun remember(handle: FileHandle): FileRef {
        handles[handle.ref.id] = handle
        return handle.ref
    }

    open fun resolve(id: String): FileHandle {
        val handle = handles[id] ?: throw ToolFailure("fileReferenceUnknown")
        return handle.refresh?.let { handle.copy(ref = it()) } ?: handle
    }
    fun revoke(id: String) { handles.remove(id) }
    internal fun publish(handle: FileHandle): FileRef = remember(handle)
    abstract fun createOutput(name: String, mimeType: String? = null): FileRef
    open val outputDirectory: java.nio.file.Path? get() = null
    open fun discard(ref: FileRef) { throw ToolFailure("outputReferenceRequired") }
    open fun renameBatch(names: List<Pair<String, String>>): List<FileRef> { throw ToolFailure("renameUnsupported") }

    fun readBytes(id: String, limit: Int = 16 * 1024 * 1024): ByteArray {
        requireArgument(limit in 1..128 * 1024 * 1024, "limit")
        val handle = resolve(id)
        if ((handle.ref.size ?: 0) > limit) throw ToolFailure("inputTooLarge")
        return handle.openInput().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > limit) throw ToolFailure("inputTooLarge")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    fun writeBytes(name: String, bytes: ByteArray, mimeType: String? = null): FileRef {
        val ref = createOutput(safeFileName(name), mimeType)
        try { resolve(ref.id).openOutput!!.invoke().use { it.write(bytes) } }
        catch (failure: Throwable) { runCatching { discard(ref) }; throw failure }
        return ref.copy(size = bytes.size.toLong())
    }
}

fun requireWritableDirectory(directory: java.nio.file.Path) {
    // 精简 Windows 运行时的权限预判可能误报，使用实际创建和删除验证输出目录
    try {
        java.nio.file.Files.createDirectories(directory)
        val probe = java.nio.file.Files.createTempFile(directory, ".KaffuWriteProbe", ".tmp")
        java.nio.file.Files.delete(probe)
    } catch (_: java.io.IOException) { throw ToolFailure("outputDirectoryNotWritable") }
    catch (_: SecurityException) { throw ToolFailure("outputDirectoryNotWritable") }
}

fun safeFileName(name: String): String {
    if (name.isBlank() || name.length > 180 || name.endsWith('.') || name.endsWith(' ') ||
        name.any { it.code < 32 || it in "/\\:*?\"<>|" } || name in setOf(".", "..")) {
        throw ToolFailure("fileNameInvalid")
    }
    val stem = name.substringBefore('.').uppercase(java.util.Locale.ROOT)
    if (stem in setOf("CON", "PRN", "AUX", "NUL") || stem.matches(Regex("(?:COM|LPT)[1-9]"))) {
        throw ToolFailure("fileNameReserved")
    }
    return name
}
