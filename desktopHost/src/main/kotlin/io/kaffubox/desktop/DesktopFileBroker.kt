// 管理桌面用户授权文件与宿主输出目录，使用唯一名称创建输出而不覆盖已有文件
package io.kaffubox.desktop

import io.kaffubox.core.*
import java.nio.file.*

class DesktopFileBroker(outputRoot: Path) : FileBroker() {
    private val ownedOutputs = java.util.concurrent.ConcurrentHashMap<String, Path>()
    @Volatile var outputRoot: Path = outputRoot.toAbsolutePath().normalize()
        private set
    init { Files.createDirectories(this.outputRoot) }

    fun setOutputDirectory(directory: Path) {
        val target = directory.toAbsolutePath().normalize()
        Files.createDirectories(target)
        if (!Files.isDirectory(target) || !Files.isWritable(target)) throw ToolFailure("outputDirectoryNotWritable")
        outputRoot = target
    }

    fun register(path: Path): FileRef {
        val real = path.toRealPath()
        if (!Files.isRegularFile(real)) throw ToolFailure("fileRequired")
        val ref = FileRef(nextId(), real.fileName.toString(), Files.size(real), Files.probeContentType(real), false)
        return remember(FileHandle(ref, { Files.newInputStream(real) }, localPath = real.toString(), refresh = { ref.copy(size = Files.size(real)) }))
    }

    override fun createOutput(name: String, mimeType: String?): FileRef {
        val safe = safeFileName(name)
        val extension = safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val stem = if (extension.isEmpty()) safe else safe.dropLast(extension.length)
        for (index in 0..9999) {
            val target = outputRoot.resolve(if (index == 0) safe else "$stem ($index)$extension")
            try { Files.createFile(target) } catch (_: FileAlreadyExistsException) { continue }
            val ref = FileRef(nextId(), target.fileName.toString(), null, mimeType, true)
            ownedOutputs[ref.id] = target
            return remember(FileHandle(ref, { Files.newInputStream(target) }, { Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING) }, target.toString(), { ref.copy(size = Files.size(target)) }))
        }
        throw ToolFailure("outputNameExhausted")
    }

    fun discard(ref: FileRef) {
        val handle = resolve(ref.id)
        val target = Paths.get(handle.localPath ?: throw ToolFailure("localFileRequired")).toAbsolutePath().normalize()
        if (!handle.ref.writable || ownedOutputs[ref.id] != target) throw ToolFailure("outputReferenceRequired")
        Files.deleteIfExists(target); ownedOutputs.remove(ref.id); revoke(ref.id)
    }
}
