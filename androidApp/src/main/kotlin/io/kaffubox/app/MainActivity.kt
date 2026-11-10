// 启动移动端 Compose 界面并接收系统文件选择、结果保存和分享导入
package io.kaffubox.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val controller get() = (application as KaffuApplication).controller
    private val openFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> controller.finishPick(uris) }
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> controller.finishPick(listOfNotNull(uri)) }
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> controller.finishSave(uri) }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller.launchPicker = { types, multiple -> if (multiple) openFiles.launch(types) else openFile.launch(types) }
        controller.launchSave = { name -> saveFile.launch(name) }
        controller.requestNotifications = { if (android.os.Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        setContent { KaffuTheme(controller.preferences.theme) { MobileAppShell(controller) } }
        if (savedInstanceState == null) handleShared(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleShared(intent) }
    private fun handleShared(intent: Intent) {
        @Suppress("DEPRECATION")
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) controller.importUris(uris)
    }
}
