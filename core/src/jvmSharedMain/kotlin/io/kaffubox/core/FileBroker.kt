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

    fun resolve(id: String): FileHandle {
        val handle = handles[id] ?: throw ToolFailure("fileReferenceUnknown")
        return handle.refresh?.let { handle.copy(ref = it()) } ?: handle
    }
    fun revoke(id: String) { handles.remove(id) }
    abstract fun createOutput(name: String, mimeType: String? = null): FileRef

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
        resolve(ref.id).openOutput!!.invoke().use { it.write(bytes) }
        return ref.copy(size = bytes.size.toLong())
    }
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
