// 调度可取消任务、广播状态并在提交时固定工具版本，避免插件更新改变正在执行的任务
package io.kaffubox.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TaskManager(
    private val registry: ToolRegistry,
    private val files: FileBroker,
    private val platform: PlatformInfo,
    concurrency: Int = 4,
    private val onSnapshot: (TaskSnapshot) -> Unit = {}
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val permits = Semaphore(concurrency.coerceIn(1, 32))
    private val states = ConcurrentHashMap<String, MutableStateFlow<TaskSnapshot>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val observableTasks = MutableStateFlow<List<TaskSnapshot>>(emptyList())
    val snapshots: StateFlow<List<TaskSnapshot>> = observableTasks.asStateFlow()

    fun submit(toolId: String, arguments: JsonObject, granted: Set<Capability>): String {
        val lease = registry.acquire(toolId)
        if (!granted.containsAll(lease.descriptor.capabilities)) {
            lease.release.close()
            throw ToolFailure("permissionDenied")
        }
        val id = UUID.randomUUID().toString()
        val initial = TaskSnapshot(id, toolId, lease.descriptor.providerId, lease.descriptor.providerVersion, TaskState.Queued, System.currentTimeMillis())
        val state = MutableStateFlow(initial)
        states[id] = state
        observableTasks.value = list()
        onSnapshot(initial)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                permits.withPermit {
                    update(id) { it.copy(state = TaskState.Running, startedAt = System.currentTimeMillis()) }
                    val context = ToolContext(files, granted, platform, registry, { completed, total, phase ->
                        update(id) { it.copy(completed = completed.coerceAtLeast(0), total = total, phase = phase) }
                    }, owner = lease.owner)
                    val result = lease.operation.execute(arguments, context)
                    ensureActive()
                    update(id) { it.copy(state = TaskState.Succeeded, finishedAt = System.currentTimeMillis(), result = result) }
                }
            } catch (cancelled: CancellationException) {
                update(id) { it.copy(state = TaskState.Cancelled, finishedAt = System.currentTimeMillis()) }
            } catch (failure: ToolFailure) {
                update(id) { it.copy(state = TaskState.Failed, finishedAt = System.currentTimeMillis(), errorCode = failure.code, errorDetails = failure.details) }
            } catch (failure: Exception) {
                update(id) { it.copy(state = TaskState.Failed, finishedAt = System.currentTimeMillis(), errorCode = "executionFailed", errorDetails = jsonObject("type" to failure.javaClass.simpleName, "reason" to (failure as? java.nio.file.FileSystemException)?.reason)) }
            }
        }
        jobs[id] = job
        job.invokeOnCompletion {
            lease.release.close()
            jobs.remove(id)
            if (state.value.state in setOf(TaskState.Queued, TaskState.Cancelling)) {
                update(id) { it.copy(state = TaskState.Cancelled, finishedAt = System.currentTimeMillis()) }
            }
        }
        job.start()
        return id
    }

    fun snapshot(id: String): TaskSnapshot = states[id]?.value ?: throw ToolFailure("taskNotFound")
    fun observe(id: String): StateFlow<TaskSnapshot> = states[id]?.asStateFlow() ?: throw ToolFailure("taskNotFound")
    fun list(): List<TaskSnapshot> = states.values.map { it.value }.sortedByDescending { it.createdAt }
    suspend fun await(id: String): TaskSnapshot { jobs[id]?.join(); return snapshot(id) }

    fun cancel(id: String): Boolean {
        val job = jobs[id] ?: return false
        update(id) { if (it.state in setOf(TaskState.Queued, TaskState.Running)) it.copy(state = TaskState.Cancelling) else it }
        job.cancel()
        return true
    }

    fun cancelProvider(providerId: String) { list().filter { it.providerId == providerId }.forEach { cancel(it.id) } }

    fun forget(id: String) {
        if (jobs.containsKey(id)) throw ToolFailure("taskStillRunning")
        states.remove(id)
        observableTasks.value = list()
    }

    private fun update(id: String, transform: (TaskSnapshot) -> TaskSnapshot) {
        val state = states[id] ?: return
        synchronized(state) { state.value = transform(state.value); observableTasks.value = list(); onSnapshot(state.value) }
    }

    override fun close() { scope.cancel() }
}
