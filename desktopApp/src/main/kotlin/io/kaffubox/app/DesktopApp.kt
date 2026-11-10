// 启动 Windows 桌面窗口、文件拖放与托盘通知，并分派独立脚本工作进程
package io.kaffubox.app

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import io.kaffubox.desktop.runScriptWorker
import java.awt.*
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    if (args.contains("--scriptWorker")) { runScriptWorker(); return }
    val arguments = args.toList().chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
    val controller = try { AppController(arguments["--dataDir"]?.let(Path::of) ?: AppController.defaultDataRoot(), arguments) }
    catch (failure: Exception) { JOptionPane.showMessageDialog(null, "无法启动 KaffuBox\n" + (failure.message ?: failure.javaClass.simpleName), "KaffuBox", JOptionPane.ERROR_MESSAGE); return }
    application {
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val state = rememberWindowState(width = minOf(1180, screen.width - 60).dp, height = minOf(850, screen.height - 70).dp, position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center))
        controller.onExit = ::exitApplication
        DisposableEffect(Unit) { onDispose { controller.close() } }
        Window(onCloseRequest = controller::closeRequested, title = "KaffuBox", state = state, visible = controller.visible) {
            DisposableEffect(window) {
                controller.window = window
                window.minimumSize = Dimension(800, 600)
                val icon = appIcon()
                window.iconImage = icon
                val tray = if (SystemTray.isSupported()) runCatching {
                    TrayIcon(icon, "KaffuBox").apply {
                        isImageAutoSize = true
                        addActionListener { SwingUtilities.invokeLater { window.isVisible = true; window.toFront(); window.requestFocus() } }
                        popupMenu = PopupMenu().apply {
                            add(MenuItem("打开 KaffuBox").apply { addActionListener { SwingUtilities.invokeLater { window.toFront(); window.requestFocus() } } })
                            add(MenuItem("退出").apply { addActionListener { SwingUtilities.invokeLater { controller.closeRequested() } } })
                        }
                        SystemTray.getSystemTray().add(this)
                    }
                }.getOrNull() else null
                controller.notify = { text -> tray?.displayMessage("KaffuBox", text, TrayIcon.MessageType.INFO) }
                onDispose { controller.window = null; controller.notify = null; tray?.let { SystemTray.getSystemTray().remove(it) } }
            }
            KaffuTheme(controller.preferences.theme) { AppShell(controller) }
        }
    }
}

private fun appIcon(): BufferedImage = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply {
    createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        color = Color(49, 108, 130); fillRoundRect(0, 0, 64, 64, 20, 20)
        color = Color.WHITE; font = Font("Segoe UI", Font.BOLD, 43); drawString("K", 17, 48); dispose()
    }
}
