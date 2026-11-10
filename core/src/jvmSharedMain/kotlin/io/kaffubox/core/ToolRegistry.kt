// 注册可版本化工具并为执行任务保留提供者租约，支持插件更新期间旧任务继续运行
package io.kaffubox.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

fun interface ToolOperation {
    suspend fun execute(arguments: JsonObject, context: ToolContext): JsonElement
}

class ToolProvider(
    val id: String,
    val version: String,
    val tools: List<Pair<ToolDescriptor, ToolOperation>>,
    private val onReleased: () -> Unit = {}
) {
    private val references = AtomicInteger()
    private val retired = AtomicBoolean()
    private val released = AtomicBoolean()
    private val resources = mutableListOf<AutoCloseable>()

    fun own(resource: AutoCloseable): AutoCloseable {
        synchronized(resources) {
            if (released.get()) resource.close() else resources += resource
        }
        return AutoCloseable { synchronized(resources) { resources.remove(resource) } }
    }

    internal fun acquire(): AutoCloseable {
        references.incrementAndGet()
        val closed = AtomicBoolean()
        return AutoCloseable { if (closed.compareAndSet(false, true)) { references.decrementAndGet(); tryRelease() } }
    }

    fun retire() { retired.set(true); tryRelease() }
    fun activeReferences(): Int = references.get()
    private fun tryRelease() {
        if (retired.get() && references.get() == 0 && released.compareAndSet(false, true)) {
            val closing = synchronized(resources) { resources.toList().also { resources.clear() } }
            closing.forEach { try { it.close() } catch (_: Exception) { } }
            onReleased()
        }
    }
}

data class ToolLease(val descriptor: ToolDescriptor, val operation: ToolOperation, val release: AutoCloseable, val owner: ToolProvider)

class ToolRegistry {
    private val lock = Any()
    private val providers = linkedMapOf<String, ToolProvider>()

    fun install(provider: ToolProvider) {
        synchronized(lock) {
            val ids = provider.tools.map { it.first.id }
            if (ids.size != ids.toSet().size) throw ToolFailure("toolIdDuplicate")
            val occupied = providers.filterKeys { it != provider.id }.values.flatMap { it.tools }.map { it.first.id }.toSet()
            if (ids.any { it in occupied }) throw ToolFailure("toolIdConflict")
            val old = providers.put(provider.id, provider)
            old?.retire()
        }
    }

    fun remove(providerId: String) { synchronized(lock) { providers.remove(providerId)?.retire() } }
    fun list(): List<ToolDescriptor> = synchronized(lock) { providers.values.flatMap { it.tools.map(Pair<ToolDescriptor, ToolOperation>::first) }.sortedBy { it.id } }

    fun acquire(toolId: String): ToolLease = synchronized(lock) {
        for (provider in providers.values) {
            val entry = provider.tools.firstOrNull { it.first.id == toolId } ?: continue
            return@synchronized ToolLease(entry.first, entry.second, provider.acquire(), provider)
        }
        throw ToolFailure("toolNotFound", jsonObject("toolId" to toolId))
    }
}

class ToolContext(
    val files: FileBroker,
    val granted: Set<Capability>,
    val platform: PlatformInfo,
    val registry: ToolRegistry,
    val progress: (Long, Long?, String?) -> Unit = { _, _, _ -> },
    private val callPath: List<String> = emptyList(),
    private val owner: ToolProvider? = null
) {
    fun own(resource: AutoCloseable): AutoCloseable = (owner ?: throw ToolFailure("resourceOwnerMissing")).own(resource)
    suspend fun checkpoint(completed: Long? = null, total: Long? = null, phase: String? = null) {
        currentCoroutineContext().ensureActive()
        if (completed != null) progress(completed, total, phase)
    }

    suspend fun invoke(toolId: String, arguments: JsonObject): JsonElement {
        if (callPath.size >= 12 || toolId in callPath) throw ToolFailure("toolRecursion")
        val lease = registry.acquire(toolId)
        lease.release.use {
            if (!granted.containsAll(lease.descriptor.capabilities)) throw ToolFailure("permissionDenied")
            return lease.operation.execute(arguments, ToolContext(files, granted, platform, registry, progress, callPath + toolId, owner ?: lease.owner))
        }
    }

    fun restricted(capabilities: Set<Capability>): ToolContext =
        ToolContext(files, granted.intersect(capabilities), platform, registry, progress, callPath, owner)
}
