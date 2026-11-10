// 读取桌面操作系统、JVM 内存、处理器数量和可访问存储空间
package io.kaffubox.desktop

import io.kaffubox.core.*
import java.nio.file.FileSystems

object DesktopDeviceTools {
    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("device.system", Workbench.Device, capabilities = setOf(Capability.DeviceRead)) { _, context ->
            val runtime = Runtime.getRuntime()
            jsonObject("platform" to context.platform.id, "os" to System.getProperty("os.name"), "version" to System.getProperty("os.version"), "architecture" to System.getProperty("os.arch"), "processors" to runtime.availableProcessors(),
                "runtimeMemory" to jsonObject("allocated" to runtime.totalMemory(), "free" to runtime.freeMemory(), "maximum" to runtime.maxMemory()),
                "storage" to FileSystems.getDefault().fileStores.mapNotNull { store -> try { jsonObject("name" to store.name(), "type" to store.type(), "total" to store.totalSpace, "usable" to store.usableSpace) } catch (_: java.io.IOException) { null } })
        }
    )
}
