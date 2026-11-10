// 在 Compose 内容区域接收系统文件拖放并显示放置提示
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package io.kaffubox.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
import java.nio.file.Path

fun droppedFiles(event: DragAndDropEvent): List<Path> =
    (event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
        .orEmpty().filterIsInstance<java.io.File>().map { it.toPath() }

@Composable
fun FileDropArea(onFiles: (List<Path>) -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var hovering by remember { mutableStateOf(false) }
    val callback by rememberUpdatedState(onFiles)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { hovering = true }
            override fun onExited(event: DragAndDropEvent) { hovering = false }
            override fun onEnded(event: DragAndDropEvent) { hovering = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val native = event.nativeEvent as? java.awt.dnd.DropTargetDropEvent
                if (native != null) {
                    if (native.sourceActions and java.awt.dnd.DnDConstants.ACTION_COPY == 0) return false
                    native.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                }
                val paths = runCatching { droppedFiles(event) }.getOrDefault(emptyList())
                if (paths.isEmpty()) return false
                callback(paths)
                return true
            }
        }
    }
    Box(modifier.dragAndDropTarget(
        shouldStartDragAndDrop = { it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
        target = target
    )) {
        content()
        if (hovering) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).border(2.dp, MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) { Text("松开鼠标导入文件", Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
}
