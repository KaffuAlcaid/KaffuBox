// 保存桌面外观、输出目录、收藏和计算器显示设置，使用原子写入保护本地配置
package io.kaffubox.app

import io.kaffubox.core.AtomicStore
import io.kaffubox.core.CoreJson
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class AppPreferences(
    val theme: String = "system",
    val outputDirectory: String = "",
    val favorites: List<String> = listOf("science.calculate", "media.videoToGif", "image.transform", "text.lines"),
    val allowUnsignedPlugins: Boolean = false,
    val calculatorAngle: String = "deg",
    val calculatorDigits: Int = 12,
    val calculatorFormat: String = "auto",
    val rememberParameters: Boolean = true,
    val toolOptions: Map<String, Map<String, String>> = emptyMap(),
    val notifyCompleted: Boolean = false
)

class PreferencesStore(root: Path) {
    private val store = AtomicStore(root)
    fun load(): AppPreferences = store.read("Preferences.json")?.let {
        try { CoreJson.decodeFromString(AppPreferences.serializer(), it) } catch (_: Exception) { AppPreferences() }
    } ?: AppPreferences()
    fun save(preferences: AppPreferences) {
        store.write("Preferences.json", CoreJson.encodeToString(AppPreferences.serializer(), preferences))
    }
}
