// 为 Android 应用组装文件授权、设备与图像工具和隔离脚本运行时
package io.kaffubox.android

import android.content.Context
import android.os.Build
import io.kaffubox.core.*
import java.io.File

object AndroidHostFactory {
    fun create(context: Context, allowUnsignedPlugins: Boolean = false): KaffuHost {
        val application = context.applicationContext
        val root = File(application.filesDir, "KaffuBox")
        val files = AndroidFileBroker(application, File(root, "Outputs"))
        val platform = PlatformInfo("android", Build.VERSION.RELEASE, Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        return KaffuHost(files, platform, root.toPath(), AndroidScriptRuntime(application), allowUnsignedPlugins, AndroidDeviceTools.definitions(application) + ImageTools(AndroidImageCodec()).definitions() + AndroidMediaTools(application, files).definitions())
    }
}
