// 在宿主私有目录中原子保存 JSON 状态，并提供限定根目录的文件清理
package io.kaffubox.core

import java.nio.file.*
import java.util.UUID

class AtomicStore(private val root: Path) {
    init { Files.createDirectories(root) }
    fun read(name: String): String? {
        val path = root.resolve(safeFileName(name))
        if (!Files.exists(path)) return null
        if (Files.size(path) > 16_000_000) throw ToolFailure("stateTooLarge")
        return String(Files.readAllBytes(path), Charsets.UTF_8)
    }
    fun write(name: String, value: String) {
        val path = root.resolve(safeFileName(name))
        val temporary = root.resolve("Pending" + UUID.randomUUID().toString().replace("-", "") + ".tmp")
        try {
            Files.write(temporary, value.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
}

fun deletePrivateTree(root: Path, target: Path) {
    val base = root.toAbsolutePath().normalize()
    val candidate = target.toAbsolutePath().normalize()
    if (candidate == base || !candidate.startsWith(base)) throw ToolFailure("privatePathInvalid")
    if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return
    val paths = Files.walk(candidate).use { stream -> stream.sorted(Comparator.reverseOrder()).iterator().asSequence().toList() }
    paths.forEach { path ->
        var removed = false
        for (attempt in 0..3) {
            try { Files.deleteIfExists(path); removed = true; break }
            catch (failure: FileSystemException) {
                if (attempt == 3) throw failure
                Thread.sleep(25L * (attempt + 1))
            }
        }
        check(removed)
    }
}
