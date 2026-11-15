// 为单个任务固定输出目录与冲突策略，先写临时文件，成功后发布并在失败时清理
package io.kaffubox.core

import java.nio.file.*

class OutputSession(private val parent: FileBroker, directory: Path, private val policy: String, inputs: List<FileRef>) : FileBroker(), AutoCloseable {
    private val inputRefs = inputs.toList()
    fun fresh() = OutputSession(parent, root, policy, inputRefs)
    private val root = directory.toAbsolutePath().normalize()
    private val protectedPaths = inputs.mapNotNull { parent.resolve(it.id).localPath?.let(Path::of)?.toRealPath() }.toSet()
    private data class Pending(val ref: FileRef, val temporary: Path, val target: Path, val reserved: Boolean)
    private val pending = linkedMapOf<String, Pending>()
    private var committed = false
    init { requireArgument(policy in setOf("unique", "fail", "replace"), "overwritePolicy"); requireWritableDirectory(root) }
    override fun resolve(id: String): FileHandle = if (pending.containsKey(id)) super.resolve(id) else parent.resolve(id)
    override fun createOutput(name: String, mimeType: String?): FileRef {
        val safe = safeFileName(name); val extension = safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }; val stem = safe.removeSuffix(extension)
        var target = root.resolve(safe)
        var reserved = false
        if (policy == "replace") {
            if (Files.isSymbolicLink(target) || protectedPaths.any { it == target || Files.exists(target) && Files.isSameFile(it, target) }) throw ToolFailure("outputWouldReplaceInput")
            if (pending.values.any { it.target == target }) throw ToolFailure("duplicateOutputNames")
        } else {
            for (index in 0..10000) {
                target = root.resolve(if (index == 0) safe else "$stem ($index)$extension")
                try { Files.createFile(target); reserved = true; break }
                catch (failure: FileAlreadyExistsException) { if (policy == "fail") throw ToolFailure("outputAlreadyExists"); if (index == 10000) throw ToolFailure("outputNameExhausted") }
            }
        }
        val temporary = try { Files.createTempFile(root, ".KaffuPending", extension) } catch (failure: Throwable) { if (reserved) Files.deleteIfExists(target); throw failure }
        val ref = FileRef(nextId(), target.fileName.toString(), null, mimeType, true)
        pending[ref.id] = Pending(ref, temporary, target, reserved)
        return remember(FileHandle(ref, { Files.newInputStream(temporary) }, { Files.newOutputStream(temporary) }, temporary.toString(), { ref.copy(size = Files.size(temporary)) }))
    }
    override fun discard(ref: FileRef) { pending.remove(ref.id)?.let { Files.deleteIfExists(it.temporary); if (it.reserved) Files.deleteIfExists(it.target); revoke(ref.id) } }
    override fun renameBatch(names: List<Pair<String, String>>) = parent.renameBatch(names)
    fun commit() {
        for (item in pending.values.toList()) {
            if (Files.isSymbolicLink(item.target) || protectedPaths.any { it == item.target || Files.exists(item.target) && Files.isSameFile(it, item.target) }) throw ToolFailure("outputWouldReplaceInput")
            try { Files.move(item.temporary, item.target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(item.temporary, item.target, StandardCopyOption.REPLACE_EXISTING) }
            val ref = item.ref.copy(size = Files.size(item.target))
            parent.publish(FileHandle(ref, { Files.newInputStream(item.target) }, localPath = item.target.toString(), refresh = { ref.copy(size = Files.size(item.target)) }))
            pending.remove(item.ref.id)
        }
        committed = true
    }
    override fun close() { if (!committed) pending.values.toList().forEach { discard(it.ref) } }
}
