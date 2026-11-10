// 通过 Android 系统服务读取真实设备、内存、电池和传感器能力
package io.kaffubox.android

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import io.kaffubox.core.*

object AndroidDeviceTools {
    fun definitions(context: Context): List<ToolDefinition> = listOf(
        defineTool("device.system", Workbench.Device, capabilities = setOf(Capability.DeviceRead)) { _, _ ->
            val memory = ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
            val storage = StatFs(context.filesDir.absolutePath)
            val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            jsonObject("manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "androidVersion" to Build.VERSION.RELEASE, "apiLevel" to Build.VERSION.SDK_INT,
                "abis" to Build.SUPPORTED_ABIS.toList(), "processors" to Runtime.getRuntime().availableProcessors(),
                "memory" to jsonObject("total" to memory.totalMem, "available" to memory.availMem, "lowMemory" to memory.lowMemory),
                "storage" to jsonObject("total" to storage.totalBytes, "available" to storage.availableBytes),
                "battery" to jsonObject("capacityPercent" to battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }, "charging" to battery.isCharging))
        },
        defineTool("device.sensors", Workbench.Device, capabilities = setOf(Capability.DeviceRead)) { _, _ ->
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            jsonObject("sensors" to manager.getSensorList(Sensor.TYPE_ALL).map { sensor ->
                jsonObject("name" to sensor.name, "vendor" to sensor.vendor, "type" to sensor.stringType, "resolution" to sensor.resolution, "maximumRange" to sensor.maximumRange, "powerMilliampere" to sensor.power)
            })
        }
    )
}
