// 定义跨平台工具、文件引用、执行状态和能力声明的数据协议
package io.kaffubox.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class Workbench {
    Format, Media, Image, File, Document, Text, Developer, Network, Device, Transfer, Science, Daily
}

@Serializable
enum class Capability { FileRead, FileWrite, Network, DeviceRead, NativeProcess }

@Serializable
data class ToolDescriptor(
    val id: String,
    val workbench: Workbench,
    val capabilities: Set<Capability> = emptySet(),
    val inputSchema: JsonObject = JsonObject(emptyMap()),
    val providerId: String = "builtin",
    val providerVersion: String = "1.0.0",
    val displayName: String? = null,
    val description: String? = null
)

@Serializable
data class FileRef(
    val id: String,
    val name: String,
    val size: Long? = null,
    val mimeType: String? = null,
    val writable: Boolean = false
)

@Serializable
enum class TaskState { Queued, Running, Cancelling, Succeeded, Failed, Cancelled }

@Serializable
data class TaskSnapshot(
    val id: String,
    val toolId: String,
    val providerId: String,
    val providerVersion: String,
    val state: TaskState,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val completed: Long = 0,
    val total: Long? = null,
    val phase: String? = null,
    val result: JsonElement? = null,
    val errorCode: String? = null,
    val errorDetails: JsonObject? = null
)

@Serializable
data class PlatformInfo(val id: String, val version: String, val architecture: String)

@Serializable
data class EngineStatus(val id: String, val available: Boolean, val version: String? = null)
