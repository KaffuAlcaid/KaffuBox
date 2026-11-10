// 组装内置工具、任务中心和插件管理，提供供双端应用调用的无界面核心入口
package io.kaffubox.core

import kotlinx.serialization.json.*
import java.nio.file.Path

class KaffuHost(
    val files: FileBroker,
    val platform: PlatformInfo,
    private val dataRoot: Path,
    runtime: ScriptRuntimePort,
    allowUnsignedPlugins: Boolean = false,
    additionalTools: List<ToolDefinition> = emptyList()
) : AutoCloseable {
    val registry = ToolRegistry()
    val tasks: TaskManager
    val plugins: PluginManager
    private val sharing = TransferTools()

    init {
        val builtins = TextTools.definitions() + DeveloperTools.definitions() + FileTools.definitions() +
            DocumentTools.definitions() + NetworkTools.definitions() + ScienceTools.definitions() + DailyTools.definitions() + MediaMetadata.definitions() + sharing.definitions() + additionalTools
        registry.install(ToolProvider("builtin", "1.0.0", builtins))
        tasks = TaskManager(registry, files, platform)
        plugins = PluginManager(dataRoot.resolve("Plugins"), platform, registry, runtime, allowUnsignedPlugins)
    }

    suspend fun dispatch(method: String, arguments: JsonObject): JsonElement = when (method) {
        "host.info" -> jsonObject("apiVersion" to 1, "platform" to CoreJson.encodeToJsonElement(platform), "toolCount" to registry.list().size, "runtimes" to listOf("JavaScript"))
        "tools.list" -> CoreJson.encodeToJsonElement(registry.list())
        "tools.run", "tasks.submit" -> {
            val granted = (arguments["granted"] as? JsonArray)?.map { Capability.valueOf(it.jsonPrimitive.content) }?.toSet() ?: Capability.entries.toSet()
            val id = tasks.submit(arguments.string("toolId"), arguments.objectValue("arguments"), granted)
            if (method == "tools.run") CoreJson.encodeToJsonElement(tasks.await(id)) else jsonObject("taskId" to id)
        }
        "tasks.list" -> CoreJson.encodeToJsonElement(tasks.list())
        "tasks.get" -> CoreJson.encodeToJsonElement(tasks.snapshot(arguments.string("taskId")))
        "tasks.await" -> CoreJson.encodeToJsonElement(tasks.await(arguments.string("taskId")))
        "tasks.cancel" -> jsonObject("cancelled" to tasks.cancel(arguments.string("taskId")))
        "tasks.forget" -> { tasks.forget(arguments.string("taskId")); jsonObject("removed" to true) }
        "files.inspect" -> CoreJson.encodeToJsonElement(files.resolve(arguments.string("file")).ref)
        "files.readText" -> jsonObject("text" to strictUtf8(files.readBytes(arguments.string("file"), arguments.int("maxBytes", 2_000_000))))
        "files.writeText" -> CoreJson.encodeToJsonElement(files.writeBytes(arguments.string("name"), arguments.string("text").toByteArray(Charsets.UTF_8), "text/plain"))
        "plugins.list" -> CoreJson.encodeToJsonElement(plugins.list())
        "plugins.trust" -> { plugins.trust(arguments.string("keyId"), arguments.string("publicKey")); jsonObject("trusted" to true) }
        "plugins.install" -> {
            val handle = files.resolve(arguments.string("file"))
            CoreJson.encodeToJsonElement(plugins.install(handle.openInput))
        }
        "plugins.enable" -> CoreJson.encodeToJsonElement(plugins.enable(arguments.string("id"), arguments.strings("granted").map { Capability.valueOf(it) }.toSet()))
        "plugins.disable" -> {
            val id = arguments.string("id")
            val result = plugins.disable(id)
            if (arguments.flag("cancelRunning")) tasks.cancelProvider(id)
            CoreJson.encodeToJsonElement(result)
        }
        "plugins.uninstall" -> {
            val id = arguments.string("id"); plugins.uninstall(id)
            if (arguments.flag("cancelRunning")) tasks.cancelProvider(id)
            jsonObject("removed" to true)
        }
        "plugins.rollback" -> CoreJson.encodeToJsonElement(plugins.rollback(arguments.string("id"), arguments.string("version")))
        else -> throw ToolFailure("methodUnknown", jsonObject("method" to method))
    }

    override fun close() { tasks.close(); plugins.close(); registry.remove("builtin"); sharing.close() }
}
