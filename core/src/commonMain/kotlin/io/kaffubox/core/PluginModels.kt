// 定义插件清单、权限、工具入口、签名和已安装版本的持久化格式
package io.kaffubox.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class PluginRuntime { JavaScript, Python }

@Serializable
data class PluginTool(
    val id: String,
    val workbench: Workbench,
    val function: String,
    val inputSchema: JsonObject = JsonObject(emptyMap()),
    val name: String? = null,
    val description: String? = null
)

@Serializable
data class PluginManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val version: String,
    val apiVersion: Int = 1,
    val platforms: Set<String> = setOf("windows", "android"),
    val runtime: PluginRuntime = PluginRuntime.JavaScript,
    val entry: String,
    val permissions: Set<Capability> = emptySet(),
    val tools: List<PluginTool>,
    val dependencies: Map<String, String> = emptyMap(),
    val ui: String? = null,
    val name: String? = null,
    val description: String? = null
)

@Serializable
data class PluginSignature(
    val algorithm: String = "SHA256withRSA",
    val keyId: String,
    val hashes: Map<String, String>,
    val signature: String
)

@Serializable
data class InstalledPlugin(
    val manifest: PluginManifest,
    val enabled: Boolean,
    val granted: Set<Capability>,
    val signedBy: String? = null,
    val installedAt: Long,
    val packageHash: String,
    val lastError: String? = null
)

@Serializable
data class ScriptLimits(
    val timeoutMillis: Long = 30_000,
    val maxInstructions: Long = 10_000_000,
    val maxResultChars: Int = 2_000_000,
    val maxHostCalls: Int = 256
)

@Serializable
data class ScriptRequest(
    val source: String,
    val function: String,
    val arguments: JsonObject,
    val limits: ScriptLimits = ScriptLimits()
)
