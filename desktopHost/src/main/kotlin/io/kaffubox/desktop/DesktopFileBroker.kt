// 管理桌面用户授权文件与宿主输出目录，使用唯一名称创建输出而不覆盖已有文件
package io.kaffubox.desktop

import io.kaffubox.core.*
import java.nio.file.*

class DesktopFileBroker(outputRoot: Path) : FileBroker() {
    private val ownedOutputs = java.util.concurrent.ConcurrentHashMap<String, Path>()
    @Volatile var outputRoot: Path = outputRoot.toAbsolutePath().normalize()
        private set
    init { Files.createDirectories(this.outputRoot) }
    override val outputDirectory: Path get() = outputRoot

    fun setOutputDirectory(directory: Path) {
        val target = directory.toAbsolutePath().normalize()
        requireWritableDirectory(target)
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

    override fun discard(ref: FileRef) {
        val handle = resolve(ref.id)
        val target = Paths.get(handle.localPath ?: throw ToolFailure("localFileRequired")).toAbsolutePath().normalize()
        if (!handle.ref.writable || ownedOutputs[ref.id] != target) throw ToolFailure("outputReferenceRequired")
        Files.deleteIfExists(target); ownedOutputs.remove(ref.id); revoke(ref.id)
    }

    override fun renameBatch(names: List<Pair<String, String>>): List<FileRef> = synchronized(this) {
        val sources = names.map { (id, name) -> val handle = resolve(id); val path = Path.of(handle.localPath ?: throw ToolFailure("localFileRequired")).toRealPath(); Triple(handle, path, path.resolveSibling(safeFileName(name))) }
        requireArgument(sources.map { it.second }.distinct().size == sources.size, "files")
        requireArgument(sources.map { it.third.toString().lowercase(java.util.Locale.ROOT) }.distinct().size == sources.size, "duplicateOutputNames")
        val sourcePaths = sources.map { it.second }.toSet()
        sources.forEach { (_, from, to) -> if (Files.exists(to) && to !in sourcePaths && !Files.isSameFile(from, to)) throw ToolFailure("outputAlreadyExists") }
        val staged = mutableListOf<Pair<Path, Path>>()
        val completed = mutableListOf<Pair<Path, Path>>()
        try {
            sources.forEach { (_, from, _) -> val temp = Files.createTempFile(from.parent, ".KaffuRename", ".tmp"); try { Files.move(from, temp, StandardCopyOption.REPLACE_EXISTING); staged += from to temp } catch (failure: Throwable) { Files.deleteIfExists(temp); throw failure } }
            sources.forEachIndexed { index, (_, from, to) -> Files.move(staged[index].second, to); completed += from to to }
            sources.map { (handle, _, target) ->
                val ref = handle.ref.copy(name = target.fileName.toString(), size = Files.size(target))
                if (ref.writable) ownedOutputs[ref.id] = target
                remember(FileHandle(ref, { Files.newInputStream(target) }, localPath = target.toString(), refresh = { ref.copy(size = Files.size(target)) }))
            }
        } catch (failure: Throwable) {
            completed.asReversed().forEach { (from, to) -> val temp = staged.single { it.first == from }.second; runCatching { Files.move(to, temp) } }
            staged.asReversed().forEach { (from, temp) -> if (Files.exists(temp)) runCatching { Files.move(temp, from) } }
            throw failure
        }
    }
}
