// 读取图像与媒体元数据，并向图片管线提供 EXIF 方向信息
package io.kaffubox.core

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import java.io.FilterInputStream
import java.io.InputStream

object MediaMetadata {
    fun read(handle: FileHandle): Metadata = handle.openInput().use { input ->
        try { ImageMetadataReader.readMetadata(LimitedInput(input, 64L * 1024 * 1024)) }
        catch (failure: com.drew.imaging.ImageProcessingException) { throw ToolFailure("metadataFormatUnsupported") }
    }

    fun orientation(handle: FileHandle): Int {
        val metadata = try { read(handle) } catch (failure: ToolFailure) { if (failure.code == "metadataFormatUnsupported") return 1 else throw failure }
        return metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)?.takeIf { it in 1..8 } ?: 1
    }

    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("file.metadata", Workbench.File, mapOf("file" to "string"), listOf("file"), setOf(Capability.FileRead)) { args, context ->
            val metadata = read(context.files.resolve(args.string("file")))
            jsonObject("directories" to metadata.directories.map { directory ->
                jsonObject("type" to directory.name, "tags" to directory.tags.map { tag -> jsonObject("id" to tag.tagType, "name" to tag.tagName, "value" to tag.description) }, "errors" to directory.errors.toList())
            })
        }
    )
}

private class LimitedInput(source: InputStream, private val limit: Long) : FilterInputStream(source) {
    private var consumed = 0L
    private fun account(count: Long) { consumed += count.coerceAtLeast(0); if (consumed > limit) throw ToolFailure("metadataSizeLimit") }
    override fun read(): Int = `in`.read().also { if (it >= 0) account(1) }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also { account(it.toLong()) }
    override fun skip(count: Long): Long = `in`.skip(count).also(::account)
}
