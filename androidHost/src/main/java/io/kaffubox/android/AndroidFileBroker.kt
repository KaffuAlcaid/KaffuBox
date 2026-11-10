// 将 Android 授权 URI 和应用私有输出映射为共用文件引用，并支持导出到授权目标
package io.kaffubox.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import io.kaffubox.core.*
import java.io.File

class AndroidFileBroker(private val context: Context, private val outputRoot: File) : FileBroker() {
    private val sourceUris = java.util.concurrent.ConcurrentHashMap<String, Uri>()
    init { check(outputRoot.mkdirs() || outputRoot.isDirectory) }

    fun register(uri: Uri, persist: Boolean = false): FileRef {
        if (uri.scheme != "content") throw ToolFailure("contentUriRequired")
        val resolver = context.contentResolver
        if (persist) resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        var name: String? = null; var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn).takeIf { it >= 0 }
            }
        }
        val ref = FileRef(nextId(), name ?: "Input", size, resolver.getType(uri))
        sourceUris[ref.id] = uri
        return remember(FileHandle(ref, { resolver.openInputStream(uri) ?: throw ToolFailure("fileOpenFailed") }))
    }

    fun sourceUri(id: String): Uri = sourceUris[id] ?: resolve(id).localPath?.let { Uri.fromFile(File(it)) } ?: throw ToolFailure("fileReferenceUnknown")

    fun registerFile(file: File): FileRef {
        if (!file.isFile) throw ToolFailure("fileRequired")
        val ref = FileRef(nextId(), file.name, file.length(), java.net.URLConnection.guessContentTypeFromName(file.name))
        return remember(FileHandle(ref, { file.inputStream() }, localPath = file.absolutePath))
    }

    fun discard(ref: FileRef) {
        val handle = resolve(ref.id)
        val file = File(handle.localPath ?: throw ToolFailure("outputReferenceRequired")).canonicalFile
        if (!handle.ref.writable || !file.toPath().startsWith(outputRoot.canonicalFile.toPath())) throw ToolFailure("outputReferenceRequired")
        file.delete(); revoke(ref.id)
    }

    override fun createOutput(name: String, mimeType: String?): FileRef {
        val safe = safeFileName(name); val extension = safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }; val stem = safe.removeSuffix(extension)
        for (index in 0..9999) {
            val file = File(outputRoot, if (index == 0) safe else "$stem ($index)$extension")
            if (!file.createNewFile()) continue
            val ref = FileRef(nextId(), file.name, null, mimeType, true)
            return remember(FileHandle(ref, { file.inputStream() }, { file.outputStream() }, file.absolutePath, { ref.copy(size = file.length()) }))
        }
        throw ToolFailure("outputNameExhausted")
    }

    fun export(id: String, target: Uri) {
        if (target.scheme != "content") throw ToolFailure("contentUriRequired")
        resolve(id).openInput().use { input ->
            (context.contentResolver.openOutputStream(target, "wt") ?: throw ToolFailure("fileOpenFailed")).use { output -> input.copyTo(output) }
        }
    }
}
