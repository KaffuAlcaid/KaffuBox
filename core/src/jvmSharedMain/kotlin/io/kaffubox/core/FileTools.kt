// 实现流式文件哈希、比较、查重、分割合并、类型识别和受限 ZIP 压缩解压
package io.kaffubox.core

import kotlinx.serialization.json.*
import java.io.BufferedInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object FileTools {
    private val read = setOf(Capability.FileRead)
    private val write = setOf(Capability.FileRead, Capability.FileWrite)
    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("file.hash", Workbench.File, mapOf("file" to "string", "algorithm" to "string"), listOf("file"), read) { args, context ->
            val algorithm = args.string("algorithm", "SHA-256")
            requireArgument(algorithm in setOf("SHA-256", "SHA-512", "SHA-1", "MD5"), "algorithm")
            jsonObject("hash" to digest(context, args.string("file"), algorithm), "algorithm" to algorithm)
        },
        defineTool("file.inspect", Workbench.File, mapOf("file" to "string"), listOf("file"), read) { args, context ->
            val handle = context.files.resolve(args.string("file"))
            val header = handle.openInput().use { input -> val bytes = ByteArray(64); bytes.copyOf(readBlock(input, bytes)) }
            val hex = header.toHex()
            val type = when {
                hex.startsWith("89504e470d0a1a0a") -> "png"
                hex.startsWith("ffd8ff") -> "jpeg"
                header.take(6).toByteArray().toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "gif"
                hex.startsWith("25504446") -> "pdf"
                hex.startsWith("504b0304") || hex.startsWith("504b0506") -> "zip"
                header.size >= 12 && header.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" -> "isoMedia"
                header.size >= 12 && header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" -> header.copyOfRange(8, 12).toString(Charsets.US_ASCII).lowercase()
                hex.startsWith("1f8b") -> "gzip"
                else -> "unknown"
            }
            jsonObject("file" to CoreJson.encodeToJsonElement(handle.ref), "detectedType" to type, "headerHex" to hex)
        },
        defineTool("file.compare", Workbench.File, mapOf("left" to "string", "right" to "string"), listOf("left", "right"), read) { args, context ->
            jsonObject("equal" to compare(context, args.string("left"), args.string("right")))
        },
        defineTool("file.duplicates", Workbench.File, mapOf("files" to "array"), listOf("files"), read) { args, context ->
            val ids = args.strings("files").distinct(); requireArgument(ids.size <= 10_000, "files")
            val hashes = linkedMapOf<String, MutableList<String>>()
            ids.forEachIndexed { index, id ->
                context.checkpoint(index.toLong(), ids.size.toLong(), "hashing")
                hashes.getOrPut(digest(context, id, "SHA-256")) { mutableListOf() }.add(id)
            }
            val groups = mutableListOf<List<String>>()
            for (candidates in hashes.values.filter { it.size > 1 }) {
                val pending = candidates.toMutableList()
                while (pending.isNotEmpty()) {
                    val first = pending.removeAt(0); val matches = mutableListOf(first)
                    val iterator = pending.iterator()
                    while (iterator.hasNext()) { val id = iterator.next(); if (compare(context, first, id)) { matches += id; iterator.remove() } }
                    if (matches.size > 1) groups += matches
                }
            }
            jsonObject("groups" to groups)
        },
        defineTool("file.split", Workbench.File, mapOf("file" to "string", "partBytes" to "integer"), listOf("file", "partBytes"), write) { args, context ->
            val partSize = args.long("partBytes"); requireArgument(partSize in 1..(4L * 1024 * 1024 * 1024), "partBytes")
            val handle = context.files.resolve(args.string("file")); val outputs = mutableListOf<FileRef>(); var total = 0L
            handle.ref.size?.let { requireArgument(it == 0L || (it - 1) / partSize + 1 <= 10_000, "partCount") }
            BufferedInputStream(handle.openInput()).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val first = input.read(); if (first < 0) break
                    requireArgument(outputs.size < 10_000, "partCount")
                    val ref = context.files.createOutput(safeFileName(handle.ref.name + ".part" + (outputs.size + 1).toString().padStart(4, '0')))
                    var written = 1L
                    context.files.resolve(ref.id).openOutput!!.invoke().use { output ->
                        output.write(first)
                        while (written < partSize) {
                            context.checkpoint(total + written, handle.ref.size, "splitting")
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), partSize - written).toInt())
                            if (count < 0) break
                            output.write(buffer, 0, count); written += count
                        }
                    }
                    outputs += ref.copy(size = written); total += written
                }
            }
            jsonObject("files" to CoreJson.encodeToJsonElement(outputs), "bytes" to total)
        },
        defineTool("file.join", Workbench.File, mapOf("files" to "array", "name" to "string"), listOf("files", "name"), write) { args, context ->
            val ids = args.strings("files"); requireArgument(ids.isNotEmpty() && ids.size <= 10_000, "files")
            val output = context.files.createOutput(safeFileName(args.string("name")))
            var total = 0L
            context.files.resolve(output.id).openOutput!!.invoke().use { sink ->
                for (id in ids) context.files.resolve(id).openInput().use { input -> total += copyStream(input, sink, context) }
            }
            jsonObject("file" to CoreJson.encodeToJsonElement(output.copy(size = total)))
        },
        defineTool("file.zip", Workbench.File, mapOf("files" to "array", "name" to "string"), listOf("files"), write) { args, context ->
            val ids = args.strings("files"); requireArgument(ids.size in 1..10_000, "files")
            val handles = ids.map(context.files::resolve)
            requireArgument(handles.map { it.ref.name.lowercase(java.util.Locale.ROOT) }.distinct().size == handles.size, "duplicateEntryNames")
            val output = context.files.createOutput(safeFileName(args.string("name", "Archive.zip")), "application/zip")
            ZipOutputStream(context.files.resolve(output.id).openOutput!!.invoke()).use { zip ->
                handles.forEachIndexed { index, handle ->
                    context.checkpoint(index.toLong(), handles.size.toLong(), "archiving")
                    zip.putNextEntry(ZipEntry(safeFileName(handle.ref.name)))
                    handle.openInput().use { copyStream(it, zip, context) }; zip.closeEntry()
                }
            }
            jsonObject("file" to CoreJson.encodeToJsonElement(output))
        },
        defineTool("file.unzip", Workbench.File, mapOf("file" to "string", "maxBytes" to "integer"), listOf("file"), write) { args, context ->
            val maximum = args.long("maxBytes", 128L * 1024 * 1024); requireArgument(maximum in 1..(2L * 1024 * 1024 * 1024), "maxBytes")
            val outputs = mutableListOf<JsonObject>(); var total = 0L; var entryCount = 0
            ZipInputStream(context.files.resolve(args.string("file")).openInput()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break; entryCount++
                    requireArgument(entryCount <= 5000, "entryCount")
                    val path = entry.name.replace('\\', '/')
                    requireArgument(!path.startsWith('/') && path.split('/').none { it == ".." || ':' in it } && path.length <= 1024, "entryPath")
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    val name = safeFileName(path.trimEnd('/').substringAfterLast('/'))
                    val ref = context.files.createOutput(name)
                    var bytes = 0L
                    context.files.resolve(ref.id).openOutput!!.invoke().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            context.checkpoint(total, maximum, "extracting")
                            val count = zip.read(buffer); if (count < 0) break
                            if (total + count > maximum) throw ToolFailure("archiveSizeLimit")
                            sink.write(buffer, 0, count); total += count; bytes += count
                        }
                    }
                    zip.closeEntry()
                    outputs += jsonObject("entry" to path, "file" to CoreJson.encodeToJsonElement(ref.copy(size = bytes)))
                }
            }
            jsonObject("entries" to outputs, "bytes" to total, "layout" to "flat")
        }
    )

    private suspend fun digest(context: ToolContext, id: String, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm); val handle = context.files.resolve(id); var total = 0L
        handle.openInput().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { context.checkpoint(total, handle.ref.size, "hashing"); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count); total += count }
        }
        return digest.digest().toHex()
    }

    private suspend fun compare(context: ToolContext, left: String, right: String): Boolean {
        val a = context.files.resolve(left); val b = context.files.resolve(right)
        if (a.ref.size != null && b.ref.size != null && a.ref.size != b.ref.size) return false
        a.openInput().use { leftInput -> b.openInput().use { rightInput ->
            val first = ByteArray(128 * 1024); val second = ByteArray(first.size)
            while (true) {
                context.checkpoint(); val leftCount = readBlock(leftInput, first); val rightCount = readBlock(rightInput, second)
                if (leftCount != rightCount) return false
                if (leftCount == 0) return true
                for (i in 0 until leftCount) if (first[i] != second[i]) return false
            }
        } }
    }
}

fun readBlock(input: InputStream, buffer: ByteArray): Int {
    var offset = 0
    while (offset < buffer.size) { val count = input.read(buffer, offset, buffer.size - offset); if (count < 0) break; if (count == 0) continue; offset += count }
    return offset
}

suspend fun copyStream(input: InputStream, output: java.io.OutputStream, context: ToolContext): Long {
    var bytes = 0L; val buffer = ByteArray(128 * 1024)
    while (true) { context.checkpoint(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count); bytes += count }
    return bytes
}
